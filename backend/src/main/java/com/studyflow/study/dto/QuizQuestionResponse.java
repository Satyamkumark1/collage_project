package com.studyflow.study.dto;

import com.studyflow.study.domain.Question;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Deliberately excludes {@code correctIndex}/{@code explanation}/{@code citations} — the answer
 * key is never sent to the client while an attempt is in progress (see docs/DECISIONS.md and
 * specs/10-study-features.md's own quiz bullet). Contrast with {@link QuestionResponse}, which
 * MCQ self-study review sends in full.
 */
public record QuizQuestionResponse(UUID id, UUID quizId, String stem, JsonNode options) {

    public static QuizQuestionResponse from(Question question, UUID quizId, ObjectMapper objectMapper) {
        JsonNode options = objectMapper.readTree(question.getOptionsJson());
        return new QuizQuestionResponse(question.getId(), quizId, question.getStem(), options);
    }
}
