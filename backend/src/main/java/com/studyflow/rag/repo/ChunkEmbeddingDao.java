package com.studyflow.rag.repo;

import com.pgvector.PGvector;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plain JdbcTemplate, not a JPA entity — {@code vector} is a pgvector extension type with no
 * native Hibernate mapping. {@link PGvector} extends {@code PGobject} and carries its own
 * Postgres type name, so the driver binds it correctly; a generic {@code SqlTypes.OTHER}
 * Hibernate mapping would have hit the same "bound as bytea" problem documented for citext in
 * docs/DECISIONS.md.
 */
@Repository
public class ChunkEmbeddingDao {

    private final JdbcTemplate jdbcTemplate;

    public ChunkEmbeddingDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(UUID chunkId, UUID documentId, UUID ownerId, float[] embedding, String model,
            String modelVersion) {
        jdbcTemplate.update(
                "INSERT INTO chunk_embeddings (chunk_id, document_id, owner_id, embedding, model, model_version) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                chunkId, documentId, ownerId, new PGvector(embedding), model, modelVersion);
    }

    public int countByDocumentId(UUID documentId, UUID ownerId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chunk_embeddings WHERE document_id = ? AND owner_id = ?", Integer.class,
                documentId, ownerId);
        return count == null ? 0 : count;
    }

    public record VectorHit(UUID chunkId, double similarity) {
    }

    /**
     * Cosine similarity ({@code 1 - cosine distance}), best match first. {@code SET LOCAL} only
     * takes effect for the current transaction (see specs/02-data-model.md's reserved
     * {@code hnsw.ef_search=40} note) — this method must run inside a transaction started by the
     * caller (see {@code RetrievalServiceImpl}), otherwise Postgres silently drops the setting
     * before the SELECT below runs.
     */
    @Transactional(readOnly = true)
    public List<VectorHit> searchByCosineSimilarity(UUID documentId, UUID ownerId, float[] queryEmbedding,
            int limit) {
        jdbcTemplate.execute("SET LOCAL hnsw.ef_search = 40");
        PGvector vector = new PGvector(queryEmbedding);
        return jdbcTemplate.query(
                "SELECT chunk_id, 1 - (embedding <=> ?) AS similarity FROM chunk_embeddings "
                        + "WHERE document_id = ? AND owner_id = ? ORDER BY embedding <=> ? LIMIT ?",
                (rs, rowNum) -> new VectorHit(UUID.fromString(rs.getString("chunk_id")), rs.getDouble("similarity")),
                vector, documentId, ownerId, vector, limit);
    }
}
