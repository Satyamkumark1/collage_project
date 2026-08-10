package com.studyflow.study.repo;

import com.studyflow.study.domain.QuizAttempt;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, UUID> {

    Optional<QuizAttempt> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<QuizAttempt> findByQuizIdAndOwnerIdOrderByStartedAtDesc(UUID quizId, UUID ownerId);
}
