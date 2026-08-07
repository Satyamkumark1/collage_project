package com.studyflow.jobs.service;

/** Handed to a {@link JobHandler} so it can report progress without knowing about persistence. */
public interface ProgressReporter {

    void report(int progressPct, String progressStage);
}
