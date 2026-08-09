package com.studyflow.study.repo;

import com.studyflow.study.domain.QuestionSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionSetRepository extends JpaRepository<QuestionSet, UUID> {

    Optional<QuestionSet> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<QuestionSet> findByDocumentIdAndOwnerIdOrderByCreatedAtDesc(UUID documentId, UUID ownerId);
}
