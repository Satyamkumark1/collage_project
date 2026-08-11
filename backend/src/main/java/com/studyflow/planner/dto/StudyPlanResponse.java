package com.studyflow.planner.dto;

import com.studyflow.planner.domain.StudyPlan;
import com.studyflow.planner.domain.StudySession;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record StudyPlanResponse(
        UUID id,
        UUID documentId,
        LocalDate examDate,
        Instant createdAt,
        List<SessionView> sessions) {

    public record SessionView(UUID id, LocalDate scheduledDate) {
        static SessionView from(StudySession session) {
            return new SessionView(session.getId(), session.getScheduledDate());
        }
    }

    public static StudyPlanResponse from(StudyPlan plan, List<StudySession> sessions) {
        return new StudyPlanResponse(plan.getId(), plan.getDocumentId(), plan.getExamDate(), plan.getCreatedAt(),
                sessions.stream().map(SessionView::from).toList());
    }
}
