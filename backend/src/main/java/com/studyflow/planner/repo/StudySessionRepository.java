package com.studyflow.planner.repo;

import com.studyflow.planner.domain.StudySession;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudySessionRepository extends JpaRepository<StudySession, UUID> {

    Optional<StudySession> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<StudySession> findByPlanIdAndOwnerIdOrderByScheduledDateAsc(UUID planId, UUID ownerId);
}
