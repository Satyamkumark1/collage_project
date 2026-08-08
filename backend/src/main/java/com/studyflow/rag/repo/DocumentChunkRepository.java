package com.studyflow.rag.repo;

import com.studyflow.rag.domain.DocumentChunk;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    Optional<DocumentChunk> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<DocumentChunk> findByDocumentIdAndOwnerIdOrderByChunkIndexAsc(UUID documentId, UUID ownerId);
}
