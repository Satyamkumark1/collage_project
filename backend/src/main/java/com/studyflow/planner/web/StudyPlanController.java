package com.studyflow.planner.web;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.library.domain.Document;
import com.studyflow.library.repo.DocumentRepository;
import com.studyflow.planner.domain.StudyPlan;
import com.studyflow.planner.dto.StudyPlanRequest;
import com.studyflow.planner.dto.StudyPlanResponse;
import com.studyflow.planner.service.StudyPlanService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Synchronous CRUD, not a job — see docs/DECISIONS.md. Not gated on {@code DocumentStatus.READY}
 * or DPDP consent: planning touches only the exam date, no chunk/text content, no LLM call.
 */
@RestController
@RequestMapping("/api/v1")
public class StudyPlanController {

    private final DocumentRepository documentRepository;
    private final StudyPlanService studyPlanService;

    public StudyPlanController(DocumentRepository documentRepository, StudyPlanService studyPlanService) {
        this.documentRepository = documentRepository;
        this.studyPlanService = studyPlanService;
    }

    @PostMapping("/documents/{documentId}/study-plans")
    public ResponseEntity<StudyPlanResponse> create(@PathVariable UUID documentId,
            @Valid @RequestBody StudyPlanRequest request, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        requireOwnedDocument(documentId, ownerId);

        StudyPlan plan = studyPlanService.build(documentId, ownerId, request.examDate());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(StudyPlanResponse.from(plan, studyPlanService.sessionsFor(plan.getId(), ownerId)));
    }

    @GetMapping("/documents/{documentId}/study-plans")
    public List<StudyPlanResponse> listForDocument(@PathVariable UUID documentId, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        requireOwnedDocument(documentId, ownerId);
        return studyPlanService.plansForDocument(documentId, ownerId).stream()
                .map(plan -> StudyPlanResponse.from(plan, studyPlanService.sessionsFor(plan.getId(), ownerId)))
                .toList();
    }

    @GetMapping("/study-plans/{id}")
    public StudyPlanResponse get(@PathVariable UUID id, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        StudyPlan plan = studyPlanService.requirePlan(id, ownerId);
        return StudyPlanResponse.from(plan, studyPlanService.sessionsFor(plan.getId(), ownerId));
    }

    @GetMapping("/study-plans/{id}/export.ics")
    public ResponseEntity<String> exportIcs(@PathVariable UUID id, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        StudyPlan plan = studyPlanService.requirePlan(id, ownerId);
        Document document = documentRepository.findByIdAndOwnerId(plan.getDocumentId(), ownerId)
                .orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_NOT_FOUND, "No document with that id"));

        String ics = studyPlanService.renderIcs(plan, document.getTitle());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/calendar"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("study-plan.ics").build().toString())
                .body(ics);
    }

    private Document requireOwnedDocument(UUID documentId, UUID ownerId) {
        return documentRepository.findByIdAndOwnerId(documentId, ownerId)
                .filter(doc -> doc.getDeletedAt() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_NOT_FOUND, "No document with that id"));
    }
}
