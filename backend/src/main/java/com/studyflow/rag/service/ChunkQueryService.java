package com.studyflow.rag.service;

import java.util.List;
import java.util.UUID;

/**
 * Published interface for other features (study) to read a document's chunks without injecting
 * {@code DocumentChunkRepository} directly — see specs/01-architecture.md cross-feature access
 * rule.
 */
public interface ChunkQueryService {

    record ChunkView(UUID id, String content, int tokenCount) {
    }

    /** Owner-scoped, in stored (chunk_index) order. */
    List<ChunkView> findOrderedChunks(UUID documentId, UUID ownerId);
}
