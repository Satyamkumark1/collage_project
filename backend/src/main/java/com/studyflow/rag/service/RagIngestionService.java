package com.studyflow.rag.service;

import com.studyflow.jobs.service.ProgressReporter;
import com.studyflow.library.domain.DocumentFileType;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Published interface for other features (library) to trigger ingestion without depending on
 * rag's repositories directly (see specs/01-architecture.md cross-feature access rule).
 * {@code stageCallback} lets the caller (which owns the Document entity) mirror each stage onto
 * {@code documents.status} without rag needing to know Document exists.
 */
public interface RagIngestionService {

    enum Stage {
        PARSING,
        CHUNKING,
        EMBEDDING
    }

    record IngestionResult(int pageCount, int charCount, int chunkCount) {
    }

    IngestionResult ingest(UUID documentId, UUID ownerId, byte[] content, DocumentFileType fileType,
            ProgressReporter progress, Consumer<Stage> stageCallback);
}
