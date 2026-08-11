import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ApiError } from "../api/client";
import { getDocument, type DocumentResponse } from "../api/documents";
import { listSummaries, requestSummary } from "../api/summaries";
import { JobProgress } from "../components/JobProgress";
import { SourceRail } from "../components/SourceRail";

const INGESTING_STATUSES: DocumentResponse["status"][] = ["UPLOADED", "PARSING", "CHUNKING", "EMBEDDING"];

export function DocumentDetail() {
  const { id } = useParams<{ id: string }>();
  const documentId = id!;
  const queryClient = useQueryClient();
  const [activeSummaryJobId, setActiveSummaryJobId] = useState<string | null>(null);

  const documentQuery = useQuery({
    queryKey: ["document", documentId],
    queryFn: () => getDocument(documentId),
    refetchInterval: (query) => {
      const doc = query.state.data;
      return doc && INGESTING_STATUSES.includes(doc.status) ? 2000 : false;
    },
  });

  const summariesQuery = useQuery({
    queryKey: ["summaries", documentId],
    queryFn: () => listSummaries(documentId),
    enabled: documentQuery.data?.status === "READY",
  });

  const requestSummaryMutation = useMutation({
    mutationFn: () => requestSummary(documentId),
    onSuccess: (result) => setActiveSummaryJobId(result.jobId),
  });

  if (documentQuery.isLoading) {
    return (
      <div className="page stack" aria-busy="true" aria-live="polite">
        <span className="visually-hidden">Loading document…</span>
        <div className="skeleton" style={{ height: 32, width: "60%" }} />
        <div className="skeleton" style={{ height: 120 }} />
      </div>
    );
  }

  if (documentQuery.isError) {
    return (
      <div className="page">
        <div className="error-banner" role="alert">
          <strong>Couldn&apos;t load this document</strong>
          <span>{documentErrorMessageFor(documentQuery.error)}</span>
          <button type="button" className="button button-secondary" onClick={() => documentQuery.refetch()}>
            Retry
          </button>
        </div>
      </div>
    );
  }

  const doc = documentQuery.data;
  if (!doc) {
    return null;
  }

  return (
    <div className="page stack">
      <div>
        <h1>{doc.title}</h1>
        <p className="hint numeral">
          {doc.fileType} · {(doc.sizeBytes / 1024).toFixed(0)} KB
          {doc.pageCount != null ? ` · ${doc.pageCount} page(s)` : ""}
        </p>
      </div>

      {INGESTING_STATUSES.includes(doc.status) && (
        <div className="card stack-sm" aria-live="polite">
          <strong>Reading your document…</strong>
          <div className="progress-track">
            <div
              className="progress-fill"
              style={{ width: doc.status === "UPLOADED" ? "10%" : doc.status === "PARSING" ? "40%" : doc.status === "CHUNKING" ? "65%" : "85%" }}
            />
          </div>
          <span className="hint">This usually takes under a minute.</span>
        </div>
      )}

      {doc.status === "FAILED" && (
        <div className="error-banner" role="alert">
          <strong>Ingestion failed — {doc.failureCode ?? "UNKNOWN_ERROR"}</strong>
          <span>{failureMessageFor(doc.failureCode)}</span>
        </div>
      )}

      {doc.status === "READY" && (
        <div style={{ display: "flex", gap: "0.75rem", flexWrap: "wrap" }}>
          <Link to={`/documents/${documentId}/tutor`} className="button button-secondary">
            Ask the tutor
          </Link>
          <Link to={`/documents/${documentId}/key-points`} className="button button-secondary">
            Key points
          </Link>
          <Link to={`/documents/${documentId}/mcqs`} className="button button-secondary">
            Practice MCQs
          </Link>
          <Link to={`/documents/${documentId}/flashcards`} className="button button-secondary">
            Flashcards
          </Link>
          <Link to={`/documents/${documentId}/quizzes`} className="button button-secondary">
            Quizzes
          </Link>
          <Link to={`/documents/${documentId}/planner`} className="button button-secondary">
            Planner
          </Link>
        </div>
      )}

      {doc.status === "READY" && (
        <div className="card stack">
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
            <h2 style={{ margin: 0 }}>Summary</h2>
            {!activeSummaryJobId && (
              <button
                type="button"
                className="button button-primary"
                disabled={requestSummaryMutation.isPending}
                onClick={() => requestSummaryMutation.mutate()}
              >
                {requestSummaryMutation.isPending ? "Starting…" : "Generate summary"}
              </button>
            )}
          </div>

          {requestSummaryMutation.isError && (
            <div className="error-banner" role="alert">
              <strong>Couldn&apos;t start summary generation</strong>
              <span>{summaryErrorMessageFor(requestSummaryMutation.error)}</span>
            </div>
          )}

          {activeSummaryJobId && (
            <JobProgress
              jobId={activeSummaryJobId}
              label="Summary generation"
              onTerminal={(job) => {
                if (job.status === "SUCCEEDED") {
                  void queryClient.invalidateQueries({ queryKey: ["summaries", documentId] });
                }
                setActiveSummaryJobId(null);
              }}
            />
          )}

          {summariesQuery.isLoading && (
            <div className="skeleton" style={{ height: 80 }} aria-busy="true" aria-live="polite" />
          )}

          {summariesQuery.isError && (
            <div className="error-banner" role="alert">
              <strong>Couldn&apos;t load summaries</strong>
              <span>Something went wrong. Please try again.</span>
              <button type="button" className="button button-secondary" onClick={() => summariesQuery.refetch()}>
                Retry
              </button>
            </div>
          )}

          {summariesQuery.data && summariesQuery.data.length === 0 && !activeSummaryJobId && (
            <p className="hint">No summary yet — generate one above.</p>
          )}

          {summariesQuery.data && summariesQuery.data.length > 0 && (
            <div>
              <p className="summary-content">{summariesQuery.data[0].contentMd}</p>
              <SourceRail citations={summariesQuery.data[0].citations} />
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function failureMessageFor(code: string | null): string {
  switch (code) {
    case "FILE_ENCRYPTED":
      return "This PDF is password-protected. Remove the password and upload it again.";
    case "FILE_NO_TEXT_LAYER":
      return "This looks like a scanned image with no selectable text — OCR isn't supported yet.";
    case "FILE_CORRUPT":
      return "This file couldn't be read. It may be corrupted — try re-exporting it.";
    case "STORAGE_READ_ERROR":
      return "We couldn't read your uploaded file from storage. Please try uploading again.";
    default:
      return "Please try uploading again.";
  }
}

function summaryErrorMessageFor(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "DOCUMENT_NOT_READY":
        return "This document is still being processed.";
      case "AUTH_GUARDIAN_CONSENT_REQUIRED":
        return "Generating summaries requires guardian consent for accounts under 18.";
      case "QUOTA_AI_EXCEEDED":
        return "You've hit this month's AI generation limit.";
      default:
        return "Something went wrong. Please try again.";
    }
  }
  return "Something went wrong. Please try again.";
}

function documentErrorMessageFor(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "DOCUMENT_NOT_FOUND":
        return "This document could not be found.";
      case "AUTH_GUARDIAN_CONSENT_REQUIRED":
        return "You do not have permission to view this document.";
      default:
        return "Something went wrong. Please try again.";
    }
  }
  return "Something went wrong. Please try again.";
}
