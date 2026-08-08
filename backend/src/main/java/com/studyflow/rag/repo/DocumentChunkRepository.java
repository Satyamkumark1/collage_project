package com.studyflow.rag.repo;

import com.studyflow.rag.domain.DocumentChunk;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    Optional<DocumentChunk> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<DocumentChunk> findByDocumentIdAndOwnerIdOrderByChunkIndexAsc(UUID documentId, UUID ownerId);

    /** Owner-scoped batch lookup for the fixed candidate set retrieval already selected. */
    List<DocumentChunk> findByIdInAndOwnerId(Collection<UUID> ids, UUID ownerId);

    List<DocumentChunk> findByDocumentIdAndOwnerIdAndChunkIndexIn(UUID documentId, UUID ownerId,
            Collection<Integer> chunkIndexes);

    /**
     * Lexical arm of hybrid retrieval (see specs/09-rag.md, docs/DECISIONS.md). {@code content_tsv}
     * is a generated column (V12 migration) with no JPA mapping, same reasoning as
     * {@code chunk_embeddings.embedding} — queried natively rather than mapped onto the entity.
     * Returns ids in rank order (best match first), not the rows themselves, since RRF fusion
     * only needs rank position.
     */
    @Query(value = "SELECT id FROM document_chunks WHERE document_id = :documentId AND owner_id = :ownerId "
            + "AND content_tsv @@ plainto_tsquery('english', :query) "
            + "ORDER BY ts_rank_cd(content_tsv, plainto_tsquery('english', :query)) DESC LIMIT :limit",
            nativeQuery = true)
    List<UUID> searchByLexicalRank(@Param("documentId") UUID documentId, @Param("ownerId") UUID ownerId,
            @Param("query") String query, @Param("limit") int limit);
}
