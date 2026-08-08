package com.studyflow.rag.service;

import com.studyflow.rag.domain.DocumentChunk;
import com.studyflow.rag.repo.ChunkEmbeddingDao;
import com.studyflow.rag.repo.ChunkEmbeddingDao.VectorHit;
import com.studyflow.rag.repo.DocumentChunkRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vector search + Postgres full-text, fused with Reciprocal Rank Fusion, then neighbour-chunk
 * expansion (see specs/09-rag.md §Retrieval). Every parameter here (top-k sizes, RRF's k=60,
 * fused-set size, expansion cap) is a designed choice, not a spec-mandated one — full rationale
 * in docs/DECISIONS.md, since the original master spec's retrieval section was never provided.
 */
@Service
public class RetrievalServiceImpl implements RetrievalService {

    private static final int VECTOR_TOP_K = 20;
    private static final int LEXICAL_TOP_K = 20;
    private static final int RRF_K = 60;
    private static final int FUSED_TOP_K = 8;
    private static final int MAX_TOTAL_CHUNKS = 16;

    private final EmbeddingClient embeddingClient;
    private final ChunkEmbeddingDao chunkEmbeddingDao;
    private final DocumentChunkRepository chunkRepository;

    public RetrievalServiceImpl(EmbeddingClient embeddingClient, ChunkEmbeddingDao chunkEmbeddingDao,
            DocumentChunkRepository chunkRepository) {
        this.embeddingClient = embeddingClient;
        this.chunkEmbeddingDao = chunkEmbeddingDao;
        this.chunkRepository = chunkRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public RetrievalResult retrieve(UUID documentId, UUID ownerId, String query) {
        float[] queryEmbedding = embeddingClient.embed(List.of(query)).get(0);

        List<VectorHit> vectorHits = chunkEmbeddingDao.searchByCosineSimilarity(documentId, ownerId, queryEmbedding,
                VECTOR_TOP_K);
        List<UUID> lexicalHits = chunkRepository.searchByLexicalRank(documentId, ownerId, query, LEXICAL_TOP_K);
        double bestVectorSimilarity = vectorHits.isEmpty() ? 0.0 : vectorHits.get(0).similarity();

        List<UUID> fusedIds = fuse(vectorHits.stream().map(VectorHit::chunkId).toList(), lexicalHits, FUSED_TOP_K);
        if (fusedIds.isEmpty()) {
            return new RetrievalResult(List.of(), bestVectorSimilarity);
        }

        Map<UUID, DocumentChunk> primaryById = chunkRepository.findByIdInAndOwnerId(fusedIds, ownerId).stream()
                .collect(Collectors.toMap(DocumentChunk::getId, c -> c));
        Map<UUID, DocumentChunk> selected = new LinkedHashMap<>();
        for (UUID id : fusedIds) {
            DocumentChunk chunk = primaryById.get(id);
            if (chunk != null) {
                selected.put(id, chunk);
            }
        }

        Set<Integer> neighbourIndexes = new LinkedHashSet<>();
        for (DocumentChunk chunk : selected.values()) {
            neighbourIndexes.add(chunk.getChunkIndex() - 1);
            neighbourIndexes.add(chunk.getChunkIndex() + 1);
        }
        if (!neighbourIndexes.isEmpty()) {
            for (DocumentChunk neighbour : chunkRepository.findByDocumentIdAndOwnerIdAndChunkIndexIn(documentId,
                    ownerId, neighbourIndexes)) {
                if (selected.size() >= MAX_TOTAL_CHUNKS) {
                    break;
                }
                selected.putIfAbsent(neighbour.getId(), neighbour);
            }
        }

        List<RetrievedChunk> chunks = selected.values().stream()
                .sorted(Comparator.comparingInt(DocumentChunk::getChunkIndex))
                .map(c -> new RetrievedChunk(c.getId(), c.getContent(), c.getPageFrom(), c.getPageTo(),
                        c.getSectionPath()))
                .toList();
        return new RetrievalResult(chunks, bestVectorSimilarity);
    }

    private List<UUID> fuse(List<UUID> vectorRanked, List<UUID> lexicalRanked, int topK) {
        Map<UUID, Double> scores = new LinkedHashMap<>();
        addRankScores(scores, vectorRanked);
        addRankScores(scores, lexicalRanked);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(topK)
                .map(Map.Entry::getKey)
                .toList();
    }

    private void addRankScores(Map<UUID, Double> scores, List<UUID> ranked) {
        for (int i = 0; i < ranked.size(); i++) {
            int rank = i + 1;
            scores.merge(ranked.get(i), 1.0 / (RRF_K + rank), Double::sum);
        }
    }
}
