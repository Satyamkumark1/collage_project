package com.studyflow.study.dto;

import com.studyflow.study.domain.Question;
import com.studyflow.study.service.QuizAttemptService.ResultView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Only ever built once an attempt is terminal — carries the full answer key, see docs/DECISIONS.md. */
public record QuizResultResponse(QuizAttemptResponse attempt, List<QuestionBreakdown> questions) {

    public record QuestionBreakdown(
            UUID id,
            String stem,
            JsonNode options,
            int correctIndex,
            String explanation,
            JsonNode citations,
            Short selectedIndex,
            Boolean isCorrect) {
    }

    public static QuizResultResponse from(ResultView view, ObjectMapper objectMapper) {
        Map<UUID, Short> selectedByQuestionId = view.selectedIndexByQuestionId();
        List<QuestionBreakdown> questions = view.questions().stream()
                .map(question -> toBreakdown(question, selectedByQuestionId.get(question.getId()), objectMapper))
                .toList();
        return new QuizResultResponse(QuizAttemptResponse.from(view.attempt()), questions);
    }

    private static QuestionBreakdown toBreakdown(Question question, Short selectedIndex, ObjectMapper objectMapper) {
        JsonNode options = objectMapper.readTree(question.getOptionsJson());
        JsonNode citations = objectMapper.readTree(question.getCitationsJson());
        Boolean isCorrect = selectedIndex == null ? null : selectedIndex == question.getCorrectIndex();
        return new QuestionBreakdown(question.getId(), question.getStem(), options, question.getCorrectIndex(),
                question.getExplanation(), citations, selectedIndex, isCorrect);
    }
}
