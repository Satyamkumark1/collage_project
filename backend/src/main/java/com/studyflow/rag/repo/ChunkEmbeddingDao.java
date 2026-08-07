package com.studyflow.rag.repo;

import com.pgvector.PGvector;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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

    public int countByDocumentId(UUID documentId) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM chunk_embeddings WHERE document_id = ?",
                Integer.class, documentId);
        return count == null ? 0 : count;
    }
}
