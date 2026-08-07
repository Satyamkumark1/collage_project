package com.studyflow.identity.dto;

import java.time.Instant;
import java.util.UUID;

public record RegisterResponse(UUID id, String email, String name, Instant createdAt) {
}
