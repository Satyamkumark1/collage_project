package com.studyflow.study.service;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.TaskType;
import com.studyflow.jobs.service.JobHandler;
import com.studyflow.jobs.service.ProgressReporter;
import com.studyflow.study.domain.Quiz;
import com.studyflow.study.domain.QuizMode;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class QuizBuildHandler implements JobHandler {

    private final QuizGenerationService quizGenerationService;
    private final ObjectMapper objectMapper;

    public QuizBuildHandler(QuizGenerationService quizGenerationService, ObjectMapper objectMapper) {
        this.quizGenerationService = quizGenerationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public TaskType taskType() {
        return TaskType.QUIZ_BUILD;
    }

    @Override
    public String handle(AiJob job, ProgressReporter progress) throws Exception {
        JsonNode params = objectMapper.readTree(job.getParamsJson());
        JsonNode documentIdNode = params.path("documentId");
        JsonNode modeNode = params.path("mode");
        JsonNode requestedCountNode = params.path("requestedCount");
        if (!documentIdNode.isString() || !modeNode.isString() || !requestedCountNode.isInt()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Job params missing a textual documentId/mode or integer requestedCount");
        }

        UUID documentId = UUID.fromString(documentIdNode.asString());
        QuizMode mode = QuizMode.valueOf(modeNode.asString());
        int requestedCount = requestedCountNode.asInt();

        progress.report(10, "GENERATING");
        Quiz quiz = quizGenerationService.build(documentId, job.getOwnerId(), job.getId(), mode, requestedCount);
        progress.report(100, "DONE");

        return objectMapper.writeValueAsString(
                Map.of("quizId", quiz.getId().toString(), "questionCount", quiz.getQuestionCount()));
    }
}
