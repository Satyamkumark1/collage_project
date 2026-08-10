package com.studyflow.study.repo;

import com.studyflow.study.domain.Quiz;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizRepository extends JpaRepository<Quiz, UUID> {

    Optional<Quiz> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<Quiz> findByDocumentIdAndOwnerIdOrderByCreatedAtDesc(UUID documentId, UUID ownerId);
}
