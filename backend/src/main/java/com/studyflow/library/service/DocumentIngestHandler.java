package com.studyflow.library.service;

import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.TaskType;
import com.studyflow.jobs.domain.TransientJobException;
import com.studyflow.jobs.service.JobHandler;
import com.studyflow.jobs.service.ProgressReporter;
import com.studyflow.library.domain.Document;
import com.studyflow.library.repo.DocumentRepository;
import com.studyflow.rag.domain.DocumentParsingException;
import com.studyflow.rag.service.RagIngestionService;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns the {@code documents.status} lifecycle (UPLOADED -> PARSING -> CHUNKING -> EMBEDDING ->
 * READY/FAILED); delegates the actual parse/chunk/embed work to {@link RagIngestionService}
 * (rag's published interface) rather than reaching into rag's repositories directly.
 */
@Component
public class DocumentIngestHandler implements JobHandler {

    private final DocumentRepository documentRepository;
    private final StorageProvider storageProvider;
    private final RagIngestionService ragIngestionService;
    private final ObjectMapper objectMapper;

    public DocumentIngestHandler(DocumentRepository documentRepository, StorageProvider storageProvider,
            RagIngestionService ragIngestionService, ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.storageProvider = storageProvider;
        this.ragIngestionService = ragIngestionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public TaskType taskType() {
        return TaskType.DOCUMENT_INGEST;
    }

    @Override
    public String handle(AiJob job, ProgressReporter progress) throws Exception {
        JsonNode params = objectMapper.readTree(job.getParamsJson());
        UUID documentId = UUID.fromString(params.get("documentId").asString());

        Document document = documentRepository.findByIdAndOwnerId(documentId, job.getOwnerId())
                .orElseThrow(() -> new IllegalStateException("Document not found for ingest job: " + documentId));

        byte[] content;
        try (InputStream in = storageProvider.retrieve(document.getStorageKey())) {
            content = in.readAllBytes();
        } catch (IOException e) {
            // Storage-layer failure, not an ingestion/parsing one — terminal either way (a
            // retry would hit the same missing/unreadable file), so finalize the document as
            // failed here rather than leaving it stuck at its pre-ingest status forever.
            document.markFailed("STORAGE_READ_ERROR", "Could not read the stored file");
            documentRepository.save(document);
            throw new IllegalStateException("Storage read failed for document: " + documentId, e);
        }

        try {
            RagIngestionService.IngestionResult result = ragIngestionService.ingest(documentId, job.getOwnerId(),
                    content, document.getFileType(), progress, stage -> applyStage(document, stage));
            document.markReady(result.pageCount(), result.charCount(), "en");
            documentRepository.save(document);
            return "{\"documentId\":\"" + documentId + "\"}";
        } catch (TransientJobException e) {
            // Retryable — the dispatcher will requeue the job. Leave the document at whatever
            // in-progress stage applyStage last recorded rather than marking it failed.
            throw e;
        } catch (DocumentParsingException e) {
            document.markFailed(e.failureCode(), e.getMessage());
            documentRepository.save(document);
            throw new IllegalStateException("Ingestion failed: " + e.failureCode(), e);
        } catch (Exception e) {
            // Anything else here is a bug or an unmodeled failure, not something a retry would
            // fix — finalize the document as failed instead of leaving it stuck in-progress
            // forever while the job itself goes to FAILED.
            document.markFailed("INGESTION_ERROR", "Unexpected ingestion failure");
            documentRepository.save(document);
            throw e;
        }
    }

    private void applyStage(Document document, RagIngestionService.Stage stage) {
        switch (stage) {
            case PARSING -> document.markParsing();
            case CHUNKING -> document.markChunking();
            case EMBEDDING -> document.markEmbedding();
        }
        documentRepository.save(document);
    }
}
