package com.studyflow.ai;

import java.util.List;
import java.util.UUID;

/**
 * Carries everything an {@link AiProvider} needs for one call (see specs/08-ai-layer.md
 * §Provider abstraction). The caller resolves {@code model} from config keyed by purpose —
 * nothing in this package hardcodes a model id. Request timeout is a fixed client-level setting
 * (this is an async job path, not a browser-waited request), not tuned per call.
 */
public record AiCompletionRequest(
        String purpose,
        int promptVersion,
        List<Message> messages,
        String model,
        int maxTokens,
        double temperature,
        boolean jsonMode,
        UUID ownerId,
        UUID jobId) {

    public record Message(String role, String content) {
    }
}
