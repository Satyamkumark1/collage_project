import { useQuery } from "@tanstack/react-query";
import { useEffect, useRef } from "react";
import { ApiError } from "../api/client";
import { getJob, isTerminal, type JobResponse } from "../api/jobs";

interface JobProgressProps {
  jobId: string;
  label?: string;
  onTerminal?: (job: JobResponse) => void;
}

/**
 * Polls GET /jobs/{id} at 1500ms, backing off to 5s after 30s, stopping on terminal state —
 * see specs/11-frontend.md §Async UX. Generation takes 20-180s; a bare spinner for two minutes
 * is a bug report, so this always shows real percent + stage, never just a spinner.
 */
export function JobProgress({ jobId, label, onTerminal }: JobProgressProps) {
  const startedAtRef = useRef(Date.now());

  const { data, error } = useQuery({
    queryKey: ["job", jobId],
    queryFn: () => getJob(jobId),
    refetchInterval: (query) => {
      const job = query.state.data;
      if (job && isTerminal(job.status)) {
        return false;
      }
      const elapsedMs = Date.now() - startedAtRef.current;
      return elapsedMs > 30_000 ? 5000 : 1500;
    },
  });

  useEffect(() => {
    if (data && isTerminal(data.status)) {
      onTerminal?.(data);
    }
    // Only re-run when the status itself changes, not on every poll tick.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data?.status]);

  if (error) {
    return (
      <div className="error-banner" role="alert">
        <strong>Could not check status</strong>
        <span>{statusCheckErrorMessageFor(error)}</span>
      </div>
    );
  }

  if (!data) {
    return (
      <div className="stack-sm" aria-live="polite" aria-busy="true">
        <div className="skeleton" style={{ height: 8 }} />
        <span className="hint">Checking status…</span>
      </div>
    );
  }

  if (data.status === "FAILED") {
    return (
      <div className="error-banner" role="alert">
        <strong>
          {label ?? "This"} failed — {data.errorCode ?? "UNKNOWN_ERROR"}
        </strong>
        <span>{jobFailureMessageFor(data.errorCode)}</span>
      </div>
    );
  }

  return (
    <div className="stack-sm" aria-live="polite">
      <div className="progress-track" role="progressbar" aria-valuenow={data.progressPct} aria-valuemin={0} aria-valuemax={100}>
        <div className="progress-fill" style={{ width: `${data.progressPct}%` }} />
      </div>
      <span className="hint numeral">
        {data.progressPct}% — {data.progressStage ?? data.status}
      </span>
    </div>
  );
}

// The API only ever sends a stable errorCode, never a diagnostic message (see JobResponse) —
// this is the frontend-owned copy for each code, same pattern as DocumentDetail's
// failureMessageFor/summaryErrorMessageFor.
function jobFailureMessageFor(errorCode: string | null): string {
  switch (errorCode) {
    case "AI_SCHEMA_INVALID":
      return "The AI's response couldn't be validated. Please try again.";
    case "AI_INSUFFICIENT_CONTEXT":
      return "There wasn't enough usable content to work with.";
    case "TRANSIENT_FAILURE":
      return "A temporary error occurred and retries were exhausted. Please try again.";
    default:
      return "Something went wrong. Please try again.";
  }
}

function statusCheckErrorMessageFor(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "JOB_NOT_FOUND":
        return "This job could not be found.";
      default:
        return "Please check your connection and try again.";
    }
  }
  return "Please check your connection and try again.";
}
