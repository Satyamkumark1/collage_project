package com.studyflow.study.repo;

import com.studyflow.study.domain.QuizAnswer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizAnswerRepository extends JpaRepository<QuizAnswer, UUID> {

    Optional<QuizAnswer> findByIdAndOwnerId(UUID id, UUID ownerId);

    Optional<QuizAnswer> findByAttemptIdAndQuestionIdAndOwnerId(UUID attemptId, UUID questionId, UUID ownerId);

    List<QuizAnswer> findByAttemptIdAndOwnerId(UUID attemptId, UUID ownerId);
}
