package com.studyflow.common.error;

import java.util.List;

/** Carries a stable {@link ErrorCode} through to the RFC 9457 problem+json response. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<String> errors;
    private final Long retryAfterSeconds;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, null, null);
    }

    public ApiException(ErrorCode code, String detail, List<String> errors, Long retryAfterSeconds) {
        super(detail);
        this.code = code;
        this.errors = errors;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ErrorCode code() {
        return code;
    }

    public List<String> errors() {
        return errors;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
