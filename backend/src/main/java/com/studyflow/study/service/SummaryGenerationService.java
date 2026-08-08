package com.studyflow.study.service;

import com.studyflow.ai.AiCompletionRequest;
import com.studyflow.ai.AiCompletionResult;
import com.studyflow.ai.AiProvider;
import com.studyflow.ai.domain.AiOutcome;
import com.studyflow.ai.prompt.PromptRegistry;
import com.studyflow.ai.service.AiCallLogger;
import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.rag.service.ChunkQueryService;
import com.studyflow.rag.service.ChunkQueryService.ChunkView;
import com.studyflow.study.domain.Summary;
import com.studyflow.study.repo.SummaryRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Map-reduce over a document's own chunks in stored order (not retrieval — see
 * specs/09-rag.md). Structured-output repair loop: call -> parse -> schema validate -> semantic
 * validate (cited chunk ids belong to this document) -> one repair call -> fail
 * AI_SCHEMA_INVALID (see specs/08-ai-layer.md §Structured output).
 */
@Service
public class SummaryGenerationService {

    private static final String PURPOSE = "summary";
    private static final String REDUCE_PURPOSE = "summary-reduce";
    private static final int PROMPT_VERSION = 1;
    private static final int MAX_INPUT_TOKENS = 6000;
    private static final int MAX_OUTPUT_TOKENS = 800;
    private static final int MIN_WORDS = 40;
    private static final int MAX_WORDS = 150;

    private final AiProvider aiProvider;
    private final PromptRegistry promptRegistry;
    private final AiCallLogger aiCallLogger;
    private final ChunkQueryService chunkQueryService;
    private final SummaryRepository summaryRepository;
    private final ObjectMapper objectMapper;
    private final String summaryModel;

    public SummaryGenerationService(AiProvider aiProvider, PromptRegistry promptRegistry, AiCallLogger aiCallLogger,
            ChunkQueryService chunkQueryService, SummaryRepository summaryRepository, ObjectMapper objectMapper,
            @Value("${studyflow.ai.groq.models.summary}") String summaryModel) {
        this.aiProvider = aiProvider;
        this.promptRegistry = promptRegistry;
        this.aiCallLogger = aiCallLogger;
        this.chunkQueryService = chunkQueryService;
        this.summaryRepository = summaryRepository;
        this.objectMapper = objectMapper;
        this.summaryModel = summaryModel;
    }

    // Deliberately not @Transactional: chunkQueryService's read and summaryRepository.save
    // below each already run in their own short transaction (see ChunkQueryServiceImpl,
    // SimpleJpaRepository), and aiCallLogger.log runs in its own REQUIRES_NEW transaction
    // regardless of any ambient one. Wrapping this method would hold a DB transaction open
    // across the Groq calls in between (summarizeChunks/mapReduce can take 20-180s) for no
    // benefit — there's no multi-statement atomicity requirement spanning the read and the
    // final save.
    public Summary generate(UUID documentId, UUID ownerId, UUID jobId) {
        List<ChunkView> chunks = chunkQueryService.findOrderedChunks(documentId, ownerId);
        if (chunks.isEmpty()) {
            throw new ApiException(ErrorCode.AI_INSUFFICIENT_CONTEXT, "Document has no chunks to summarize");
        }
        Set<String> validChunkIds = chunks.stream().map(c -> c.id().toString()).collect(java.util.stream.Collectors.toSet());

        int totalTokens = chunks.stream().mapToInt(ChunkView::tokenCount).sum();
        GroundedSummary result = totalTokens <= MAX_INPUT_TOKENS
                ? summarizeChunks(chunks, validChunkIds, ownerId, jobId)
                : mapReduce(chunks, validChunkIds, ownerId, jobId);

        String citationsJson = buildCitationsJson(result.citedChunkIds());
        Summary summary = new Summary(documentId, ownerId, result.summary(), citationsJson, summaryModel,
                PROMPT_VERSION, jobId);
        return summaryRepository.save(summary);
    }

    private record GroundedSummary(String summary, Set<String> citedChunkIds) {
    }

    private record ValidationResult(boolean valid, String summary, Set<String> citedChunkIds,
            List<String> violations) {
        static ValidationResult invalid(List<String> violations) {
            return new ValidationResult(false, null, Set.of(), violations);
        }
    }

