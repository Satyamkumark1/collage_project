package com.studyflow.library.dto;

import com.studyflow.library.domain.Document;
import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        String title,
        String originalFilename,
        String mimeType,
        String fileType,
        long sizeBytes,
        Integer pageCount,
        Integer charCount,
        String status,
        String failureCode,
        String failureDetail,
        Instant createdAt) {

    public static DocumentResponse from(Document document) {
        return new DocumentResponse(document.getId(), document.getTitle(), document.getOriginalFilename(),
                document.getMimeType(), document.getFileType().name(), document.getSizeBytes(),
                document.getPageCount(), document.getCharCount(), document.getStatus().name(),
                document.getFailureCode(), document.getFailureDetail(), document.getCreatedAt());
    }
}
