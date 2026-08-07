package com.studyflow.jobs.domain;

/**
 * Thrown by a {@link com.studyflow.jobs.service.JobHandler} for retryable failure classes only
 * (429s, 5xxs, timeouts, connection resets — see specs/07-jobs-and-async.md). Any other
 * exception a handler throws goes straight to FAILED, no retry.
 */
public class TransientJobException extends RuntimeException {

    public TransientJobException(String message, Throwable cause) {
        super(message, cause);
    }

    public TransientJobException(String message) {
        super(message);
    }
}
