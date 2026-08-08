package com.studyflow.study.service;

import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.TaskType;
import com.studyflow.jobs.service.JobHandler;
import com.studyflow.jobs.service.ProgressReporter;
import com.studyflow.study.domain.Summary;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class SummaryGenerateHandler implements JobHandler {

    private final SummaryGenerationService summaryGenerationService;
    private final ObjectMapper objectMapper;

    public SummaryGenerateHandler(SummaryGenerationService summaryGenerationService, ObjectMapper objectMapper) {
        this.summaryGenerationService = summaryGenerationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public TaskType taskType() {
        return TaskType.SUMMARY_GENERATE;
    }

    @Override
    public String handle(AiJob job, ProgressReporter progress) throws Exception {
        JsonNode params = objectMapper.readTree(job.getParamsJson());
        UUID documentId = UUID.fromString(params.get("documentId").asString());

        progress.report(10, "GENERATING");
        Summary summary = summaryGenerationService.generate(documentId, job.getOwnerId(), job.getId());
        progress.report(100, "DONE");

        return "{\"summaryId\":\"" + summary.getId() + "\"}";
    }
}
