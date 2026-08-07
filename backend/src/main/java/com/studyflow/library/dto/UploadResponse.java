package com.studyflow.library.dto;

import java.util.UUID;

public record UploadResponse(UUID documentId, UUID jobId) {
}
