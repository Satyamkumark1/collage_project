package com.studyflow.common.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes the frontend switches on (see
 * specs/03-api-and-errors.md). Never render {@code detail} to a user — codes are what's
 * human-facing text gets keyed off of, on the frontend.
 */
public enum ErrorCode {

    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED),
    AUTH_REFRESH_REUSED(HttpStatus.UNAUTHORIZED),
    AUTH_GUARDIAN_CONSENT_REQUIRED(HttpStatus.FORBIDDEN),
    AUTH_EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT),

    FILE_TYPE_UNSUPPORTED(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
    FILE_ENCRYPTED(HttpStatus.UNPROCESSABLE_ENTITY),
    FILE_NO_TEXT_LAYER(HttpStatus.UNPROCESSABLE_ENTITY),
    FILE_CORRUPT(HttpStatus.UNPROCESSABLE_ENTITY),

    DOCUMENT_NOT_READY(HttpStatus.CONFLICT),
    JOB_NOT_FOUND(HttpStatus.NOT_FOUND),

    AI_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    AI_SCHEMA_INVALID(HttpStatus.UNPROCESSABLE_ENTITY),
    AI_INSUFFICIENT_CONTEXT(HttpStatus.UNPROCESSABLE_ENTITY),

    QUOTA_UPLOADS_EXCEEDED(HttpStatus.PAYMENT_REQUIRED),
    QUOTA_AI_EXCEEDED(HttpStatus.PAYMENT_REQUIRED),

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),

    // Not in the original spec table — added for endpoints the table implies but doesn't
    // spell out (GET/DELETE by id, unmapped routes). Logged here rather than silently: see
    // docs/DECISIONS.md.
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    SUMMARY_NOT_FOUND(HttpStatus.NOT_FOUND),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