    private GroundedSummary mapReduce(List<ChunkView> chunks, Set<String> validChunkIds, UUID ownerId,
            UUID jobId) {
        List<List<ChunkView>> groups = partitionByTokenBudget(chunks, MAX_INPUT_TOKENS);
        List<String> partials = new ArrayList<>();
        Set<String> unionCitations = new LinkedHashSet<>();
        for (List<ChunkView> group : groups) {
            GroundedSummary partial = summarizeChunks(group, validChunkIds, ownerId, jobId);
            partials.add(partial.summary());
            unionCitations.addAll(partial.citedChunkIds());
        }
        String combined = reduce(partials, ownerId, jobId);
        return new GroundedSummary(combined, unionCitations);
    }

    private List<List<ChunkView>> partitionByTokenBudget(List<ChunkView> chunks, int budget) {
        List<List<ChunkView>> groups = new ArrayList<>();
        List<ChunkView> current = new ArrayList<>();
        int currentTokens = 0;
        for (ChunkView chunk : chunks) {
            if (currentTokens + chunk.tokenCount() > budget && !current.isEmpty()) {
                groups.add(current);
                current = new ArrayList<>();
                currentTokens = 0;
            }
            current.add(chunk);
            currentTokens += chunk.tokenCount();
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    private GroundedSummary summarizeChunks(List<ChunkView> chunks, Set<String> validChunkIds, UUID ownerId,
            UUID jobId) {
        String systemPrompt = promptRegistry.load(PURPOSE, PROMPT_VERSION).content();
        String userContent = buildChunksMessage(chunks);

        List<AiCompletionRequest.Message> firstMessages = List.of(
                new AiCompletionRequest.Message("system", systemPrompt),
                new AiCompletionRequest.Message("user", userContent));
        AiCompletionResult first = callProvider(PURPOSE, firstMessages, ownerId, jobId);
        ValidationResult validated = validate(first.content(), validChunkIds);
        logCall(ownerId, jobId, first, validated.valid() ? AiOutcome.OK : AiOutcome.SCHEMA_FAIL, 1);
        if (validated.valid()) {
            return new GroundedSummary(validated.summary(), validated.citedChunkIds());
        }

        String repairInstruction = buildRepairInstruction(validated.violations());
        List<AiCompletionRequest.Message> repairMessages = List.of(
                new AiCompletionRequest.Message("system", systemPrompt),
                new AiCompletionRequest.Message("user", userContent),
                new AiCompletionRequest.Message("assistant", first.content()),
                new AiCompletionRequest.Message("user", repairInstruction));
        AiCompletionResult repaired = callProvider(PURPOSE, repairMessages, ownerId, jobId);
        ValidationResult repairedValidated = validate(repaired.content(), validChunkIds);
        logCall(ownerId, jobId, repaired, repairedValidated.valid() ? AiOutcome.REPAIRED : AiOutcome.SCHEMA_FAIL, 2);
        if (repairedValidated.valid()) {
            return new GroundedSummary(repairedValidated.summary(), repairedValidated.citedChunkIds());
        }

        throw new ApiException(ErrorCode.AI_SCHEMA_INVALID,
                "Model output failed schema/semantic validation after one repair attempt: "
                        + repairedValidated.violations());
    }

    private String reduce(List<String> partialSummaries, UUID ownerId, UUID jobId) {
        String systemPrompt = promptRegistry.load(REDUCE_PURPOSE, PROMPT_VERSION).content();
        StringBuilder sb = new StringBuilder("<<<SUMMARIES>>>\n");
        for (String partial : partialSummaries) {
            sb.append("- ").append(partial).append('\n');
        }
        sb.append("<<<END_SUMMARIES>>>");
        List<AiCompletionRequest.Message> messages = List.of(
                new AiCompletionRequest.Message("system", systemPrompt),
                new AiCompletionRequest.Message("user", sb.toString()));
        AiCompletionRequest request = new AiCompletionRequest(REDUCE_PURPOSE, PROMPT_VERSION, messages, summaryModel,
                MAX_OUTPUT_TOKENS, 0.3, false, ownerId, jobId);
        AiCompletionResult result = aiProvider.complete(request);
        aiCallLogger.log(ownerId, jobId, "groq", summaryModel, REDUCE_PURPOSE, PROMPT_VERSION, result.tokensIn(),
                result.tokensOut(), (int) result.latencyMs(), result.finishReason(), AiOutcome.OK, 1);
        return result.content().strip();
    }

    private AiCompletionResult callProvider(String purpose, List<AiCompletionRequest.Message> messages, UUID ownerId,
            UUID jobId) {
        AiCompletionRequest request = new AiCompletionRequest(purpose, PROMPT_VERSION, messages, summaryModel,
                MAX_OUTPUT_TOKENS, 0.3, true, ownerId, jobId);
        return aiProvider.complete(request);
    }

    private void logCall(UUID ownerId, UUID jobId, AiCompletionResult result, AiOutcome outcome, int attemptNo) {
        aiCallLogger.log(ownerId, jobId, "groq", summaryModel, PURPOSE, PROMPT_VERSION, result.tokensIn(),
                result.tokensOut(), (int) result.latencyMs(), result.finishReason(), outcome, attemptNo);
    }

    private String buildChunksMessage(List<ChunkView> chunks) {
        StringBuilder sb = new StringBuilder("<<<DOCUMENT>>>\n");
        for (ChunkView chunk : chunks) {
            sb.append("[chunk: ").append(chunk.id()).append("]\n");
            sb.append(chunk.content()).append("\n\n");
        }
        sb.append("<<<END_DOCUMENT>>>");
        return sb.toString();
    }

    private ValidationResult validate(String rawContent, Set<String> validChunkIds) {
        String jsonText = stripMarkdownFences(rawContent);
        JsonNode node;
        try {
            node = objectMapper.readTree(jsonText);
        } catch (Exception e) {
            return ValidationResult.invalid(List.of("Response was not valid JSON: " + e.getMessage()));
        }

        List<String> violations = new ArrayList<>();
        JsonNode summaryNode = node.get("summary");
        if (summaryNode == null || !summaryNode.isString() || summaryNode.asString().isBlank()) {
            violations.add("'summary' must be a non-empty string");
        }
        JsonNode citedNode = node.get("citedChunkIds");
        if (citedNode == null || !citedNode.isArray() || citedNode.isEmpty()) {
            violations.add("'citedChunkIds' must be a non-empty array");
        }
        if (!violations.isEmpty()) {
            return ValidationResult.invalid(violations);
        }

        String summary = summaryNode.asString();
        int wordCount = summary.strip().split("\\s+").length;
        if (wordCount < MIN_WORDS || wordCount > MAX_WORDS) {
            violations.add("'summary' must be roughly " + MIN_WORDS + "-" + MAX_WORDS + " words (got " + wordCount
                    + ")");
        }

        Set<String> citedIds = new LinkedHashSet<>();
        List<String> invalidIds = new ArrayList<>();
        for (JsonNode idNode : citedNode) {
            String id = idNode.asString();
            if (validChunkIds.contains(id)) {
                citedIds.add(id);
            } else {
                invalidIds.add(id);
            }
        }
        if (!invalidIds.isEmpty()) {
            violations.add("citedChunkIds contains ids that don't belong to this document: " + invalidIds);
        }
        if (citedIds.isEmpty()) {
            violations.add("No valid citedChunkIds remained after validation");
        }

        if (!violations.isEmpty()) {
            return ValidationResult.invalid(violations);
        }
        return new ValidationResult(true, summary, citedIds, List.of());
    }

    private String stripMarkdownFences(String content) {
        String trimmed = content.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return trimmed;
    }

    private String buildRepairInstruction(List<String> violations) {
        return "Your previous response had these problems:\n- " + String.join("\n- ", violations)
                + "\n\nRespond again with corrected JSON only, fixing these specific issues. Same schema as "
                + "before: {\"summary\": \"...\", \"citedChunkIds\": [...]}.";
    }

    private String buildCitationsJson(Set<String> citedChunkIds) {
        List<Map<String, String>> citations = citedChunkIds.stream().map(id -> Map.of("chunkId", id)).toList();
        return objectMapper.writeValueAsString(citations);
    }
}
