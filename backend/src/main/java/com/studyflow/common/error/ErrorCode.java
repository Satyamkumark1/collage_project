package com.studyflow.common.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes the frontend switches on (see
 * specs/03-api-and-errors.md). Never render {@code detail} to a user — codes
 * are what's
 * human-facing text gets keyed off of, on the frontend.
 */
public enum ErrorCode {

    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED),
    AUTH_REFRESH_REUSED(HttpStatus.UNAUTHORIZED),
    AUTH_GUARDIAN_CONSENT_REQUIRED(HttpStatus.FORBIDDEN),
    // OAuth sign-ins (Google/GitHub) create the account before birth year is known — see
    // docs/DECISIONS.md. Thrown by DpdpGuard until the post-login "confirm birth year" step
    // completes (PATCH /me/birth-year).
    AUTH_BIRTH_YEAR_REQUIRED(HttpStatus.FORBIDDEN),
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

    // Not in the original spec table — added for endpoints the table implies but
    // doesn't
    // spell out (GET/DELETE by id, unmapped routes). Logged here rather than
    // silently: see
    // docs/DECISIONS.md.
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    SUMMARY_NOT_FOUND(HttpStatus.NOT_FOUND),
    // Phase 2 (tutor chat) — same "GET/DELETE by id needs a not-found code" gap as
    // the two above.
    CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    // Phase 3 (batch study generation) — same gap again.
    KEY_POINTS_NOT_FOUND(HttpStatus.NOT_FOUND),
    QUESTION_SET_NOT_FOUND(HttpStatus.NOT_FOUND),
    FLASHCARD_NOT_FOUND(HttpStatus.NOT_FOUND),
    // Phase 4 (quizzes) — same "GET by id needs a not-found code" gap, plus two attempt-lifecycle
    // conflict codes the spec table never anticipated (server-authoritative timing wasn't
    // possible to fully spec ahead of time — see docs/DECISIONS.md).
    QUIZ_NOT_FOUND(HttpStatus.NOT_FOUND),
    QUIZ_ATTEMPT_NOT_FOUND(HttpStatus.NOT_FOUND),
    QUIZ_ATTEMPT_EXPIRED(HttpStatus.CONFLICT),
    QUIZ_ATTEMPT_NOT_IN_PROGRESS(HttpStatus.CONFLICT),
    QUIZ_ATTEMPT_NOT_SUBMITTED(HttpStatus.CONFLICT),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    // Phase 5, checkpoint B: the login-attempt L1 in-process bucket that specs/06-rate-limiting.md
    // and docs/DECISIONS.md already claimed existed ("this phase: only the L1 in-process bucket
    // exists... for login attempts") but was never actually wired up until now — see
    // docs/DECISIONS.md.
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    // Phase 7 (study planner) — same "GET by id needs a not-found code" gap as every prior phase.
    STUDY_PLAN_NOT_FOUND(HttpStatus.NOT_FOUND),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
