package com.studyflow.study.service;

import com.studyflow.ai.prompt.PromptRegistry;
import com.studyflow.ai.service.BatchRepairLoop;
import com.studyflow.ai.service.BatchRepairLoop.BatchRequest;
import com.studyflow.ai.service.BatchRepairLoop.ItemResult;
import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.rag.service.ChunkQueryService;
import com.studyflow.rag.service.ChunkQueryService.ChunkView;
import com.studyflow.study.domain.BloomLevel;
import com.studyflow.study.domain.Difficulty;
import com.studyflow.study.domain.QuestionSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Batch MCQ generation via {@link BatchRepairLoop} — see docs/DECISIONS.md for the fresh design
 * calls (40/20/40 EASY/MEDIUM/HARD difficulty mix, Bloom level pairing, chunk-coverage steering
 * via an explicit per-question target list). No natural "reduce" step for a question set (unlike
 * summary's map-reduce), so documents too large for one call are handled by
 * partition-and-concatenate: split chunks into token-budget groups, split the requested count
 * proportionally across groups, generate each group independently, concatenate, trim to exactly
 * requestedCount if rounding overshot.
 */
@Service
public class McqGenerationService {

    private static final Logger log = LoggerFactory.getLogger(McqGenerationService.class);
    private static final String PURPOSE = "mcq";
    private static final int PROMPT_VERSION = 1;
    private static final int MAX_INPUT_TOKENS = 8000;
    private static final int OUTPUT_TOKENS_PER_QUESTION = 150;
    private static final List<String> LAZY_OPTION_PATTERNS = List.of("all of the above", "none of the above");

    private final BatchRepairLoop batchRepairLoop;
    private final PromptRegistry promptRegistry;
    private final ChunkQueryService chunkQueryService;
    private final McqPersistenceService mcqPersistenceService;
    private final String mcqModel;

    public McqGenerationService(BatchRepairLoop batchRepairLoop, PromptRegistry promptRegistry,
            ChunkQueryService chunkQueryService, McqPersistenceService mcqPersistenceService,
            @Value("${studyflow.ai.groq.models.mcq}") String mcqModel) {
        this.batchRepairLoop = batchRepairLoop;
        this.promptRegistry = promptRegistry;
        this.chunkQueryService = chunkQueryService;
        this.mcqPersistenceService = mcqPersistenceService;
        this.mcqModel = mcqModel;
    }

    private record Target(int displayIndex, Difficulty difficulty, BloomLevel bloomLevel, String anchorChunkId) {
    }

    // Deliberately not @Transactional — same rationale as KeyPointExtractionService: the Groq
    // call(s) can take 20-180s each and shouldn't hold a DB transaction open; persistence runs in
    // its own short transaction.
    public QuestionSet generate(UUID documentId, UUID ownerId, UUID jobId, int requestedCount) {
        List<ChunkView> chunks = chunkQueryService.findOrderedChunks(documentId, ownerId);
        if (chunks.isEmpty()) {
            throw new ApiException(ErrorCode.AI_INSUFFICIENT_CONTEXT, "Document has no chunks to generate MCQs from");
        }

        Map<Difficulty, Integer> difficultyMix = difficultyMixFor(requestedCount);
        String systemPrompt = promptRegistry.load(PURPOSE, PROMPT_VERSION).content();

        List<List<ChunkView>> groups = partitionByTokenBudget(chunks, MAX_INPUT_TOKENS);
        List<Integer> groupCounts = splitCountAcrossGroups(requestedCount, groups.size());

        List<QuestionDraft> allItems = new ArrayList<>();
        RuntimeException lastFailure = null;
        for (int g = 0; g < groups.size(); g++) {
            int groupRequested = groupCounts.get(g);
            if (groupRequested == 0) {
                continue;
            }
            List<ChunkView> group = groups.get(g);
            List<Target> targets = planTargets(difficultyMixFor(groupRequested), group);
            String userContent = buildUserContent(group, targets);
            BatchRequest request = new BatchRequest(PURPOSE, PROMPT_VERSION, mcqModel, systemPrompt, userContent,
                    OUTPUT_TOKENS_PER_QUESTION * groupRequested, 0.5, ownerId, jobId);
            try {
                allItems.addAll(batchRepairLoop.run(request, itemNode -> validateItem(itemNode, group)));
            } catch (RuntimeException e) {
                lastFailure = e;
                log.warn("MCQ batch failed for document {} group of {} chunk(s): {}", documentId, group.size(),
                        e.getMessage());
            }
        }

        if (allItems.size() > requestedCount) {
            allItems = allItems.subList(0, requestedCount);
        }
        if (allItems.isEmpty()) {
            if (lastFailure != null) {
                throw lastFailure;
            }
            throw new ApiException(ErrorCode.AI_SCHEMA_INVALID,
                    "Model output failed schema/semantic validation after one repair attempt: zero items validated");
        }

        return mcqPersistenceService.saveAll(documentId, ownerId, jobId, (short) requestedCount, allItems, chunks,
                difficultyMix, mcqModel, PROMPT_VERSION);
    }

    private Map<Difficulty, Integer> difficultyMixFor(int n) {
        int easy = Math.round(n * 0.4f);
        int medium = Math.round(n * 0.4f);
        int hard = Math.max(0, n - easy - medium);
        Map<Difficulty, Integer> mix = new LinkedHashMap<>();
        mix.put(Difficulty.EASY, easy);
        mix.put(Difficulty.MEDIUM, medium);
        mix.put(Difficulty.HARD, hard);
        return mix;
    }

    /** Round-robin interleave of the difficulty buckets, then bloom-pair and anchor each slot. */
    private List<Target> planTargets(Map<Difficulty, Integer> mix, List<ChunkView> chunks) {
        List<Difficulty> ordered = interleaveDifficulties(mix.getOrDefault(Difficulty.EASY, 0),
                mix.getOrDefault(Difficulty.MEDIUM, 0), mix.getOrDefault(Difficulty.HARD, 0));

        Map<Difficulty, Integer> seen = new LinkedHashMap<>();
        List<Target> targets = new ArrayList<>();
        int total = ordered.size();
        for (int i = 0; i < total; i++) {
            Difficulty difficulty = ordered.get(i);
            int occurrence = seen.merge(difficulty, 1, Integer::sum) - 1;
            BloomLevel bloomLevel = bloomLevelFor(difficulty, occurrence);
            int anchorIndex = Math.min((i * chunks.size()) / total, chunks.size() - 1);
            String anchorChunkId = chunks.get(anchorIndex).id().toString();
            targets.add(new Target(i + 1, difficulty, bloomLevel, anchorChunkId));
        }
        return targets;
    }

    private List<Difficulty> interleaveDifficulties(int easy, int medium, int hard) {
        List<Difficulty> result = new ArrayList<>();
        int e = easy;
        int m = medium;
        int h = hard;
        while (e > 0 || m > 0 || h > 0) {
            if (e > 0) {
                result.add(Difficulty.EASY);
                e--;
            }
            if (m > 0) {
                result.add(Difficulty.MEDIUM);
                m--;
            }
            if (h > 0) {
                result.add(Difficulty.HARD);
                h--;
            }
        }
        return result;
    }

    /** Every 4th question at a given difficulty gets the secondary Bloom level, else the primary. */
    private BloomLevel bloomLevelFor(Difficulty difficulty, int occurrence) {
        boolean secondary = occurrence % 4 == 3;
        return switch (difficulty) {
            case EASY -> secondary ? BloomLevel.UNDERSTAND : BloomLevel.REMEMBER;
            case MEDIUM -> secondary ? BloomLevel.APPLY : BloomLevel.UNDERSTAND;
            case HARD -> secondary ? BloomLevel.ANALYZE : BloomLevel.APPLY;
        };
    }

    private List<Integer> splitCountAcrossGroups(int total, int groups) {
        List<Integer> counts = new ArrayList<>();
        int base = total / groups;
        int remainder = total % groups;
        for (int i = 0; i < groups; i++) {
            counts.add(base + (i < remainder ? 1 : 0));
        }
        return counts;
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

    private String buildUserContent(List<ChunkView> chunks, List<Target> targets) {
        StringBuilder sb = new StringBuilder("<<<DOCUMENT>>>\n");
        for (ChunkView chunk : chunks) {
            sb.append("[chunk: ").append(chunk.id()).append("]\n");
            sb.append(chunk.content()).append("\n\n");
        }
        sb.append("<<<END_DOCUMENT>>>\n\n<<<TARGETS>>>\n");
        for (Target target : targets) {
            sb.append("Question ").append(target.displayIndex()).append(": ").append(target.difficulty())
                    .append(" / ").append(target.bloomLevel()).append(", primarily from chunk ")
                    .append(target.anchorChunkId()).append('\n');
        }
        sb.append("<<<END_TARGETS>>>");
        return sb.toString();
    }

    private ItemResult<QuestionDraft> validateItem(JsonNode itemNode, List<ChunkView> validChunks) {
        List<String> violations = new ArrayList<>();

        JsonNode stemNode = itemNode.get("stem");
        if (stemNode == null || !stemNode.isString() || stemNode.asString().strip().length() < 8) {
            violations.add("'stem' must be a non-empty question, at least 8 characters");
        }

        List<String> options = new ArrayList<>();
        JsonNode optionsNode = itemNode.get("options");
        if (optionsNode == null || !optionsNode.isArray() || optionsNode.size() != 4) {
            violations.add("'options' must be an array of exactly 4 strings");
        } else {
            List<String> normalized = new ArrayList<>();
            for (JsonNode optionNode : optionsNode) {
                if (!optionNode.isString() || optionNode.asString().isBlank()) {
                    violations.add("every option must be a non-empty string");
                    break;
                }
                String option = optionNode.asString();
                String norm = option.strip().toLowerCase(Locale.ROOT);
                if (LAZY_OPTION_PATTERNS.contains(norm) || norm.matches("both [a-d] and [a-d]")) {
                    violations.add("options may not use a lazy catch-all like 'all of the above'");
                }
                if (normalized.contains(norm)) {
                    violations.add("options must be pairwise distinct");
                }
                normalized.add(norm);
                options.add(option);
            }
        }

        int correctIndex = -1;
        JsonNode correctIndexNode = itemNode.get("correctIndex");
        if (correctIndexNode == null || !correctIndexNode.isInt() || correctIndexNode.asInt() < 0
                || correctIndexNode.asInt() > 3) {
            violations.add("'correctIndex' must be an integer 0-3");
        } else {
            correctIndex = correctIndexNode.asInt();
        }

        JsonNode explanationNode = itemNode.get("explanation");
        if (explanationNode == null || !explanationNode.isString() || explanationNode.asString().isBlank()) {
            violations.add("'explanation' must be a non-empty string");
        }

        Difficulty difficulty = null;
        JsonNode difficultyNode = itemNode.get("difficulty");
        if (difficultyNode == null || !difficultyNode.isString()) {
            violations.add("'difficulty' must be a string");
        } else {
            try {
                difficulty = Difficulty.valueOf(difficultyNode.asString());
            } catch (IllegalArgumentException e) {
                violations.add("'difficulty' must be one of EASY, MEDIUM, HARD");
            }
        }

        BloomLevel bloomLevel = null;
        JsonNode bloomNode = itemNode.get("bloomLevel");
        if (bloomNode == null || !bloomNode.isString()) {
            violations.add("'bloomLevel' must be a string");
        } else {
            try {
                bloomLevel = BloomLevel.valueOf(bloomNode.asString());
            } catch (IllegalArgumentException e) {
                violations.add("'bloomLevel' must be one of REMEMBER, UNDERSTAND, APPLY, ANALYZE");
            }
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
        return ItemResult.valid(new QuestionDraft(stemNode.asString(), options, correctIndex,
                explanationNode.asString(), difficulty, bloomLevel, citedIds));
    }
}
