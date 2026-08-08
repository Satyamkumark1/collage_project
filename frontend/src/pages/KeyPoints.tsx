import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useParams } from "react-router-dom";
import { ApiError } from "../api/client";
import { getDocument, type DocumentResponse } from "../api/documents";
import { listKeyPoints, requestKeyPoints } from "../api/keyPoints";
import { JobProgress } from "../components/JobProgress";
import { CitationList } from "../components/CitationList";

const INGESTING_STATUSES: DocumentResponse["status"][] = ["UPLOADED", "PARSING", "CHUNKING", "EMBEDDING"];

export function KeyPoints() {
  const { id } = useParams<{ id: string }>();
  const documentId = id!;
  const queryClient = useQueryClient();

  const documentQuery = useQuery({
    queryKey: ["document", documentId],
    queryFn: () => getDocument(documentId),
    refetchInterval: (query) => {
      const doc = query.state.data;
      return doc && INGESTING_STATUSES.includes(doc.status) ? 2000 : false;
    },
  });

  const keyPointsQuery = useQuery({
    queryKey: ["key-points", documentId],
    queryFn: () => listKeyPoints(documentId),
    enabled: documentQuery.data?.status === "READY",
  });

  const requestKeyPointsMutation = useMutation({
    mutationFn: () => requestKeyPoints(documentId),
  });

  if (documentQuery.isLoading) {
    return (
      <div className="page stack" aria-busy="true" aria-live="polite">
        <span className="visually-hidden">Loading key points…</span>
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
        <h1>Key points</h1>
        <p className="hint numeral">
          {doc.title} · {doc.fileType} · {(doc.sizeBytes / 1024).toFixed(0)} KB
          {doc.pageCount != null ? ` · ${doc.pageCount} page(s)` : ""}
        </p>
      </div>

      {INGESTING_STATUSES.includes(doc.status) && (
        <div className="card stack-sm" aria-live="polite">
          <strong>Reading your document…</strong>
          <div className="progress-track">
            <div
              className="progress-fill"
              style={{
                width:
                  doc.status === "UPLOADED" ? "10%" : doc.status === "PARSING" ? "40%" : doc.status === "CHUNKING" ? "65%" : "85%",
              }}
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
        <div className="card stack">
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: "1rem" }}>
            <h2 style={{ margin: 0 }}>Study points</h2>
            <button
              type="button"
              className="button button-primary"
              disabled={requestKeyPointsMutation.isPending}
              onClick={() => requestKeyPointsMutation.mutate()}
            >
              {requestKeyPointsMutation.isPending ? "Starting…" : "Generate key points"}
            </button>
          </div>

          {requestKeyPointsMutation.isError && (
            <div className="error-banner" role="alert">
              <strong>Couldn&apos;t start key point generation</strong>
              <span>{keyPointsErrorMessageFor(requestKeyPointsMutation.error)}</span>
            </div>
          )}

          {requestKeyPointsMutation.data && (
            <JobProgress
              jobId={requestKeyPointsMutation.data.jobId}
              label="Key point generation"
              onTerminal={(job) => {
                if (job.status === "SUCCEEDED") {
                  void queryClient.invalidateQueries({ queryKey: ["key-points", documentId] });
                }
              }}
            />
          )}

          {keyPointsQuery.isLoading && (
            <div className="skeleton" style={{ height: 80 }} aria-busy="true" aria-live="polite" />
          )}

          {keyPointsQuery.isError && (
            <div className="error-banner" role="alert">
              <strong>Couldn&apos;t load key points</strong>
              <span>Something went wrong. Please try again.</span>
              <button type="button" className="button button-secondary" onClick={() => keyPointsQuery.refetch()}>
                Retry
              </button>
            </div>
          )}

          {keyPointsQuery.data && keyPointsQuery.data.length === 0 && !requestKeyPointsMutation.data && (
            <p className="hint">No key points yet. Generate them above.</p>
          )}

          {keyPointsQuery.data && keyPointsQuery.data.length > 0 && (
            <div className="stack">
              {keyPointsQuery.data.map((keyPoint) => (
                <article key={keyPoint.id} className="card stack-sm">
                  <div style={{ display: "flex", justifyContent: "space-between", gap: "1rem", alignItems: "baseline" }}>
                    <h3 style={{ margin: 0 }}>{keyPoint.label}</h3>
                    <span className="badge badge-progress">{keyPoint.category}</span>
                  </div>
                  <p className="summary-content">{keyPoint.contentMd}</p>
                  <CitationList citations={keyPoint.citations} />
                </article>
              ))}
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

function keyPointsErrorMessageFor(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "DOCUMENT_NOT_READY":
        return "This document is still being processed.";
      case "AUTH_GUARDIAN_CONSENT_REQUIRED":
        return "Generating key points requires guardian consent for accounts under 18.";
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
