package com.studyflow.planner.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class StudySessionSchedulerTest {

    @Test
    void examInThePastYieldsNoSessions() {
        assertThat(StudySessionScheduler.scheduleDates(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 1)))
                .isEmpty();
    }

    @Test
    void examTodayYieldsExactlyOneSessionToday() {
        LocalDate today = LocalDate.of(2026, 1, 1);
        assertThat(StudySessionScheduler.scheduleDates(today, today)).containsExactly(today);
    }

    @Test
    void oneDayOutYieldsTwoSessions() {
        LocalDate today = LocalDate.of(2026, 1, 1);
        LocalDate exam = today.plusDays(1);
        assertThat(StudySessionScheduler.scheduleDates(today, exam)).containsExactly(today, exam);
    }

    @Test
    void farOutExamUsesFullCadenceDescendingToZero() {
        LocalDate today = LocalDate.of(2026, 1, 1);
        LocalDate exam = today.plusDays(30);
        List<LocalDate> dates = StudySessionScheduler.scheduleDates(today, exam);
        assertThat(dates).containsExactly(
                exam.minusDays(21), exam.minusDays(14), exam.minusDays(10), exam.minusDays(7),
                exam.minusDays(5), exam.minusDays(3), exam.minusDays(2), exam.minusDays(1), exam);
    }

    @Test
    void tenDaysOutFiltersOffsetsBeyondTheWindow() {
        LocalDate today = LocalDate.of(2026, 1, 1);
        LocalDate exam = today.plusDays(10);
        List<LocalDate> dates = StudySessionScheduler.scheduleDates(today, exam);
        assertThat(dates).containsExactly(
                exam.minusDays(10), exam.minusDays(7), exam.minusDays(5), exam.minusDays(3),
                exam.minusDays(2), exam.minusDays(1), exam);
    }
}
