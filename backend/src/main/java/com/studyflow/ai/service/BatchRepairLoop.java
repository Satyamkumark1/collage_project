package com.studyflow.ai.service;

import com.studyflow.ai.AiCompletionRequest;
import com.studyflow.ai.AiCompletionRequest.Message;
import com.studyflow.ai.AiCompletionResult;
import com.studyflow.ai.AiProvider;
import com.studyflow.ai.domain.AiOutcome;
import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.jobs.domain.TransientJobException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared partial-success variant of {@code SummaryGenerationService}'s structured-output repair
 * loop, generalized to a batch of independently-generated items: call -> parse a
 * {@code {"items": [...]}} response -> validate each item -> if some are malformed, one repair
 * call for only the malformed subset (bounds repair cost to O(invalid), not O(N)) -> merge -> drop
 * anything still invalid or a length-mismatched repair response, without a second repair attempt.
 * Zero surviving items after repair is total failure ({@code AI_SCHEMA_INVALID}), not partial
 * success — see docs/status/phase-3.md and docs/DECISIONS.md.
 */
@Component
public class BatchRepairLoop {

    private final AiProvider aiProvider;
    private final AiCallLogger aiCallLogger;
    private final ObjectMapper objectMapper;

    public BatchRepairLoop(AiProvider aiProvider, AiCallLogger aiCallLogger, ObjectMapper objectMapper) {
        this.aiProvider = aiProvider;
        this.aiCallLogger = aiCallLogger;
        this.objectMapper = objectMapper;
    }

    /** Supplied by the feature to parse+validate one item's raw JSON node. */
    public interface ItemValidator<T> {
        ItemResult<T> validate(JsonNode itemNode);
    }

    public record ItemResult<T>(boolean valid, T value, List<String> violations) {
        public static <T> ItemResult<T> valid(T value) {
            return new ItemResult<>(true, value, List.of());
        }

        public static <T> ItemResult<T> invalid(List<String> violations) {
            return new ItemResult<>(false, null, violations);
        }
    }

    public record BatchRequest(String purpose, int promptVersion, String model, String systemPrompt,
            String userContent, int maxOutputTokens, double temperature, UUID ownerId, UUID jobId) {
    }

    private record InvalidItem(JsonNode node, List<String> violations) {
    }

    /**
     * @throws ApiException(AI_SCHEMA_INVALID) if zero items validate, even after the repair call.
     */
    public <T> List<T> run(BatchRequest request, ItemValidator<T> validator) {
        List<Message> firstMessages = List.of(
                new Message("system", request.systemPrompt()),
                new Message("user", request.userContent()));

        AiCompletionResult first;
        boolean usedRepairAttempt;
        try {
            first = callProvider(request, firstMessages);
            usedRepairAttempt = false;
        } catch (TransientJobException e) {
            // Retryable (429/5xx/timeout) — let it propagate untouched so the job engine's own
            // backoff/retry handles it, rather than burning this loop's one repair attempt on
            // something a requeue would fix better.
            throw e;
        } catch (RuntimeException firstCallFailure) {
            // A real failure mode observed live during Phase 3 checkpoint 16 testing: Groq's own
            // json_object-mode validator can reject a generation outright (400
            // json_validate_failed, empty failed_generation — no content to inspect at all).
            // There's nothing to build a targeted repair instruction from, so this consumes the
            // loop's one repair attempt as a plain retry of the identical request instead.
            usedRepairAttempt = true;
            try {
                first = callProvider(request, firstMessages);
            } catch (RuntimeException secondCallFailure) {
                throw new ApiException(ErrorCode.AI_SCHEMA_INVALID,
                        "Model call failed on both the initial attempt and the one repair attempt: "
                                + secondCallFailure.getMessage());
            }
        }

        List<JsonNode> rawItems = parseItems(first.content());
        List<T> valid = new ArrayList<>();
        List<InvalidItem> invalid = new ArrayList<>();
        for (JsonNode itemNode : rawItems) {
            ItemResult<T> result = validateSafely(itemNode, validator);
            if (result.valid()) {
                valid.add(result.value());
            } else {
                invalid.add(new InvalidItem(itemNode, result.violations()));
            }
        }
        logCall(request, first, invalid.isEmpty() && !valid.isEmpty() ? AiOutcome.OK : AiOutcome.SCHEMA_FAIL,
                usedRepairAttempt ? 2 : 1);

        // The plain retry above already spent this loop's one repair attempt — don't also run
        // the targeted item-level repair call below, even if the retry's own output has some
        // malformed items.
        if (!invalid.isEmpty() && !usedRepairAttempt) {
            List<Message> repairMessages = List.of(
                    new Message("system", request.systemPrompt()),
                    new Message("user", request.userContent()),
                    new Message("assistant", first.content()),
                    new Message("user", buildRepairInstruction(invalid)));
            AiCompletionResult repaired = callProvider(request, repairMessages);
            List<JsonNode> repairedItems = parseItems(repaired.content());

            int repairedValidCount = 0;
            // A length-mismatched repair response can't be reliably matched back to the
            // malformed items it was supposed to replace — drop the whole malformed set rather
            // than guessing a mapping. No second repair attempt either way (see class javadoc).
            if (repairedItems.size() == invalid.size()) {
                for (JsonNode itemNode : repairedItems) {
                    ItemResult<T> result = validateSafely(itemNode, validator);
                    if (result.valid()) {
                        valid.add(result.value());
                        repairedValidCount++;
                    }
                }
            }
            logCall(request, repaired, repairedValidCount > 0 ? AiOutcome.REPAIRED : AiOutcome.SCHEMA_FAIL, 2);
        }

        if (valid.isEmpty()) {
            throw new ApiException(ErrorCode.AI_SCHEMA_INVALID,
                    "Model output failed schema/semantic validation after one repair attempt: zero items validated");
        }
        return valid;
    }

