package com.studyflow.jobs.web;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.dto.JobResponse;
import com.studyflow.jobs.repo.AiJobRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final AiJobRepository repository;
    private final ObjectMapper objectMapper;

    public JobController(AiJobRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public record JobListResponse(List<JobResponse> items, UUID nextCursor) {
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable UUID id, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        AiJob job = repository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ApiException(ErrorCode.JOB_NOT_FOUND, "No job with that id"));
        return JobResponse.from(job, objectMapper);
    }

    @GetMapping
    public JobListResponse list(Authentication authentication, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) UUID cursor) {
        if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and " + MAX_LIMIT);
        }
        UUID ownerId = UUID.fromString(authentication.getName());
        int effectiveLimit = limit == null ? DEFAULT_LIMIT : limit;
        Limit limitObj = Limit.of(effectiveLimit + 1);
        List<AiJob> page = cursor == null
                ? repository.findByOwnerIdOrderByIdDesc(ownerId, limitObj)
                : repository.findByOwnerIdAndIdLessThanOrderByIdDesc(ownerId, cursor, limitObj);

        boolean hasMore = page.size() > effectiveLimit;
        List<AiJob> pageItems = hasMore ? page.subList(0, effectiveLimit) : page;
        UUID nextCursor = hasMore ? pageItems.get(pageItems.size() - 1).getId() : null;

        List<JobResponse> items = pageItems.stream().map(job -> JobResponse.from(job, objectMapper)).toList();
        return new JobListResponse(items, nextCursor);
    }
}
