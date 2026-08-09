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
import com.studyflow.study.domain.Flashcard;
import com.studyflow.study.dto.FlashcardJobResponse;
import com.studyflow.study.dto.FlashcardResponse;
import com.studyflow.study.dto.FlashcardReviewRequest;
import com.studyflow.study.repo.FlashcardRepository;
import com.studyflow.study.service.FlashcardPersistenceService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1")
public class FlashcardController {

    private static final int DEFAULT_DUE_LIMIT = 20;
    private static final int MAX_DUE_LIMIT = 100;

    private final DocumentRepository documentRepository;
    private final FlashcardRepository flashcardRepository;
    private final FlashcardPersistenceService flashcardPersistenceService;
    private final IdentityService identityService;
    private final JobEnqueueService jobEnqueueService;
    private final DpdpGuard dpdpGuard;
    private final ObjectMapper objectMapper;

    public FlashcardController(DocumentRepository documentRepository, FlashcardRepository flashcardRepository,
            FlashcardPersistenceService flashcardPersistenceService, IdentityService identityService,
            JobEnqueueService jobEnqueueService, DpdpGuard dpdpGuard, ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.flashcardRepository = flashcardRepository;
        this.flashcardPersistenceService = flashcardPersistenceService;
        this.identityService = identityService;
        this.jobEnqueueService = jobEnqueueService;
        this.dpdpGuard = dpdpGuard;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/documents/{documentId}/flashcards")
    public ResponseEntity<FlashcardJobResponse> request(@PathVariable UUID documentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        dpdpGuard.requireConsentIfMinor(currentUser(ownerId));

        requireReadyDocument(documentId, ownerId);

        String paramsJson = "{\"documentId\":\"" + documentId + "\"}";
        String fingerprint = JobEnqueueService.fingerprint(documentId.toString(), TaskType.FLASHCARD_GENERATE.name(),
                "1");
        AiJob job = jobEnqueueService.enqueue(ownerId, TaskType.FLASHCARD_GENERATE, paramsJson, idempotencyKey,
                fingerprint);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new FlashcardJobResponse(job.getId()));
    }

    @GetMapping("/documents/{documentId}/flashcards")
    public List<FlashcardResponse> listForDocument(@PathVariable UUID documentId, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        return flashcardRepository.findByDocumentIdAndOwnerIdOrderByCreatedAtDesc(documentId, ownerId).stream()
                .map(f -> FlashcardResponse.from(f, objectMapper))
                .toList();
    }

    @GetMapping("/flashcards/due")
    public List<FlashcardResponse> due(Authentication authentication, @RequestParam(required = false) Integer limit) {
        if (limit != null && (limit < 1 || limit > MAX_DUE_LIMIT)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and " + MAX_DUE_LIMIT);
        }
        UUID ownerId = UUID.fromString(authentication.getName());
        int effectiveLimit = limit == null ? DEFAULT_DUE_LIMIT : limit;
        return flashcardRepository
                .findByOwnerIdAndDueAtLessThanEqualOrderByDueAtAsc(ownerId, Instant.now(), Limit.of(effectiveLimit))
                .stream()
                .map(f -> FlashcardResponse.from(f, objectMapper))
                .toList();
    }

    @PostMapping("/flashcards/{id}/review")
    public FlashcardResponse review(@PathVariable UUID id, @Valid @RequestBody FlashcardReviewRequest request,
            Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        ZoneId userZone = ZoneId.of(currentUser(ownerId).getTimezone());
        Flashcard flashcard = flashcardPersistenceService.review(id, ownerId, request.quality(), userZone);
        return FlashcardResponse.from(flashcard, objectMapper);
    }

    private Document requireReadyDocument(UUID documentId, UUID ownerId) {
        Document document = documentRepository.findByIdAndOwnerId(documentId, ownerId)
                .filter(doc -> doc.getDeletedAt() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_NOT_FOUND, "No document with that id"));
        if (document.getStatus() != DocumentStatus.READY) {
            throw new ApiException(ErrorCode.DOCUMENT_NOT_READY, "Document ingestion has not finished yet");
        }
        return document;
    }

    private User currentUser(UUID userId) {
        return identityService.requireActiveUser(userId);
    }
}
