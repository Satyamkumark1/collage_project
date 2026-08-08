package com.studyflow.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email String email,
        // BCrypt only hashes the first 72 bytes of its input; a longer password is silently
        // truncated to that prefix, which is surprising rather than a real strength limit — cap
        // it here instead of accepting input the hasher would quietly ignore the tail of.
        @NotBlank @Size(min = 10, max = 72, message = "must be between 10 and 72 characters") String password,
        @NotBlank String name,
        @NotNull @Min(1900) Short birthYear) {
}
