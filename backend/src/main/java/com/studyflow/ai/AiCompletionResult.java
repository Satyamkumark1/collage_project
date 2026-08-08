package com.studyflow.ai;

public record AiCompletionResult(
        String content,
        String finishReason,
        int tokensIn,
        int tokensOut,
        long latencyMs,
        String model) {
}
