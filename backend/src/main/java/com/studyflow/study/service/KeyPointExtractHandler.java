package com.studyflow.study.service;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.TaskType;
import com.studyflow.jobs.service.JobHandler;
import com.studyflow.jobs.service.ProgressReporter;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class KeyPointExtractHandler implements JobHandler {

    private final KeyPointExtractionService keyPointExtractionService;
    private final ObjectMapper objectMapper;

    public KeyPointExtractHandler(KeyPointExtractionService keyPointExtractionService, ObjectMapper objectMapper) {
        this.keyPointExtractionService = keyPointExtractionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public TaskType taskType() {
        return TaskType.KEY_POINTS_EXTRACT;
    }

    @Override
    public String handle(AiJob job, ProgressReporter progress) throws Exception {
        JsonNode params = objectMapper.readTree(job.getParamsJson());
        JsonNode documentIdNode = params.path("documentId");
        if (!documentIdNode.isString()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Job params missing a textual documentId");
        }

        UUID documentId = UUID.fromString(documentIdNode.asString());
        progress.report(10, "EXTRACTING");
        keyPointExtractionService.extract(documentId, job.getOwnerId(), job.getId());
        progress.report(100, "DONE");

        return objectMapper.writeValueAsString(Map.of("documentId", documentId.toString()));
    }
}
