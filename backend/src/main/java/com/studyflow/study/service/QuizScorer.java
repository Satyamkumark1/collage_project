package com.studyflow.study.service;

import com.studyflow.study.domain.Question;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure scoring function, no DB/HTTP dependency — same posture as {@link Sm2Calculator} (plain-
 * JUnit testable, no Spring context needed). {@code score = correctCount - incorrectCount *
 * negativeMarkingFraction}, 1 point per correct answer, 0 for unanswered — a fresh design call
 * (JEE/NEET-style -0.25 negative marking for EXAM mode, 0 for PRACTICE/REVISION), see
 * docs/DECISIONS.md.
 */
public final class QuizScorer {

    private QuizScorer() {
    }

    public record ScoreBreakdown(BigDecimal score, short maxScore, short correctCount, short incorrectCount,
            short unansweredCount) {
    }

    /**
     * @param questions           every question in the quiz, in any order.
     * @param selectedIndexById   question id -> the student's selected option index; a missing
     *                            entry means unanswered.
     */
    public static ScoreBreakdown score(List<Question> questions, Map<UUID, Integer> selectedIndexById,
            BigDecimal negativeMarkingFraction) {
        short correct = 0;
        short incorrect = 0;
        short unanswered = 0;
        for (Question question : questions) {
            Integer selected = selectedIndexById.get(question.getId());
            if (selected == null) {
                unanswered++;
            } else if (selected == question.getCorrectIndex()) {
                correct++;
            } else {
                incorrect++;
            }
        }

        BigDecimal rawScore = BigDecimal.valueOf(correct)
                .subtract(negativeMarkingFraction.multiply(BigDecimal.valueOf(incorrect)));
        return new ScoreBreakdown(rawScore.setScale(2, RoundingMode.HALF_UP), (short) questions.size(), correct,
                incorrect, unanswered);
    }
}
