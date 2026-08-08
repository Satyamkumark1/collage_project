package com.studyflow.ai;

/**
 * Nothing outside the {@code ai} package knows Groq exists (see specs/08-ai-layer.md). A
 * fallback provider is deferred until one is actually needed.
 */
public interface AiProvider {

    AiCompletionResult complete(AiCompletionRequest request);
}
