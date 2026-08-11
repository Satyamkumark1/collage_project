package com.studyflow.rag.service;

import com.studyflow.common.hash.Sha256;
import com.studyflow.jobs.domain.TransientJobException;
import com.studyflow.jobs.service.ProgressReporter;
import com.studyflow.library.domain.DocumentFileType;
import com.studyflow.rag.domain.DocumentChunk;
import com.studyflow.rag.domain.DocumentParsingException;
import com.studyflow.rag.repo.ChunkEmbeddingDao;
import com.studyflow.rag.repo.DocumentChunkRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RagIngestionServiceImpl implements RagIngestionService {

    private final List<DocumentParser> parsers;
    private final TextNormalizer normalizer;
    private final DocumentChunker chunker;
    private final EmbeddingClient embeddingClient;
    private final DocumentChunkRepository chunkRepository;
    private final ChunkEmbeddingDao chunkEmbeddingDao;

    public RagIngestionServiceImpl(List<DocumentParser> parsers, TextNormalizer normalizer, DocumentChunker chunker,
            EmbeddingClient embeddingClient, DocumentChunkRepository chunkRepository,
            ChunkEmbeddingDao chunkEmbeddingDao) {
        this.parsers = parsers;
        this.normalizer = normalizer;
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.chunkRepository = chunkRepository;
        this.chunkEmbeddingDao = chunkEmbeddingDao;
    }

    @Override
    @Transactional
    public IngestionResult ingest(UUID documentId, UUID ownerId, byte[] content, DocumentFileType fileType,
            ProgressReporter progress, Consumer<Stage> stageCallback) {
        DocumentParser parser = parsers.stream().filter(p -> p.supports(fileType)).findFirst()
                .orElseThrow(() -> new DocumentParsingException("FILE_TYPE_UNSUPPORTED",
                        "No parser registered for " + fileType));

        stageCallback.accept(Stage.PARSING);
        progress.report(10, "PARSING");
        ParsedDocument parsed = parser.parse(content);

        progress.report(30, "NORMALIZING");
        ParsedDocument normalized = normalizer.normalize(parsed);

        stageCallback.accept(Stage.CHUNKING);
        progress.report(40, "CHUNKING");
        List<DocumentChunker.ChunkDraft> drafts = chunker.chunk(normalized);
        if (drafts.isEmpty()) {
            throw new DocumentParsingException("FILE_NO_TEXT_LAYER", "No usable content survived chunking");
        }

        List<DocumentChunk> savedChunks = new ArrayList<>(drafts.size());
        int index = 0;
        for (DocumentChunker.ChunkDraft draft : drafts) {
            DocumentChunk chunk = new DocumentChunk(documentId, ownerId, index++, draft.content(),
                    draft.tokenCount(), draft.pageFrom(), draft.pageTo(), draft.sectionPath(),
                    Sha256.hex(draft.content()));
            savedChunks.add(chunkRepository.save(chunk));
        }
        // ChunkEmbeddingDao writes via plain JdbcTemplate, bypassing the Hibernate session, so
        // it only sees rows actually flushed to the DB — Hibernate defers INSERTs until flush
        // time by default, which is late enough to trip the chunk_embeddings FK otherwise.
        chunkRepository.flush();

        stageCallback.accept(Stage.EMBEDDING);
        progress.report(60, "EMBEDDING");
        List<String> texts = savedChunks.stream().map(DocumentChunk::getContent).toList();
        int totalTexts = texts.size();
        List<float[]> embeddings = embeddingClient.embed(texts,
                embedded -> progress.report(60 + (int) (30.0 * embedded / totalTexts), "EMBEDDING"));
        if (embeddings.size() != savedChunks.size()) {
            throw new TransientJobException("Embedding provider returned " + embeddings.size() + " vectors for "
                    + savedChunks.size() + " chunks");
        }
        int expectedDimension = embeddings.isEmpty() ? 0 : embeddings.get(0).length;
        for (float[] embedding : embeddings) {
            if (embedding.length != expectedDimension) {
                throw new TransientJobException("Embedding provider returned inconsistent vector dimensions");
            }
        }
        for (int i = 0; i < savedChunks.size(); i++) {
            DocumentChunk chunk = savedChunks.get(i);
            chunkEmbeddingDao.insert(chunk.getId(), documentId, ownerId, embeddings.get(i), embeddingClient.model(),
                    embeddingClient.modelVersion());
        }

        progress.report(90, "INDEXING");
        return new IngestionResult(normalized.pages().size(), normalized.totalCharCount(), savedChunks.size());
    }
}
