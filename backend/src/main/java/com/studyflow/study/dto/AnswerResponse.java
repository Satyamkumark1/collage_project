package com.studyflow.study.dto;

import com.studyflow.study.domain.QuizAnswer;
import com.studyflow.study.service.QuizAttemptService.AnswerOutcome;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code isCorrect}/{@code explanation} are only populated in REVISION mode (immediate formative
 * feedback) — {@code null} in EXAM/PRACTICE, where the answer key is withheld until submit. See
 * docs/DECISIONS.md.
 */
public record AnswerResponse(UUID questionId, Short selectedIndex, Instant answeredAt, Boolean isCorrect,
        String explanation) {

    public static AnswerResponse from(AnswerOutcome outcome) {
        return new AnswerResponse(outcome.answer().getQuestionId(), outcome.answer().getSelectedIndex(),
                outcome.answer().getAnsweredAt(), outcome.isCorrect(), outcome.explanation());
    }

    /**
     * For resuming an in-progress attempt — the student's own previously saved picks. No
     * correctness/explanation here even in REVISION mode; that's only computed at save time.
     */
    public static AnswerResponse from(QuizAnswer answer) {
        return new AnswerResponse(answer.getQuestionId(), answer.getSelectedIndex(), answer.getAnsweredAt(), null,
                null);
    }
}
