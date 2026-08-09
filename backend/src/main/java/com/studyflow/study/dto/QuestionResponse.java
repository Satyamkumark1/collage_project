package com.studyflow.study.dto;

import com.studyflow.study.domain.Question;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Includes {@code correctIndex}/{@code explanation} directly — this phase's MCQs are self-study
 * review, not a proctored/scored quiz, so the answer isn't gated server-side (see
 * docs/DECISIONS.md). The frontend hides it behind a client-side "reveal" interaction.
 */
public record QuestionResponse(
        UUID id,
        UUID questionSetId,
        String stem,
        JsonNode options,
        int correctIndex,
        String explanation,
        String difficulty,
        String bloomLevel,
        JsonNode citations) {

    public static QuestionResponse from(Question question, ObjectMapper objectMapper) {
        JsonNode options = objectMapper.readTree(question.getOptionsJson());
        JsonNode citations = objectMapper.readTree(question.getCitationsJson());
        return new QuestionResponse(question.getId(), question.getQuestionSetId(), question.getStem(), options,
                question.getCorrectIndex(), question.getExplanation(), question.getDifficulty().name(),
                question.getBloomLevel().name(), citations);
    }
}
