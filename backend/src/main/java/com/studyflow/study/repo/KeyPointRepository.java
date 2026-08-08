package com.studyflow.study.repo;

import com.studyflow.study.domain.KeyPoint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KeyPointRepository extends JpaRepository<KeyPoint, UUID> {

    Optional<KeyPoint> findByIdAndOwnerId(UUID id, UUID ownerId);

    Optional<KeyPoint> findFirstByDocumentIdAndOwnerIdOrderByCreatedAtDesc(UUID documentId, UUID ownerId);

    List<KeyPoint> findByDocumentIdAndOwnerIdAndJobIdOrderBySortOrderAsc(UUID documentId, UUID ownerId, UUID jobId);
}
