package com.studyflow.study.service;

import com.studyflow.ai.prompt.PromptRegistry;
import com.studyflow.ai.service.BatchRepairLoop;
import com.studyflow.ai.service.BatchRepairLoop.BatchRequest;
import com.studyflow.ai.service.BatchRepairLoop.ItemResult;
import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.rag.service.ChunkQueryService;
import com.studyflow.rag.service.ChunkQueryService.ChunkView;
import com.studyflow.study.domain.Flashcard;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Extracts flashcards via {@link BatchRepairLoop} — same shape as
 * {@link KeyPointExtractionService}: no fixed target count, partition-and-concatenate for
 * documents too large for one call, per-group failure tolerance (a malformed group doesn't sink
 * the whole batch). See docs/status/phase-3.md.
 */
@Service
public class FlashcardGenerationService {

    private static final Logger log = LoggerFactory.getLogger(FlashcardGenerationService.class);
    private static final String PURPOSE = "flashcard";
    private static final int PROMPT_VERSION = 1;
    private static final int MAX_INPUT_TOKENS = 6000;
    private static final int MAX_OUTPUT_TOKENS = 3000;

    private final BatchRepairLoop batchRepairLoop;
    private final PromptRegistry promptRegistry;
    private final ChunkQueryService chunkQueryService;
    private final FlashcardPersistenceService flashcardPersistenceService;
    private final String flashcardModel;

    public FlashcardGenerationService(BatchRepairLoop batchRepairLoop, PromptRegistry promptRegistry,
            ChunkQueryService chunkQueryService, FlashcardPersistenceService flashcardPersistenceService,
            @Value("${studyflow.ai.groq.models.flashcard}") String flashcardModel) {
        this.batchRepairLoop = batchRepairLoop;
        this.promptRegistry = promptRegistry;
        this.chunkQueryService = chunkQueryService;
        this.flashcardPersistenceService = flashcardPersistenceService;
        this.flashcardModel = flashcardModel;
    }

    // Deliberately not @Transactional — same rationale as KeyPointExtractionService/
    // McqGenerationService: the Groq call(s) can take 20-180s each and shouldn't hold a DB
    // transaction open; persistence runs in its own short transaction.
    public List<Flashcard> generate(UUID documentId, UUID ownerId, UUID jobId) {
        List<ChunkView> chunks = chunkQueryService.findOrderedChunks(documentId, ownerId);
        if (chunks.isEmpty()) {
            throw new ApiException(ErrorCode.AI_INSUFFICIENT_CONTEXT, "Document has no chunks to generate flashcards from");
        }

        String systemPrompt = promptRegistry.load(PURPOSE, PROMPT_VERSION).content();
        List<List<ChunkView>> groups = partitionByTokenBudget(chunks, MAX_INPUT_TOKENS);
        List<FlashcardDraft> allDrafts = new ArrayList<>();
        RuntimeException lastFailure = null;
        for (List<ChunkView> group : groups) {
            String userContent = buildChunksMessage(group);
            BatchRequest request = new BatchRequest(PURPOSE, PROMPT_VERSION, flashcardModel, systemPrompt,
                    userContent, MAX_OUTPUT_TOKENS, 0.3, ownerId, jobId);
            try {
                allDrafts.addAll(batchRepairLoop.run(request, itemNode -> validateItem(itemNode, group)));
            } catch (RuntimeException e) {
                lastFailure = e;
                log.warn("Flashcard batch failed for document {} group of {} chunk(s): {}", documentId, group.size(),
                        e.getMessage());
            }
        }

        if (allDrafts.isEmpty()) {
            if (lastFailure != null) {
                throw lastFailure;
            }
            throw new ApiException(ErrorCode.AI_SCHEMA_INVALID,
                    "Model output failed schema/semantic validation after one repair attempt: zero items validated");
        }
        return flashcardPersistenceService.saveAll(documentId, ownerId, jobId, allDrafts, chunks, flashcardModel,
                PROMPT_VERSION);
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

    private ItemResult<FlashcardDraft> validateItem(JsonNode itemNode, List<ChunkView> validChunks) {
        List<String> violations = new ArrayList<>();

        JsonNode frontNode = itemNode.get("frontMd");
        if (frontNode == null || !frontNode.isString() || frontNode.asString().isBlank()) {
            violations.add("'frontMd' must be a non-empty string");
        }

        JsonNode backNode = itemNode.get("backMd");
        if (backNode == null || !backNode.isString() || backNode.asString().isBlank()) {
            violations.add("'backMd' must be a non-empty string");
        }

        List<String> citedIds = new ArrayList<>();
        JsonNode citedNode = itemNode.get("citedChunkIds");
        if (citedNode == null || !citedNode.isArray() || citedNode.isEmpty()) {
            violations.add("'citedChunkIds' must be a non-empty array");
        } else {
            List<String> invalidIds = new ArrayList<>();
            for (JsonNode idNode : citedNode) {
                String id = idNode.asString();
                if (validChunks.stream().anyMatch(chunk -> chunk.id().toString().equals(id))) {
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
        }

        if (!violations.isEmpty()) {
            return ItemResult.invalid(violations);
        }
        return ItemResult.valid(new FlashcardDraft(frontNode.asString(), backNode.asString(), citedIds));
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
}
