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
import com.studyflow.study.domain.QuestionSet;
import com.studyflow.study.dto.QuestionResponse;
import com.studyflow.study.dto.QuestionSetJobResponse;
import com.studyflow.study.dto.QuestionSetRequest;
import com.studyflow.study.dto.QuestionSetResponse;
import com.studyflow.study.repo.QuestionRepository;
import com.studyflow.study.repo.QuestionSetRepository;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1")
public class QuestionSetController {

    private static final Set<Integer> ALLOWED_COUNTS = Set.of(10, 25, 50);

    private final DocumentRepository documentRepository;
    private final QuestionSetRepository questionSetRepository;
    private final QuestionRepository questionRepository;
    private final IdentityService identityService;
    private final JobEnqueueService jobEnqueueService;
    private final DpdpGuard dpdpGuard;
    private final ObjectMapper objectMapper;

    public QuestionSetController(DocumentRepository documentRepository, QuestionSetRepository questionSetRepository,
            QuestionRepository questionRepository, IdentityService identityService,
            JobEnqueueService jobEnqueueService, DpdpGuard dpdpGuard, ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.questionSetRepository = questionSetRepository;
        this.questionRepository = questionRepository;
        this.identityService = identityService;
        this.jobEnqueueService = jobEnqueueService;
        this.dpdpGuard = dpdpGuard;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/documents/{documentId}/question-sets")
    public ResponseEntity<QuestionSetJobResponse> request(@PathVariable UUID documentId,
            @Valid @RequestBody QuestionSetRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        dpdpGuard.requireConsentIfMinor(currentUser(ownerId));

        if (!ALLOWED_COUNTS.contains(request.requestedCount())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "requestedCount must be one of 10, 25, 50");
        }
        requireReadyDocument(documentId, ownerId);

        String paramsJson = "{\"documentId\":\"" + documentId + "\",\"requestedCount\":" + request.requestedCount()
                + "}";
        String fingerprint = JobEnqueueService.fingerprint(documentId.toString(), TaskType.MCQ_GENERATE.name(),
                request.requestedCount().toString());
        AiJob job = jobEnqueueService.enqueue(ownerId, TaskType.MCQ_GENERATE, paramsJson, idempotencyKey,
                fingerprint);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new QuestionSetJobResponse(job.getId()));
    }

    @GetMapping("/documents/{documentId}/question-sets")
    public List<QuestionSetResponse> listForDocument(@PathVariable UUID documentId, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        return questionSetRepository.findByDocumentIdAndOwnerIdOrderByCreatedAtDesc(documentId, ownerId).stream()
                .map(qs -> QuestionSetResponse.from(qs, objectMapper))
                .toList();
    }

    @GetMapping("/question-sets/{id}")
    public QuestionSetResponse get(@PathVariable UUID id, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        QuestionSet questionSet = questionSetRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ApiException(ErrorCode.QUESTION_SET_NOT_FOUND, "No question set with that id"));
        return QuestionSetResponse.from(questionSet, objectMapper);
    }

    @GetMapping("/question-sets/{id}/questions")
    public List<QuestionResponse> listQuestions(@PathVariable UUID id, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        questionSetRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ApiException(ErrorCode.QUESTION_SET_NOT_FOUND, "No question set with that id"));
        return questionRepository.findByQuestionSetIdAndOwnerIdOrderBySortOrderAsc(id, ownerId).stream()
                .map(q -> QuestionResponse.from(q, objectMapper))
                .toList();
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
