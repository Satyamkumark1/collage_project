import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useParams } from "react-router-dom";
import { ApiError } from "../api/client";
import { getDocument, type DocumentResponse } from "../api/documents";
import { listQuestionSets, listQuestions, requestQuestionSet } from "../api/mcq";
import { JobProgress } from "../components/JobProgress";
import { CitationList } from "../components/CitationList";

const INGESTING_STATUSES: DocumentResponse["status"][] = ["UPLOADED", "PARSING", "CHUNKING", "EMBEDDING"];
const REQUESTABLE_COUNTS = [10, 25, 50] as const;

export function Mcqs() {
  const { id } = useParams<{ id: string }>();
  const documentId = id!;
  const queryClient = useQueryClient();
  const [revealed, setRevealed] = useState<Set<string>>(new Set());
  const [requestedCount, setRequestedCount] = useState<number>(10);

  const documentQuery = useQuery({
    queryKey: ["document", documentId],
    queryFn: () => getDocument(documentId),
    refetchInterval: (query) => {
      const doc = query.state.data;
      return doc && INGESTING_STATUSES.includes(doc.status) ? 2000 : false;
    },
  });

  const questionSetsQuery = useQuery({
    queryKey: ["question-sets", documentId],
    queryFn: () => listQuestionSets(documentId),
    enabled: documentQuery.data?.status === "READY",
  });

  const latestQuestionSet = questionSetsQuery.data?.[0];

  const questionsQuery = useQuery({
    queryKey: ["questions", latestQuestionSet?.id],
    queryFn: () => listQuestions(latestQuestionSet!.id),
    enabled: !!latestQuestionSet,
  });

  const requestMutation = useMutation({
    mutationFn: () => requestQuestionSet(documentId, requestedCount),
    onSuccess: () => setRevealed(new Set()),
  });

  if (documentQuery.isLoading) {
    return (
      <div className="page stack" aria-busy="true" aria-live="polite">
        <span className="visually-hidden">Loading MCQs…</span>
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
        <h1>Practice MCQs</h1>
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
            <h2 style={{ margin: 0 }}>Generate a new set</h2>
            <div style={{ display: "flex", gap: "0.5rem", alignItems: "center" }}>
              <div style={{ display: "flex", gap: "0.5rem" }} role="group" aria-label="Number of questions">
                {REQUESTABLE_COUNTS.map((count) => (
                  <button
                    key={count}
                    type="button"
                    className={requestedCount === count ? "button button-primary" : "button button-secondary"}
                    aria-pressed={requestedCount === count}
                    onClick={() => setRequestedCount(count)}
                  >
                    {count}
                  </button>
                ))}
              </div>
              <button
                type="button"
                className="button button-primary"
                disabled={requestMutation.isPending}
                onClick={() => requestMutation.mutate()}
              >
                {requestMutation.isPending ? "Starting…" : "Generate"}
              </button>
            </div>
          </div>

          {requestMutation.isError && (
            <div className="error-banner" role="alert">
              <strong>Couldn&apos;t start MCQ generation</strong>
              <span>{mcqErrorMessageFor(requestMutation.error)}</span>
            </div>
          )}

          {requestMutation.data && (
            <JobProgress
              jobId={requestMutation.data.jobId}
              label="MCQ generation"
              onTerminal={(job) => {
                if (job.status === "SUCCEEDED") {
                  void queryClient.invalidateQueries({ queryKey: ["question-sets", documentId] });
                }
              }}
            />
          )}

          {questionSetsQuery.isLoading && (
            <div className="skeleton" style={{ height: 80 }} aria-busy="true" aria-live="polite" />
          )}

          {questionSetsQuery.data && questionSetsQuery.data.length === 0 && !requestMutation.data && (
            <p className="hint">No question sets yet. Generate one above.</p>
          )}

          {latestQuestionSet && (
            <div className="stack">
              <h2 style={{ margin: 0 }}>
                Latest set · {latestQuestionSet.generatedCount} of {latestQuestionSet.requestedCount} questions
              </h2>

              {latestQuestionSet.generatedCount < latestQuestionSet.requestedCount && (
                <div className="partial-banner" role="status">
                  <strong>
                    {latestQuestionSet.generatedCount} of {latestQuestionSet.requestedCount} questions generated
                  </strong>
                  <span>
                    The rest didn&apos;t pass quality checks and were skipped — you can try generating another set.
                  </span>
                </div>
              )}

              {questionsQuery.isLoading && (
                <div className="skeleton" style={{ height: 200 }} aria-busy="true" aria-live="polite" />
              )}

              {questionsQuery.data && (
                <div className="stack">
                  {questionsQuery.data.map((question, index) => {
                    const isRevealed = revealed.has(question.id);
                    return (
                      <article key={question.id} className="card stack-sm">
                        <div style={{ display: "flex", justifyContent: "space-between", gap: "1rem", alignItems: "baseline" }}>
                          <strong>
                            {index + 1}. {question.stem}
                          </strong>
                          <span className="badge badge-progress">
                            {question.difficulty} · {question.bloomLevel}
                          </span>
                        </div>
                        <ul style={{ listStyle: "none", margin: 0, padding: 0 }} className="stack-sm">
                          {question.options.map((option, optionIndex) => {
                            const isCorrect = optionIndex === question.correctIndex;
                            return (
                              <li
                                key={optionIndex}
                                className="card"
                                style={{
                                  padding: "0.5rem 0.75rem",
                                  borderColor:
                                    isRevealed && isCorrect ? "var(--check)" : undefined,
                                  color: isRevealed && isCorrect ? "var(--check)" : undefined,
                                }}
                              >
                                {String.fromCharCode(65 + optionIndex)}. {option}
                              </li>
                            );
                          })}
                        </ul>
                        {isRevealed ? (
                          <p className="hint">{question.explanation}</p>
                        ) : (
                          <button
                            type="button"
                            className="button button-secondary"
                            onClick={() => setRevealed((prev) => new Set(prev).add(question.id))}
                          >
                            Reveal answer
                          </button>
                        )}
                        <CitationList citations={question.citations} />
                      </article>
                    );
                  })}
                </div>
              )}
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

function mcqErrorMessageFor(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "DOCUMENT_NOT_READY":
        return "This document is still being processed.";
      case "AUTH_GUARDIAN_CONSENT_REQUIRED":
        return "Generating MCQs requires guardian consent for accounts under 18.";
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
