package com.studyflow.library.web;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.identity.domain.User;
import com.studyflow.identity.service.DpdpGuard;
import com.studyflow.identity.service.IdentityService;
import com.studyflow.library.domain.Document;
import com.studyflow.library.dto.DocumentResponse;
import com.studyflow.library.dto.UploadResponse;
import com.studyflow.library.repo.DocumentRepository;
import com.studyflow.library.service.DocumentUploadService;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final DocumentUploadService uploadService;
    private final DocumentRepository documentRepository;
    private final IdentityService identityService;
    private final DpdpGuard dpdpGuard;

    public DocumentController(DocumentUploadService uploadService, DocumentRepository documentRepository,
            IdentityService identityService, DpdpGuard dpdpGuard) {
        this.uploadService = uploadService;
        this.documentRepository = documentRepository;
        this.identityService = identityService;
        this.dpdpGuard = dpdpGuard;
    }

    public record DocumentListResponse(List<DocumentResponse> items, UUID nextCursor) {
    }

    @PostMapping
    public ResponseEntity<UploadResponse> upload(@RequestPart("file") MultipartFile file,
            @RequestPart(value = "title", required = false) String title,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        dpdpGuard.requireConsentIfMinor(currentUser(ownerId));

        DocumentUploadService.UploadResult result = uploadService.upload(ownerId, file, title, idempotencyKey);
        UploadResponse body = new UploadResponse(result.documentId(), result.jobId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @GetMapping("/{id}")
    public DocumentResponse get(@PathVariable UUID id, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        Document document = documentRepository.findByIdAndOwnerId(id, ownerId)
                .filter(doc -> doc.getDeletedAt() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_NOT_FOUND, "No document with that id"));
        return DocumentResponse.from(document);
    }

    @GetMapping
    public DocumentListResponse list(Authentication authentication, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) UUID cursor) {
        if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and " + MAX_LIMIT);
        }
        UUID ownerId = UUID.fromString(authentication.getName());
        int effectiveLimit = limit == null ? DEFAULT_LIMIT : limit;
        Limit limitObj = Limit.of(effectiveLimit + 1);
        List<Document> page = cursor == null
                ? documentRepository.findByOwnerIdAndDeletedAtIsNullOrderByIdDesc(ownerId, limitObj)
                : documentRepository.findByOwnerIdAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(ownerId, cursor,
                        limitObj);

        boolean hasMore = page.size() > effectiveLimit;
        List<Document> pageItems = hasMore ? page.subList(0, effectiveLimit) : page;
        UUID nextCursor = hasMore ? pageItems.get(pageItems.size() - 1).getId() : null;

        return new DocumentListResponse(pageItems.stream().map(DocumentResponse::from).toList(), nextCursor);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        Document document = documentRepository.findByIdAndOwnerId(id, ownerId)
                .filter(doc -> doc.getDeletedAt() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_NOT_FOUND, "No document with that id"));
        document.softDelete();
        documentRepository.save(document);
        return ResponseEntity.noContent().build();
    }

    private User currentUser(UUID userId) {
        return identityService.requireActiveUser(userId);
    }
}
