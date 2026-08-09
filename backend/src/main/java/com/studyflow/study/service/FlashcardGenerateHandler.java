package com.studyflow.study.service;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.TaskType;
import com.studyflow.jobs.service.JobHandler;
import com.studyflow.jobs.service.ProgressReporter;
import com.studyflow.study.domain.Flashcard;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class FlashcardGenerateHandler implements JobHandler {

    private final FlashcardGenerationService flashcardGenerationService;
    private final ObjectMapper objectMapper;

    public FlashcardGenerateHandler(FlashcardGenerationService flashcardGenerationService,
            ObjectMapper objectMapper) {
        this.flashcardGenerationService = flashcardGenerationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public TaskType taskType() {
        return TaskType.FLASHCARD_GENERATE;
    }

    @Override
    public String handle(AiJob job, ProgressReporter progress) throws Exception {
        JsonNode params = objectMapper.readTree(job.getParamsJson());
        JsonNode documentIdNode = params.path("documentId");
        if (!documentIdNode.isString()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Job params missing a textual documentId");
        }
        UUID documentId = UUID.fromString(documentIdNode.asString());

        progress.report(10, "GENERATING");
        List<Flashcard> flashcards = flashcardGenerationService.generate(documentId, job.getOwnerId(), job.getId());
        progress.report(100, "DONE");

        return objectMapper.writeValueAsString(Map.of("documentId", documentId.toString(), "generatedCount",
                flashcards.size()));
    }
}
