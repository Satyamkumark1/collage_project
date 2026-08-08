package com.studyflow.identity.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String email, @NotBlank String password) {

    // Records generate toString() from all components by default, which would put the
    // plaintext password into any log line, exception message, or debugger view that prints
    // this object — never log document text, chat messages, JWTs, or API keys (see CLAUDE.md).
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=REDACTED]";
    }
}
