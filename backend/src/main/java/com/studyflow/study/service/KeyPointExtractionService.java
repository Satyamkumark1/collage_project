package com.studyflow.study.service;

import com.studyflow.ai.prompt.PromptRegistry;
import com.studyflow.ai.service.BatchRepairLoop;
import com.studyflow.ai.service.BatchRepairLoop.BatchRequest;
import com.studyflow.ai.service.BatchRepairLoop.ItemResult;
import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.rag.service.ChunkQueryService;
import com.studyflow.rag.service.ChunkQueryService.ChunkView;
import com.studyflow.study.domain.KeyPointCategory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Extracts key points via {@link BatchRepairLoop} — see docs/status/phase-3.md. No fixed target
 * count (unlike MCQs), so this is the simplest possible batch: extract as many as the material
 * supports, keep whatever validates, no natural "reduce" step (partition-and-concatenate, not
 * partition-and-merge, for documents too large for one call — same partitioning logic as
 * {@code SummaryGenerationService}, without the map-reduce combine step summaries need).
 */
@Service
public class KeyPointExtractionService {

    private static final Logger log = LoggerFactory.getLogger(KeyPointExtractionService.class);
    private static final String PURPOSE = "key-points";
    private static final int PROMPT_VERSION = 1;
    private static final int MAX_INPUT_TOKENS = 6000;
    private static final int MAX_OUTPUT_TOKENS = 3000;

    private final BatchRepairLoop batchRepairLoop;
    private final PromptRegistry promptRegistry;
    private final ChunkQueryService chunkQueryService;
    private final KeyPointPersistenceService keyPointPersistenceService;
    private final String keyPointsModel;

    public KeyPointExtractionService(BatchRepairLoop batchRepairLoop, PromptRegistry promptRegistry,
            ChunkQueryService chunkQueryService, KeyPointPersistenceService keyPointPersistenceService,
            @Value("${studyflow.ai.groq.models.key-points}") String keyPointsModel) {
        this.batchRepairLoop = batchRepairLoop;
        this.promptRegistry = promptRegistry;
        this.chunkQueryService = chunkQueryService;
        this.keyPointPersistenceService = keyPointPersistenceService;
        this.keyPointsModel = keyPointsModel;
    }

    // Deliberately not @Transactional — same rationale as SummaryGenerationService: the Groq
    // call(s) inside batchRepairLoop.run() can take 20-180s each and shouldn't hold a DB
    // transaction open; each save below runs in its own short transaction.
    public List<com.studyflow.study.domain.KeyPoint> extract(UUID documentId, UUID ownerId, UUID jobId) {
        List<ChunkView> chunks = chunkQueryService.findOrderedChunks(documentId, ownerId);
        if (chunks.isEmpty()) {
            throw new ApiException(ErrorCode.AI_INSUFFICIENT_CONTEXT,
                    "Document has no chunks to extract key points from");
        }

        String systemPrompt = promptRegistry.load(PURPOSE, PROMPT_VERSION).content();
        List<List<ChunkView>> groups = partitionByTokenBudget(chunks, MAX_INPUT_TOKENS);
        List<KeyPointDraft> allItems = new ArrayList<>();
        RuntimeException lastFailure = null;
        for (List<ChunkView> group : groups) {
            String userContent = buildChunksMessage(group);
            BatchRequest request = new BatchRequest(PURPOSE, PROMPT_VERSION, keyPointsModel, systemPrompt,
                    userContent, MAX_OUTPUT_TOKENS, 0.3, ownerId, jobId);
            try {
                List<KeyPointDraft> groupItems = batchRepairLoop.run(request,
                        itemNode -> validateItem(itemNode, group));
                allItems.addAll(groupItems);
            } catch (RuntimeException e) {
                lastFailure = e;
                log.warn("Key-point batch failed for document {} group of {} chunk(s): {}", documentId, group.size(),
                        e.getMessage());
            }
        }

        if (allItems.isEmpty()) {
            if (lastFailure != null) {
                throw lastFailure;
            }
            throw new ApiException(ErrorCode.AI_SCHEMA_INVALID,
                    "Model output failed schema/semantic validation after one repair attempt: zero items validated");
        }
        return keyPointPersistenceService.saveAll(documentId, ownerId, jobId, allItems, chunks, keyPointsModel,
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

    private ItemResult<KeyPointDraft> validateItem(JsonNode itemNode, List<ChunkView> validChunks) {
        List<String> violations = new ArrayList<>();

        KeyPointCategory category = null;
        JsonNode categoryNode = itemNode.get("category");
        if (categoryNode == null || !categoryNode.isString()) {
            violations.add("'category' must be a string");
        } else {
            try {
                category = KeyPointCategory.valueOf(categoryNode.asString());
            } catch (IllegalArgumentException e) {
                violations.add("'category' must be one of CONCEPT, DEFINITION, FORMULA, FACT, DATE");
            }
        }

        JsonNode labelNode = itemNode.get("label");
        if (labelNode == null || !labelNode.isString() || labelNode.asString().isBlank()) {
            violations.add("'label' must be a non-empty string");
        }

        JsonNode contentNode = itemNode.get("contentMd");
        if (contentNode == null || !contentNode.isString() || contentNode.asString().isBlank()) {
            violations.add("'contentMd' must be a non-empty string");
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
        return ItemResult.valid(new KeyPointDraft(category, labelNode.asString(), contentNode.asString(), citedIds));
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
