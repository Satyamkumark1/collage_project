package com.studyflow.planner.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure function, no DB/HTTP dependency — same posture as {@code Sm2Calculator}/{@code
 * QuizScorer}. Fixed days-before-exam cadence (denser near the exam, standard spaced-repetition
 * backload), a fresh design call — see docs/DECISIONS.md.
 */
public final class StudySessionScheduler {

    private static final int[] DAYS_BEFORE_EXAM = {21, 14, 10, 7, 5, 3, 2, 1, 0};

    private StudySessionScheduler() {
    }

    /** Empty if {@code examDate} is before {@code today}. Always ascending order. */
    public static List<LocalDate> scheduleDates(LocalDate today, LocalDate examDate) {
        long daysUntilExam = ChronoUnit.DAYS.between(today, examDate);
        if (daysUntilExam < 0) {
            return List.of();
        }
        List<LocalDate> dates = new ArrayList<>();
        for (int offset : DAYS_BEFORE_EXAM) {
            if (offset <= daysUntilExam) {
                dates.add(examDate.minusDays(offset));
            }
        }
        return dates;
    }
}
