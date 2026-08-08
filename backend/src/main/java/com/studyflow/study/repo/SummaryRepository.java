package com.studyflow.study.repo;

import com.studyflow.study.domain.Summary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SummaryRepository extends JpaRepository<Summary, UUID> {

    Optional<Summary> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<Summary> findByDocumentIdAndOwnerIdOrderByCreatedAtDesc(UUID documentId, UUID ownerId);
}
