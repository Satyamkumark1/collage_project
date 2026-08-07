package com.studyflow.rag.domain;

/**
 * A permanent (non-retryable) content problem found during parsing — encrypted PDF, no text
 * layer, or corrupt file (see specs/09-rag.md). {@code failureCode} matches one of the
 * FILE_* codes in specs/03-api-and-errors.md and is written to {@code documents.failure_code}.
 */
public class DocumentParsingException extends RuntimeException {

    private final String failureCode;

    public DocumentParsingException(String failureCode, String message) {
        super(message);
        this.failureCode = failureCode;
    }

    public String failureCode() {
        return failureCode;
    }
}
