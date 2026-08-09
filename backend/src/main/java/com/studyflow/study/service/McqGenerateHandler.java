package com.studyflow.study.service;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.TaskType;
import com.studyflow.jobs.service.JobHandler;
import com.studyflow.jobs.service.ProgressReporter;
import com.studyflow.study.domain.QuestionSet;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class McqGenerateHandler implements JobHandler {

    private final McqGenerationService mcqGenerationService;
    private final ObjectMapper objectMapper;

    public McqGenerateHandler(McqGenerationService mcqGenerationService, ObjectMapper objectMapper) {
        this.mcqGenerationService = mcqGenerationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public TaskType taskType() {
        return TaskType.MCQ_GENERATE;
    }

    @Override
    public String handle(AiJob job, ProgressReporter progress) throws Exception {
        JsonNode params = objectMapper.readTree(job.getParamsJson());
        JsonNode documentIdNode = params.path("documentId");
        JsonNode requestedCountNode = params.path("requestedCount");
        if (!documentIdNode.isString() || !requestedCountNode.isInt()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Job params missing a textual documentId or integer requestedCount");
        }

        UUID documentId = UUID.fromString(documentIdNode.asString());
        int requestedCount = requestedCountNode.asInt();

        progress.report(10, "GENERATING");
        QuestionSet questionSet = mcqGenerationService.generate(documentId, job.getOwnerId(), job.getId(),
                requestedCount);
        progress.report(100, "DONE");

        return objectMapper.writeValueAsString(Map.of("questionSetId", questionSet.getId().toString(),
                "generatedCount", questionSet.getGeneratedCount()));
    }
}
