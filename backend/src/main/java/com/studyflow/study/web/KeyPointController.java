package com.studyflow.study.web;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.identity.domain.User;
import com.studyflow.identity.service.DpdpGuard;
import com.studyflow.identity.service.IdentityService;
import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.TaskType;
import com.studyflow.jobs.service.JobEnqueueService;
import com.studyflow.library.domain.Document;
import com.studyflow.library.domain.DocumentStatus;
import com.studyflow.library.repo.DocumentRepository;
import com.studyflow.study.domain.KeyPoint;
import com.studyflow.study.dto.KeyPointJobResponse;
import com.studyflow.study.dto.KeyPointResponse;
import com.studyflow.study.repo.KeyPointRepository;
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
public class KeyPointController {

    private final DocumentRepository documentRepository;
    private final KeyPointRepository keyPointRepository;
    private final IdentityService identityService;
    private final JobEnqueueService jobEnqueueService;
    private final DpdpGuard dpdpGuard;
    private final ObjectMapper objectMapper;

    public KeyPointController(DocumentRepository documentRepository, KeyPointRepository keyPointRepository,
            IdentityService identityService, JobEnqueueService jobEnqueueService, DpdpGuard dpdpGuard,
            ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.keyPointRepository = keyPointRepository;
        this.identityService = identityService;
        this.jobEnqueueService = jobEnqueueService;
        this.dpdpGuard = dpdpGuard;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/documents/{documentId}/key-points")
    public ResponseEntity<KeyPointJobResponse> request(@PathVariable UUID documentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        dpdpGuard.requireConsentIfMinor(currentUser(ownerId));

        requireReadyDocument(documentId, ownerId);

        String paramsJson = "{\"documentId\":\"" + documentId + "\"}";
        String fingerprint = JobEnqueueService.fingerprint(documentId.toString(), TaskType.KEY_POINTS_EXTRACT.name(),
                "1");
        AiJob job = jobEnqueueService.enqueue(ownerId, TaskType.KEY_POINTS_EXTRACT, paramsJson, idempotencyKey,
                fingerprint);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new KeyPointJobResponse(job.getId()));
    }

    @GetMapping("/documents/{documentId}/key-points")
    public List<KeyPointResponse> list(@PathVariable UUID documentId, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        requireDocument(documentId, ownerId);

        var latestResult = keyPointRepository.findFirstByDocumentIdAndOwnerIdOrderByCreatedAtDesc(documentId, ownerId);
        if (latestResult.isEmpty()) {
            return List.of();
        }
        KeyPoint latest = latestResult.get();
        return keyPointRepository.findByDocumentIdAndOwnerIdAndJobIdOrderBySortOrderAsc(documentId, ownerId,
                latest.getJobId()).stream().map(keyPoint -> KeyPointResponse.from(keyPoint, objectMapper)).toList();
    }

    private Document requireReadyDocument(UUID documentId, UUID ownerId) {
        Document document = requireDocument(documentId, ownerId);
        if (document.getStatus() != DocumentStatus.READY) {
            throw new ApiException(ErrorCode.DOCUMENT_NOT_READY, "Document ingestion has not finished yet");
        }
        return document;
    }

    private Document requireDocument(UUID documentId, UUID ownerId) {
        return documentRepository.findByIdAndOwnerId(documentId, ownerId)
                .filter(doc -> doc.getDeletedAt() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_NOT_FOUND, "No document with that id"));
    }

    private User currentUser(UUID userId) {
        return identityService.requireActiveUser(userId);
    }
}
