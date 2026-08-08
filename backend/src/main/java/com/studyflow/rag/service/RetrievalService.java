package com.studyflow.rag.service;

import java.util.List;
import java.util.UUID;

/**
 * Published interface for other features (tutor) to run hybrid retrieval without injecting
 * {@code DocumentChunkRepository}/{@code ChunkEmbeddingDao} directly — see
 * specs/01-architecture.md cross-feature access rule. Full pipeline detail (vector + lexical +
 * RRF + neighbour expansion) and the parameters used: specs/09-rag.md, docs/DECISIONS.md.
 */
public interface RetrievalService {

    record RetrievedChunk(UUID chunkId, String content, Integer pageFrom, Integer pageTo, String sectionPath) {
    }

    record RetrievalResult(List<RetrievedChunk> chunks, double bestVectorSimilarity) {
    }

    /** Owner- and document-scoped. Empty {@code chunks} and {@code bestVectorSimilarity == 0} if nothing matches. */
    RetrievalResult retrieve(UUID documentId, UUID ownerId, String query);
}
