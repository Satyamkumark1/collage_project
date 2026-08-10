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
import com.studyflow.study.domain.Quiz;
import com.studyflow.study.dto.QuizJobResponse;
import com.studyflow.study.dto.QuizQuestionResponse;
import com.studyflow.study.dto.QuizRequest;
import com.studyflow.study.dto.QuizResponse;
import com.studyflow.study.repo.QuestionRepository;
import com.studyflow.study.repo.QuizRepository;
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

/** Quiz build (async, reuses MCQ generation) + the answer-key-free question view for taking one. */
@RestController
@RequestMapping("/api/v1")
public class QuizController {

    private static final Set<Integer> ALLOWED_COUNTS = Set.of(10, 25, 50);

    private final DocumentRepository documentRepository;
    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final IdentityService identityService;
    private final JobEnqueueService jobEnqueueService;
    private final DpdpGuard dpdpGuard;
    private final ObjectMapper objectMapper;

    public QuizController(DocumentRepository documentRepository, QuizRepository quizRepository,
            QuestionRepository questionRepository, IdentityService identityService,
            JobEnqueueService jobEnqueueService, DpdpGuard dpdpGuard, ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.identityService = identityService;
        this.jobEnqueueService = jobEnqueueService;
        this.dpdpGuard = dpdpGuard;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/documents/{documentId}/quizzes")
    public ResponseEntity<QuizJobResponse> request(@PathVariable UUID documentId,
            @Valid @RequestBody QuizRequest request, @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        dpdpGuard.requireConsentIfMinor(currentUser(ownerId));

        if (!ALLOWED_COUNTS.contains(request.requestedCount())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "requestedCount must be one of 10, 25, 50");
        }
        requireReadyDocument(documentId, ownerId);

        String paramsJson = "{\"documentId\":\"" + documentId + "\",\"mode\":\"" + request.mode().name()
                + "\",\"requestedCount\":" + request.requestedCount() + "}";
        String fingerprint = JobEnqueueService.fingerprint(documentId.toString(), TaskType.QUIZ_BUILD.name(),
                request.mode().name(), request.requestedCount().toString());
        AiJob job = jobEnqueueService.enqueue(ownerId, TaskType.QUIZ_BUILD, paramsJson, idempotencyKey, fingerprint);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new QuizJobResponse(job.getId()));
    }

    @GetMapping("/documents/{documentId}/quizzes")
    public List<QuizResponse> listForDocument(@PathVariable UUID documentId, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        return quizRepository.findByDocumentIdAndOwnerIdOrderByCreatedAtDesc(documentId, ownerId).stream()
                .map(QuizResponse::from)
                .toList();
    }

    @GetMapping("/quizzes/{id}")
    public QuizResponse get(@PathVariable UUID id, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        return QuizResponse.from(requireQuiz(id, ownerId));
    }

    @GetMapping("/quizzes/{id}/questions")
    public List<QuizQuestionResponse> listQuestions(@PathVariable UUID id, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        Quiz quiz = requireQuiz(id, ownerId);
        return questionRepository
                .findByQuestionSetIdAndOwnerIdOrderBySortOrderAsc(quiz.getQuestionSetId(), ownerId).stream()
                .map(question -> QuizQuestionResponse.from(question, quiz.getId(), objectMapper))
                .toList();
    }

    private Quiz requireQuiz(UUID id, UUID ownerId) {
        return quizRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ApiException(ErrorCode.QUIZ_NOT_FOUND, "No quiz with that id"));
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
