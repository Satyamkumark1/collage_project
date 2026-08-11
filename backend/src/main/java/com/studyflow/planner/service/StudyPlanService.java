package com.studyflow.planner.service;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.planner.domain.StudyPlan;
import com.studyflow.planner.domain.StudySession;
import com.studyflow.planner.repo.StudyPlanRepository;
import com.studyflow.planner.repo.StudySessionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Synchronous, not a job — no LLM call (see docs/DECISIONS.md for why this deviates from
 * specs/01-architecture.md's async classification). Session dates come from the pure
 * {@link StudySessionScheduler}.
 */
@Service
public class StudyPlanService {

    private static final DateTimeFormatter ICS_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter ICS_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final StudyPlanRepository studyPlanRepository;
    private final StudySessionRepository studySessionRepository;

    public StudyPlanService(StudyPlanRepository studyPlanRepository, StudySessionRepository studySessionRepository) {
        this.studyPlanRepository = studyPlanRepository;
        this.studySessionRepository = studySessionRepository;
    }

    @Transactional
    public StudyPlan build(UUID documentId, UUID ownerId, LocalDate examDate) {
        StudyPlan plan = studyPlanRepository.save(new StudyPlan(documentId, ownerId, examDate));
        List<StudySession> sessions = StudySessionScheduler.scheduleDates(LocalDate.now(), examDate).stream()
                .map(date -> new StudySession(plan.getId(), documentId, ownerId, date))
                .toList();
        studySessionRepository.saveAll(sessions);
        return plan;
    }

    public List<StudySession> sessionsFor(UUID planId, UUID ownerId) {
        return studySessionRepository.findByPlanIdAndOwnerIdOrderByScheduledDateAsc(planId, ownerId);
    }

    public List<StudyPlan> plansForDocument(UUID documentId, UUID ownerId) {
        return studyPlanRepository.findByDocumentIdAndOwnerIdOrderByCreatedAtDesc(documentId, ownerId);
    }

    public StudyPlan requirePlan(UUID id, UUID ownerId) {
        return studyPlanRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ApiException(ErrorCode.STUDY_PLAN_NOT_FOUND, "No study plan with that id"));
    }

    /** Hand-written RFC 5545 — one VEVENT per session is too simple to justify a library dependency. */
    public String renderIcs(StudyPlan plan, String documentTitle) {
        List<StudySession> sessions = sessionsFor(plan.getId(), plan.getOwnerId());
        String stamp = ICS_TIMESTAMP.format(Instant.now().atZone(ZoneOffset.UTC));

        StringBuilder ics = new StringBuilder();
        ics.append("BEGIN:VCALENDAR\r\n");
        ics.append("VERSION:2.0\r\n");
        ics.append("PRODID:-//StudyFlow AI//Study Planner//EN\r\n");
        for (StudySession session : sessions) {
            ics.append("BEGIN:VEVENT\r\n");
            ics.append("UID:").append(session.getId()).append("@studyflow.ai\r\n");
            ics.append("DTSTAMP:").append(stamp).append("\r\n");
            ics.append("DTSTART;VALUE=DATE:").append(ICS_DATE.format(session.getScheduledDate())).append("\r\n");
            ics.append("SUMMARY:Study session — ").append(escapeIcsText(documentTitle)).append("\r\n");
            ics.append("END:VEVENT\r\n");
        }
        ics.append("END:VCALENDAR\r\n");
        return ics.toString();
    }

    private String escapeIcsText(String text) {
        return text.replace("\\", "\\\\")
                .replace("\r\n", "\\n").replace("\r", "\\n").replace("\n", "\\n")
                .replace(",", "\\,")
                .replace(";", "\\;");
    }
}
