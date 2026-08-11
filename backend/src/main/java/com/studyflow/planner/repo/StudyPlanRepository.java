package com.studyflow.planner.repo;

import com.studyflow.planner.domain.StudyPlan;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudyPlanRepository extends JpaRepository<StudyPlan, UUID> {

    Optional<StudyPlan> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<StudyPlan> findByDocumentIdAndOwnerIdOrderByCreatedAtDesc(UUID documentId, UUID ownerId);
}
