import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";
import { ApiError } from "../api/client";
import {
  getAttempt,
  getQuiz,
  getQuizQuestions,
  listAnswers,
  saveAnswer,
  submitAttempt,
} from "../api/quizzes";

const OPTION_LETTERS = ["A", "B", "C", "D"];

export function QuizAttempt() {
  const { quizId, attemptId } = useParams<{ quizId: string; attemptId: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [currentIndex, setCurrentIndex] = useState(0);
  const [answers, setAnswers] = useState<Record<string, number | null>>({});
  const [revisionFeedback, setRevisionFeedback] = useState<Record<string, { isCorrect: boolean; explanation: string }>>({});
  const [confirmingSubmit, setConfirmingSubmit] = useState(false);
  const [nowTick, setNowTick] = useState(() => Date.now());
  const seededRef = useRef(false);
  const autoRecheckedRef = useRef(false);

  const quizQuery = useQuery({ queryKey: ["quiz", quizId], queryFn: () => getQuiz(quizId!) });
  const questionsQuery = useQuery({ queryKey: ["quiz-questions", quizId], queryFn: () => getQuizQuestions(quizId!) });
  const attemptQuery = useQuery({
    queryKey: ["quiz-attempt", attemptId],
    queryFn: () => getAttempt(attemptId!),
    refetchInterval: (query) => (query.state.data?.status === "IN_PROGRESS" ? 15_000 : false),
  });
  const existingAnswersQuery = useQuery({
    queryKey: ["quiz-attempt-answers", attemptId],
    queryFn: () => listAnswers(attemptId!),
  });

  const attempt = attemptQuery.data;
  const quiz = quizQuery.data;
  const questions = questionsQuery.data;

  // Seed local answer state once from what's already saved server-side — resuming after a page
  // reload should show the student's own prior picks, not a blank sheet. Only fills in questions
  // not already present locally: this query can resolve after the student has already answered
  // (or explicitly cleared, a legitimate local `null`) a question via saveAnswerMutation, and a
  // stale snapshot must never overwrite that — hence an `in` key-presence check, not a truthiness
  // check, so a locally-cleared `null` selection isn't mistaken for "never seeded".
  useEffect(() => {
    if (seededRef.current || !existingAnswersQuery.data) {
      return;
    }
    seededRef.current = true;
    setAnswers((prev) => {
      const next = { ...prev };
      for (const a of existingAnswersQuery.data) {
        if (!(a.questionId in next)) {
          next[a.questionId] = a.selectedIndex;
        }
      }
      return next;
    });
  }, [existingAnswersQuery.data]);

  // Already-terminal attempt (submitted, expired, or a stale/shared link) — go straight to the
  // result view rather than showing a taking UI with nothing left to take.
  useEffect(() => {
    if (attempt && attempt.status !== "IN_PROGRESS") {
      navigate(`/quiz-attempts/${attemptId}/result`, { replace: true });
    }
  }, [attempt, attemptId, navigate]);

  useEffect(() => {
    if (!attempt?.deadlineAt) {
      return;
    }
    const interval = setInterval(() => setNowTick(Date.now()), 1000);
    return () => clearInterval(interval);
  }, [attempt?.deadlineAt]);

  const remainingSeconds = attempt?.deadlineAt
    ? Math.max(0, Math.floor((new Date(attempt.deadlineAt).getTime() - nowTick) / 1000))
    : null;
  const isOvertime = remainingSeconds === 0 && quiz?.mode === "PRACTICE";

  const submitMutation = useMutation({
    mutationFn: () => submitAttempt(attemptId!),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["quiz-attempts", quizId] });
      navigate(`/quiz-attempts/${attemptId}/result`, { replace: true });
    },
  });

  // Server-authoritative timing: the countdown here is just a display computed from the
  // server-issued deadlineAt — it must never be the thing that decides an attempt is over.
  // Calling submit() here would risk a premature lock-out if this browser's clock runs fast
  // (submit() always accepts and finalizes an in-progress attempt, so an early call — before the
  // server's own clock agrees the deadline has passed — would wrongly end the attempt as
  // SUBMITTED). Refetching the attempt instead is safe either way: get() re-checks the deadline
  // against the server's own clock and only finalizes as EXPIRED if the server agrees; if it
  // doesn't yet, this is a harmless no-op and the attempt stays IN_PROGRESS. Once the server does
  // finalize it, the redirect-to-result effect above fires on the next render.
  useEffect(() => {
    if (
      quiz?.mode === "EXAM" &&
      attempt?.status === "IN_PROGRESS" &&
      remainingSeconds === 0 &&
      !autoRecheckedRef.current
    ) {
      autoRecheckedRef.current = true;
      void attemptQuery.refetch();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [remainingSeconds, quiz?.mode, attempt?.status]);

  const saveAnswerMutation = useMutation({
    mutationFn: ({ questionId, selectedIndex }: { questionId: string; selectedIndex: number | null }) =>
      saveAnswer(attemptId!, questionId, selectedIndex),
    onSuccess: (response) => {
      setAnswers((prev) => ({ ...prev, [response.questionId]: response.selectedIndex }));
      if (response.isCorrect != null && response.explanation != null) {
        setRevisionFeedback((prev) => ({
          ...prev,
          [response.questionId]: { isCorrect: response.isCorrect!, explanation: response.explanation! },
        }));
      }
    },
    onError: (error) => {
      if (error instanceof ApiError && error.code === "QUIZ_ATTEMPT_EXPIRED") {
        navigate(`/quiz-attempts/${attemptId}/result`, { replace: true });
      }
    },
  });

  if (quizQuery.isLoading || questionsQuery.isLoading || attemptQuery.isLoading) {
    return (
      <div className="page stack" aria-busy="true" aria-live="polite">
        <span className="visually-hidden">Loading quiz…</span>
        <div className="skeleton" style={{ height: 32, width: "60%" }} />
        <div className="skeleton" style={{ height: 200 }} />
      </div>
    );
  }

  if (quizQuery.isError || questionsQuery.isError || attemptQuery.isError) {
    return (
      <div className="page">
        <div className="error-banner" role="alert">
          <strong>Couldn&apos;t load this quiz</strong>
          <span>Please go back and try again.</span>
        </div>
      </div>
    );
  }

  if (!quiz || !questions || questions.length === 0 || !attempt || attempt.status !== "IN_PROGRESS") {
    return null;
  }

  const answeredCount = questions.filter((q) => answers[q.id] != null).length;
  const currentQuestion = questions[currentIndex];
  const selected = answers[currentQuestion.id] ?? null;
  const feedback = revisionFeedback[currentQuestion.id];
  const isSaving = saveAnswerMutation.isPending && saveAnswerMutation.variables?.questionId === currentQuestion.id;

  return (
    <div className="page stack">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: "1rem", flexWrap: "wrap" }}>
        <div>
          <h1>{quiz.mode} quiz</h1>
          <p className="hint numeral">
            Question {currentIndex + 1} of {questions.length} · {answeredCount} answered
          </p>
        </div>
        {remainingSeconds != null && (
          <div style={{ textAlign: "right" }}>
            <div
              className={
                "quiz-timer" +
                (remainingSeconds <= 60 && !isOvertime ? " quiz-timer-critical" : "") +
                (isOvertime ? " quiz-timer-overtime" : "")
              }
              aria-live="polite"
            >
              {isOvertime ? "Overtime" : formatClock(remainingSeconds)}
            </div>
            <span className="hint">{quiz.mode === "EXAM" ? "Auto-submits at zero" : "Not enforced"}</span>
          </div>
        )}
      </div>

      <nav className="quiz-question-strip" aria-label="Jump to question">
        {questions.map((q, i) => (
          <button
            key={q.id}
            type="button"
            className={
              "quiz-question-dot" + (answers[q.id] != null ? " answered" : "") + (i === currentIndex ? " current" : "")
            }
            aria-current={i === currentIndex}
            onClick={() => setCurrentIndex(i)}
          >
            {i + 1}
          </button>
        ))}
      </nav>

      <article className="card stack">
        <strong style={{ fontFamily: "var(--font-display)", fontSize: "1.1rem" }}>{currentQuestion.stem}</strong>

        <div className="stack-sm" role="radiogroup" aria-label={`Options for question ${currentIndex + 1}`}>
          {currentQuestion.options.map((option, optionIndex) => {
            const isSelected = selected === optionIndex;
            const revealClass = feedback
              ? optionIndex === selected
                ? feedback.isCorrect
                  ? " correct"
                  : " incorrect"
                : ""
              : "";
            return (
              <label key={optionIndex} className={"omr-option" + (isSelected ? " selected" : "")}>
                <input
                  type="radio"
                  name={`question-${currentQuestion.id}`}
                  checked={isSelected}
                  disabled={saveAnswerMutation.isPending}
                  onChange={() =>
                    saveAnswerMutation.mutate({ questionId: currentQuestion.id, selectedIndex: optionIndex })
                  }
                />
                <span className={"omr-bubble" + revealClass}>{OPTION_LETTERS[optionIndex]}</span>
                <span>{option}</span>
              </label>
            );
          })}
        </div>

        {isSaving && <span className="hint">Saving…</span>}
        {feedback && (
          <p className="hint" style={{ color: feedback.isCorrect ? "var(--check)" : "var(--red-pen)" }}>
            {feedback.isCorrect ? "Correct. " : "Not quite. "}
            {feedback.explanation}
          </p>
        )}
        {selected != null && !feedback && (
          <button
            type="button"
            className="button button-secondary"
            disabled={saveAnswerMutation.isPending}
            onClick={() => saveAnswerMutation.mutate({ questionId: currentQuestion.id, selectedIndex: null })}
          >
            Clear answer
          </button>
        )}

        <div style={{ display: "flex", justifyContent: "space-between", gap: "1rem" }}>
          <button
            type="button"
            className="button button-secondary"
            disabled={currentIndex === 0}
            onClick={() => setCurrentIndex((i) => Math.max(0, i - 1))}
          >
            Previous
          </button>
          <button
            type="button"
            className="button button-secondary"
            disabled={currentIndex === questions.length - 1}
            onClick={() => setCurrentIndex((i) => Math.min(questions.length - 1, i + 1))}
          >
            Next
          </button>
        </div>
      </article>

      {confirmingSubmit && answeredCount < questions.length && (
        <div className="partial-banner" role="status">
          <strong>{questions.length - answeredCount} question(s) unanswered</strong>
          <span>Unanswered questions score zero. Submit anyway, or go back and finish them?</span>
          <div style={{ display: "flex", gap: "0.5rem" }}>
            <button
              type="button"
              className="button button-primary"
              disabled={submitMutation.isPending || saveAnswerMutation.isPending}
              onClick={() => submitMutation.mutate()}
            >
              Submit anyway
            </button>
            <button type="button" className="button button-secondary" onClick={() => setConfirmingSubmit(false)}>
              Keep going
            </button>
          </div>
        </div>
      )}

      {submitMutation.isError && (
        <div className="error-banner" role="alert">
          <strong>Couldn&apos;t submit</strong>
          <span>Please try again.</span>
        </div>
      )}

      <button
        type="button"
        className="button button-primary button-block"
        disabled={submitMutation.isPending || saveAnswerMutation.isPending}
        onClick={() => {
          if (answeredCount < questions.length) {
            setConfirmingSubmit(true);
          } else {
            submitMutation.mutate();
          }
        }}
      >
        {submitMutation.isPending ? "Submitting…" : "Submit quiz"}
      </button>
    </div>
  );
}

function formatClock(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}
