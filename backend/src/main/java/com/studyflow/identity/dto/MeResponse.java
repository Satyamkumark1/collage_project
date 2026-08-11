package com.studyflow.identity.dto;

import java.util.UUID;

public record MeResponse(
        UUID id,
        String email,
        String name,
        String role,
        boolean emailVerified,
        boolean isMinor,
        boolean guardianConsentRequired,
        boolean birthYearRequired) {
}
