import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useParams } from "react-router-dom";
import { ApiError } from "../api/client";
import { getDocument, type DocumentResponse } from "../api/documents";
import { listFlashcards, requestFlashcards, reviewFlashcard } from "../api/flashcards";
import { JobProgress } from "../components/JobProgress";
import { CitationList } from "../components/CitationList";

const INGESTING_STATUSES: DocumentResponse["status"][] = ["UPLOADED", "PARSING", "CHUNKING", "EMBEDDING"];

// Anki-style collapse of SM-2's canonical 0-5 quality scale onto 4 buttons — the backend still
// accepts/stores the full 0-5 range (see docs/DECISIONS.md).
const QUALITY_BUTTONS = [
  { label: "Again", quality: 0 },
  { label: "Hard", quality: 3 },
  { label: "Good", quality: 4 },
  { label: "Easy", quality: 5 },
] as const;

export function Flashcards() {
  const { id } = useParams<{ id: string }>();
  const documentId = id!;
  const queryClient = useQueryClient();
  const [revealed, setRevealed] = useState<Set<string>>(new Set());

  const documentQuery = useQuery({
    queryKey: ["document", documentId],
    queryFn: () => getDocument(documentId),
    refetchInterval: (query) => {
      const doc = query.state.data;
      return doc && INGESTING_STATUSES.includes(doc.status) ? 2000 : false;
    },
  });

  const flashcardsQuery = useQuery({
    queryKey: ["flashcards", documentId],
    queryFn: () => listFlashcards(documentId),
    enabled: documentQuery.data?.status === "READY",
  });

  const requestMutation = useMutation({
    mutationFn: () => requestFlashcards(documentId),
    onSuccess: () => setRevealed(new Set()),
  });

  const reviewMutation = useMutation({
    mutationFn: ({ cardId, quality }: { cardId: string; quality: number }) => reviewFlashcard(cardId, quality),
    onSuccess: (_data, variables) => {
      setRevealed((prev) => {
        const next = new Set(prev);
        next.delete(variables.cardId);
        return next;
      });
      void queryClient.invalidateQueries({ queryKey: ["flashcards", documentId] });
    },
  });

  if (documentQuery.isLoading) {
    return (
      <div className="page stack" aria-busy="true" aria-live="polite">
        <span className="visually-hidden">Loading flashcards…</span>
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

  const now = Date.now();
  const dueCount = flashcardsQuery.data?.filter((card) => new Date(card.dueAt).getTime() <= now).length ?? 0;

  return (
    <div className="page stack">
      <div>
        <h1>Flashcards</h1>
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
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: "1rem", flexWrap: "wrap" }}>
            <h2 style={{ margin: 0 }}>
              Deck
              {dueCount > 0 && (
                <span className="badge" style={{ marginLeft: "0.5rem", borderColor: "var(--highlight)", color: "var(--highlight)" }}>
                  {dueCount} due now
                </span>
              )}
            </h2>
            <button
              type="button"
              className="button button-primary"
              disabled={requestMutation.isPending}
              onClick={() => requestMutation.mutate()}
            >
              {requestMutation.isPending ? "Starting…" : "Generate flashcards"}
            </button>
          </div>

          {requestMutation.isError && (
            <div className="error-banner" role="alert">
              <strong>Couldn&apos;t start flashcard generation</strong>
              <span>{flashcardErrorMessageFor(requestMutation.error)}</span>
            </div>
          )}

          {requestMutation.data && (
            <JobProgress
              jobId={requestMutation.data.jobId}
              label="Flashcard generation"
              onTerminal={(job) => {
                if (job.status === "SUCCEEDED") {
                  void queryClient.invalidateQueries({ queryKey: ["flashcards", documentId] });
                }
              }}
            />
          )}

          {flashcardsQuery.isLoading && (
            <div className="skeleton" style={{ height: 80 }} aria-busy="true" aria-live="polite" />
          )}

          {flashcardsQuery.data && flashcardsQuery.data.length === 0 && !requestMutation.data && (
            <p className="hint">No flashcards yet. Generate them above.</p>
          )}

          {flashcardsQuery.data && flashcardsQuery.data.length > 0 && (
            <div className="stack">
              {flashcardsQuery.data.map((card) => {
                const isRevealed = revealed.has(card.id);
                const isDue = new Date(card.dueAt).getTime() <= now;
                const isReviewing = reviewMutation.isPending && reviewMutation.variables?.cardId === card.id;
                return (
                  <article
                    key={card.id}
                    className="card stack-sm"
                    style={isDue ? { borderColor: "var(--highlight)" } : undefined}
                  >
                    <div style={{ display: "flex", justifyContent: "space-between", gap: "1rem", alignItems: "baseline" }}>
                      <strong>{card.frontMd}</strong>
                      {isDue && (
                        <span className="badge" style={{ borderColor: "var(--highlight)", color: "var(--highlight)" }}>
                          Due now
                        </span>
                      )}
                    </div>

                    {isRevealed ? (
                      <>
                        <p className="summary-content">{card.backMd}</p>
                        <CitationList citations={card.citations} />
                        <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap" }}>
                          {QUALITY_BUTTONS.map((button) => (
                            <button
                              key={button.label}
                              type="button"
                              className="button button-secondary"
                              disabled={reviewMutation.isPending}
                              onClick={() => reviewMutation.mutate({ cardId: card.id, quality: button.quality })}
                            >
                              {isReviewing ? "…" : button.label}
                            </button>
                          ))}
                        </div>
                      </>
                    ) : (
                      <button
                        type="button"
                        className="button button-secondary"
                        onClick={() => setRevealed((prev) => new Set(prev).add(card.id))}
                      >
                        Show answer
                      </button>
                    )}

                    <span className="hint numeral">
                      Reviewed {card.repetitions}× · ease {card.easeFactor.toFixed(2)} · due{" "}
                      {new Date(card.dueAt).toLocaleDateString()}
                    </span>
                  </article>
                );
              })}
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

function flashcardErrorMessageFor(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "DOCUMENT_NOT_READY":
        return "This document is still being processed.";
      case "AUTH_GUARDIAN_CONSENT_REQUIRED":
        return "Generating flashcards requires guardian consent for accounts under 18.";
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