    private <T> ItemResult<T> validateSafely(JsonNode itemNode, ItemValidator<T> validator) {
        try {
            return validator.validate(itemNode);
        } catch (RuntimeException e) {
            String violation = "Validator threw " + e.getClass().getSimpleName()
                    + (e.getMessage() == null || e.getMessage().isBlank() ? "" : ": " + e.getMessage());
            return ItemResult.invalid(List.of(violation));
        }
    }

    private AiCompletionResult callProvider(BatchRequest request, List<Message> messages) {
        AiCompletionRequest completionRequest = new AiCompletionRequest(request.purpose(), request.promptVersion(),
                messages, request.model(), request.maxOutputTokens(), request.temperature(), true, request.ownerId(),
                request.jobId());
        return aiProvider.complete(completionRequest);
    }

    private void logCall(BatchRequest request, AiCompletionResult result, AiOutcome outcome, int attemptNo) {
        aiCallLogger.log(request.ownerId(), request.jobId(), "groq", request.model(), request.purpose(),
                request.promptVersion(), result.tokensIn(), result.tokensOut(), (int) result.latencyMs(),
                result.finishReason(), outcome, attemptNo);
    }

    private List<JsonNode> parseItems(String rawContent) {
        String jsonText = stripMarkdownFences(rawContent);
        JsonNode root;
        try {
            root = objectMapper.readTree(jsonText);
        } catch (Exception e) {
            return List.of();
        }
        JsonNode items = root.get("items");
        if (items == null || !items.isArray()) {
            return List.of();
        }
        List<JsonNode> result = new ArrayList<>();
        items.forEach(result::add);
        return result;
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

    private String buildRepairInstruction(List<InvalidItem> invalidItems) {
        StringBuilder sb = new StringBuilder(
                "Some items in your previous response had problems. Respond with JSON only, matching this "
                        + "shape: {\"items\": [...]}, containing exactly " + invalidItems.size()
                        + " replacement item(s) — one corrected replacement for each malformed item listed "
                        + "below, in the same order. Do not include replacements for items that were already "
                        + "correct, and do not include any commentary.\n");
        for (int i = 0; i < invalidItems.size(); i++) {
            InvalidItem item = invalidItems.get(i);
            sb.append("\nMalformed item ").append(i + 1).append(" (original: ").append(item.node()).append("):\n");
            sb.append("- ").append(String.join("\n- ", item.violations()));
        }
        return sb.toString();
    }
}
