package com.studyflow.study.domain;

/**
 * Three concretely different modes (see docs/DECISIONS.md — invented fresh, the master spec's
 * quiz detail was never transcribed into this repo, see specs/15-PENDING.md):
 * <ul>
 * <li>{@code EXAM} — hard server-authoritative deadline, negative marking, answer key withheld
 * until submit.</li>
 * <li>{@code PRACTICE} — same timer shown but not enforced, no negative marking, answer key
 * withheld until submit.</li>
 * <li>{@code REVISION} — untimed, no negative marking, immediate per-answer feedback.</li>
 * </ul>
 */
public enum QuizMode {
    PRACTICE,
    EXAM,
    REVISION
}
