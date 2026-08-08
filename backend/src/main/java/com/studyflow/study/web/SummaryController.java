package com.studyflow.study.web;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.identity.domain.User;
import com.studyflow.identity.repo.UserRepository;
import com.studyflow.identity.service.DpdpGuard;
import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.TaskType;
import com.studyflow.jobs.service.JobEnqueueService;
import com.studyflow.library.domain.Document;
import com.studyflow.library.domain.DocumentStatus;
import com.studyflow.library.repo.DocumentRepository;
import com.studyflow.study.domain.Summary;
import com.studyflow.study.dto.SummaryJobResponse;
import com.studyflow.study.dto.SummaryResponse;
import com.studyflow.study.repo.SummaryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1")
public class SummaryController {

    private final DocumentRepository documentRepository;
    private final SummaryRepository summaryRepository;
    private final UserRepository userRepository;
    private final JobEnqueueService jobEnqueueService;
    private final DpdpGuard dpdpGuard;
    private final ObjectMapper objectMapper;

    public SummaryController(DocumentRepository documentRepository, SummaryRepository summaryRepository,
            UserRepository userRepository, JobEnqueueService jobEnqueueService, DpdpGuard dpdpGuard,
            ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.summaryRepository = summaryRepository;
        this.userRepository = userRepository;
        this.jobEnqueueService = jobEnqueueService;
        this.dpdpGuard = dpdpGuard;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/documents/{documentId}/summaries")
    public ResponseEntity<SummaryJobResponse> requestSummary(@PathVariable UUID documentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        dpdpGuard.requireConsentIfMinor(currentUser(ownerId));

        Document document = documentRepository.findByIdAndOwnerId(documentId, ownerId)
                .filter(doc -> doc.getDeletedAt() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_NOT_FOUND, "No document with that id"));
        if (document.getStatus() != DocumentStatus.READY) {
            throw new ApiException(ErrorCode.DOCUMENT_NOT_READY, "Document ingestion has not finished yet");
        }

        String paramsJson = "{\"documentId\":\"" + documentId + "\"}";
        String fingerprint = JobEnqueueService.fingerprint(documentId.toString(), TaskType.SUMMARY_GENERATE.name(),
                "1");
        AiJob job = jobEnqueueService.enqueue(ownerId, TaskType.SUMMARY_GENERATE, paramsJson, idempotencyKey,
                fingerprint);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new SummaryJobResponse(job.getId()));
    }

    @GetMapping("/documents/{documentId}/summaries")
    public List<SummaryResponse> listForDocument(@PathVariable UUID documentId, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        return summaryRepository.findByDocumentIdAndOwnerIdOrderByCreatedAtDesc(documentId, ownerId).stream()
                .map(s -> SummaryResponse.from(s, objectMapper))
                .toList();
    }

    @GetMapping("/summaries/{id}")
    public SummaryResponse get(@PathVariable UUID id, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        Summary summary = summaryRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ApiException(ErrorCode.SUMMARY_NOT_FOUND, "No summary with that id"));
        return SummaryResponse.from(summary, objectMapper);
    }

    private User currentUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_TOKEN_EXPIRED, "User no longer exists"));
    }
}
