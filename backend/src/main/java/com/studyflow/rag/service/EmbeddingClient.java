package com.studyflow.rag.service;

import java.util.List;
import java.util.function.IntConsumer;

/** Groq doesn't serve embeddings — see specs/09-rag.md §Embeddings. */
public interface EmbeddingClient {

    List<float[]> embed(List<String> texts);

    /** Same as {@link #embed(List)}, but reports cumulative embedded-count after each batch. */
    default List<float[]> embed(List<String> texts, IntConsumer onBatchEmbedded) {
        return embed(texts);
    }

    String model();

    String modelVersion();
}
