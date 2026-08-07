package com.studyflow.rag.service;

import java.util.List;

/** Groq doesn't serve embeddings — see specs/09-rag.md §Embeddings. */
public interface EmbeddingClient {

    List<float[]> embed(List<String> texts);

    String model();

    String modelVersion();
}
