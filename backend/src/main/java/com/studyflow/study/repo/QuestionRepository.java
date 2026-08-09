package com.studyflow.study.repo;

import com.studyflow.study.domain.Question;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionRepository extends JpaRepository<Question, UUID> {

    Optional<Question> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<Question> findByQuestionSetIdAndOwnerIdOrderBySortOrderAsc(UUID questionSetId, UUID ownerId);
}
