package com.studyflow.study.domain;

/**
 * An attempt starts {@code IN_PROGRESS} and ends at exactly one of two terminal states: a
 * student-initiated {@code SUBMITTED}, or a server-detected {@code EXPIRED} (EXAM mode past its
 * deadline with no submit call — see docs/DECISIONS.md).
 */
public enum QuizAttemptStatus {
    IN_PROGRESS,
    SUBMITTED,
    EXPIRED
}
