package com.studyflow.jobs.service;

import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.TaskType;

/**
 * One implementation per {@link TaskType}, owned by the feature the task belongs to (e.g.
 * DocumentIngestHandler lives in {@code library}, SummaryGenerateHandler in {@code study}) — the
 * {@code jobs} package only defines the contract, never the feature-specific work.
 */
public interface JobHandler {

    TaskType taskType();

    /**
     * @return JSON text to store as {@code result_ref} (a pointer to the produced entity), or
     *         null if the task type doesn't produce one.
     * @throws com.studyflow.jobs.domain.TransientJobException for retryable failures only.
     */
    String handle(AiJob job, ProgressReporter progress) throws Exception;
}
