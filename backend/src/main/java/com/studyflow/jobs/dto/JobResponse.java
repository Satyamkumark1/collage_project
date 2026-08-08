package com.studyflow.jobs.dto;

import com.studyflow.jobs.domain.AiJob;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// errorMessage is intentionally not exposed here — it's the raw diagnostic string a handler
// threw (see JobDispatcher), developer-facing only. errorCode is the stable field the frontend
// switches on to pick its own copy — same rule as ProblemDetail's `detail` vs `code` (see
// GlobalExceptionHandler).
public record JobResponse(
        UUID id,
        String taskType,
        String status,
        short progressPct,
        String progressStage,
        JsonNode resultRef,
        String errorCode,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    public static JobResponse from(AiJob job, ObjectMapper objectMapper) {
        JsonNode resultRef = job.getResultRefJson() == null ? null : objectMapper.readTree(job.getResultRefJson());
        return new JobResponse(job.getId(), job.getTaskType().name(), job.getStatus().name(), job.getProgressPct(),
                job.getProgressStage(), resultRef, job.getErrorCode(), job.getCreatedAt(),
                job.getStartedAt(), job.getFinishedAt());
    }
}
