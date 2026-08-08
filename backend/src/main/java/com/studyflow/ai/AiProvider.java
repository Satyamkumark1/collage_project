package com.studyflow.ai;

/**
 * Nothing outside the {@code ai} package knows Groq exists (see specs/08-ai-layer.md). A
 * fallback provider is deferred until one is actually needed.
 */
public interface AiProvider {

    AiCompletionResult complete(AiCompletionRequest request);

    /**
     * Streams a completion for a browser-waited call (tutor chat — see
     * specs/01-architecture.md's sync/async boundary table). Never throws: terminal outcome is
     * always exactly one call to {@link StreamListener#onComplete} or
     * {@link StreamListener#onError}, so a caller wiring this to an SSE emitter doesn't need a
     * surrounding try/catch.
     */
    void streamComplete(AiCompletionRequest request, StreamListener listener);

    interface StreamListener {

        void onToken(String delta);

        void onComplete(AiCompletionResult result);

        void onError(RuntimeException error);
    }
}
