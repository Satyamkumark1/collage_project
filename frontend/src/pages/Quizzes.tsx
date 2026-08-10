import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";
import { ApiError } from "../api/client";
import { getDocument, type DocumentResponse } from "../api/documents";
import {
  listAttempts,
  listQuizzes,
  requestQuiz,
  startAttempt,
  type QuizAttemptResponse,
  type QuizMode,
  type QuizResponse,
} from "../api/quizzes";
import { JobProgress } from "../components/JobProgress";

const INGESTING_STATUSES: DocumentResponse["status"][] = ["UPLOADED", "PARSING", "CHUNKING", "EMBEDDING"];
const REQUESTABLE_COUNTS = [10, 25, 50] as const;
const MODES: { value: QuizMode; label: string; hint: string }[] = [
  { value: "PRACTICE", label: "Practice", hint: "Timer shown, not enforced. No negative marking." },
  { value: "EXAM", label: "Exam", hint: "Hard deadline, auto-submits at zero. −0.25 per wrong answer." },
  { value: "REVISION", label: "Revision", hint: "Untimed. See if you're right after every answer." },
];

export function Quizzes() {
  const { id } = useParams<{ id: string }>();
  const documentId = id!;
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [mode, setMode] = useState<QuizMode>("PRACTICE");
  const [requestedCount, setRequestedCount] = useState<number>(10);
  const [startingQuizId, setStartingQuizId] = useState<string | null>(null);

  const documentQuery = useQuery({
    queryKey: ["document", documentId],
    queryFn: () => getDocument(documentId),
    refetchInterval: (query) => {
      const doc = query.state.data;
      return doc && INGESTING_STATUSES.includes(doc.status) ? 2000 : false;
    },
  });

  const quizzesQuery = useQuery({
    queryKey: ["quizzes", documentId],
    queryFn: () => listQuizzes(documentId),
    enabled: documentQuery.data?.status === "READY",
  });

  const requestMutation = useMutation({
    mutationFn: () => requestQuiz(documentId, mode, requestedCount),
  });

  const startMutation = useMutation({
    mutationFn: (quizId: string) => startAttempt(quizId),
    onMutate: (quizId) => setStartingQuizId(quizId),
    onSuccess: (attempt) => navigate(`/quizzes/${attempt.quizId}/attempts/${attempt.id}`),
    onSettled: () => setStartingQuizId(null),
  });

  if (documentQuery.isLoading) {
    return (
      <div className="page stack" aria-busy="true" aria-live="polite">
        <span className="visually-hidden">Loading quizzes…</span>
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
        <h1>Quizzes</h1>
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
          <span>Please try uploading again.</span>
        </div>
      )}

      {doc.status === "READY" && (
        <div className="card stack">
          <h2 style={{ margin: 0 }}>Build a new quiz</h2>

          <div className="stack-sm">
            <div role="group" aria-label="Quiz mode" className="stack-sm">
              {MODES.map((m) => (
                <label
                  key={m.value}
                  className="card"
                  style={{
                    display: "flex",
                    gap: "0.75rem",
                    alignItems: "flex-start",
                    padding: "0.75rem",
                    cursor: "pointer",
                    borderColor: mode === m.value ? "var(--ink)" : undefined,
                  }}
                >
                  <input
                    type="radio"
                    name="quiz-mode"
                    value={m.value}
                    checked={mode === m.value}
                    onChange={() => setMode(m.value)}
                  />
                  <span>
                    <strong>{m.label}</strong>
                    <br />
                    <span className="hint">{m.hint}</span>
                  </span>
                </label>
              ))}
            </div>

            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: "1rem", flexWrap: "wrap" }}>
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
                {requestMutation.isPending ? "Starting…" : "Build quiz"}
              </button>
            </div>
          </div>

          {requestMutation.isError && (
            <div className="error-banner" role="alert">
              <strong>Couldn&apos;t start quiz build</strong>
              <span>{quizErrorMessageFor(requestMutation.error)}</span>
            </div>
          )}

          {requestMutation.data && (
            <JobProgress
              jobId={requestMutation.data.jobId}
              label="Quiz build"
              onTerminal={(job) => {
                if (job.status === "SUCCEEDED") {
                  void queryClient.invalidateQueries({ queryKey: ["quizzes", documentId] });
                }
              }}
            />
          )}

          {quizzesQuery.isLoading && (
            <div className="skeleton" style={{ height: 80 }} aria-busy="true" aria-live="polite" />
          )}

          {quizzesQuery.data && quizzesQuery.data.length === 0 && !requestMutation.data && (
            <p className="hint">No quizzes yet. Build one above.</p>
          )}

          {quizzesQuery.data && quizzesQuery.data.length > 0 && (
            <div className="stack">
              <h2 style={{ margin: 0 }}>Your quizzes</h2>
              {quizzesQuery.data.map((quiz) => (
                <QuizCard
                  key={quiz.id}
                  quiz={quiz}
                  starting={startingQuizId === quiz.id && startMutation.isPending}
                  onStart={() => startMutation.mutate(quiz.id)}
                />
              ))}
              {startMutation.isError && (
                <div className="error-banner" role="alert">
                  <strong>Couldn&apos;t start this attempt</strong>
                  <span>{quizErrorMessageFor(startMutation.error)}</span>
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function QuizCard({ quiz, starting, onStart }: { quiz: QuizResponse; starting: boolean; onStart: () => void }) {
  const navigate = useNavigate();
  const attemptsQuery = useQuery({
    queryKey: ["quiz-attempts", quiz.id],
    queryFn: () => listAttempts(quiz.id),
  });

  return (
    <article className="card stack-sm">
      <div style={{ display: "flex", justifyContent: "space-between", gap: "1rem", alignItems: "baseline", flexWrap: "wrap" }}>
        <strong>
          {quiz.mode} · {quiz.questionCount} question(s)
        </strong>
        <span className="hint numeral">
          {quiz.timeLimitSeconds != null ? `${Math.round(quiz.timeLimitSeconds / 60)} min` : "Untimed"}
          {quiz.negativeMarkingFraction > 0 ? ` · −0.25/wrong` : ""}
        </span>
      </div>
      <button type="button" className="button button-primary" disabled={starting} onClick={onStart}>
        {starting ? "Starting…" : "Start attempt"}
      </button>

      {attemptsQuery.data && attemptsQuery.data.length > 0 && (
        <ul style={{ listStyle: "none", margin: 0, padding: 0 }} className="stack-sm">
          {attemptsQuery.data.map((attempt) => (
            <AttemptRow key={attempt.id} attempt={attempt} onResume={() => navigate(`/quizzes/${quiz.id}/attempts/${attempt.id}`)} />
          ))}
        </ul>
      )}
    </article>
  );
}

function AttemptRow({ attempt, onResume }: { attempt: QuizAttemptResponse; onResume: () => void }) {
  const navigate = useNavigate();
  return (
    <li className="card" style={{ padding: "0.5rem 0.75rem", display: "flex", justifyContent: "space-between", alignItems: "center", gap: "0.5rem" }}>
      <span className="hint numeral">{new Date(attempt.startedAt).toLocaleString()}</span>
      {attempt.status === "IN_PROGRESS" ? (
        <button type="button" className="button button-secondary" onClick={onResume}>
          Resume
        </button>
      ) : (
        <button
          type="button"
          className="button button-secondary"
          onClick={() => navigate(`/quiz-attempts/${attempt.id}/result`)}
        >
          {attempt.status === "EXPIRED" ? "View (expired)" : "View result"} · {attempt.score ?? "—"}/{attempt.maxScore ?? "—"}
        </button>
      )}
    </li>
  );
}

function quizErrorMessageFor(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "DOCUMENT_NOT_READY":
        return "This document is still being processed.";
      case "AUTH_GUARDIAN_CONSENT_REQUIRED":
        return "Building quizzes requires guardian consent for accounts under 18.";
      case "QUOTA_AI_EXCEEDED":
        return "You've hit this month's AI generation limit.";
      case "QUIZ_NOT_FOUND":
        return "This quiz could not be found.";
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
