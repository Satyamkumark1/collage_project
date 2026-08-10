import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { getQuiz, getResult } from "../api/quizzes";
import { CitationList } from "../components/CitationList";

const OPTION_LETTERS = ["A", "B", "C", "D"];

export function QuizResult() {
  const { attemptId } = useParams<{ attemptId: string }>();

  const resultQuery = useQuery({ queryKey: ["quiz-result", attemptId], queryFn: () => getResult(attemptId!) });
  const quizId = resultQuery.data?.attempt.quizId;
  const quizQuery = useQuery({
    queryKey: ["quiz", quizId],
    queryFn: () => getQuiz(quizId!),
    enabled: !!quizId,
  });

  if (resultQuery.isLoading) {
    return (
      <div className="page stack" aria-busy="true" aria-live="polite">
        <span className="visually-hidden">Loading result…</span>
        <div className="skeleton" style={{ height: 32, width: "60%" }} />
        <div className="skeleton" style={{ height: 200 }} />
      </div>
    );
  }

  if (resultQuery.isError || !resultQuery.data) {
    return (
      <div className="page">
        <div className="error-banner" role="alert">
          <strong>Couldn&apos;t load this result</strong>
          <span>The attempt may still be in progress, or this link may be invalid.</span>
        </div>
      </div>
    );
  }

  const { attempt, questions } = resultQuery.data;

  return (
    <div className="page stack">
      <div>
        <h1>Result</h1>
        <p className="hint">
          {attempt.status === "EXPIRED" ? "Time ran out — scored from what was saved." : "Submitted."}
        </p>
      </div>

      <div className="card stack-sm">
        <span className="quiz-timer" aria-label="Score">
          {attempt.score ?? 0} / {attempt.maxScore ?? questions.length}
        </span>
        <span className="hint numeral">
          {attempt.correctCount ?? 0} correct · {attempt.incorrectCount ?? 0} wrong · {attempt.unansweredCount ?? 0} unanswered
        </span>
        {quizQuery.data && (
          <Link to={`/documents/${quizQuery.data.documentId}/quizzes`} className="button button-secondary">
            Back to quizzes
          </Link>
        )}
      </div>

      <div className="stack">
        {questions.map((question, index) => (
          <article key={question.id} className="card stack-sm">
            <strong>
              {index + 1}. {question.stem}
            </strong>
            <ul style={{ listStyle: "none", margin: 0, padding: 0 }} className="stack-sm">
              {question.options.map((option, optionIndex) => {
                const isCorrectOption = optionIndex === question.correctIndex;
                const isSelected = optionIndex === question.selectedIndex;
                const bubbleClass = isCorrectOption ? " correct" : isSelected ? " incorrect" : "";
                return (
                  <li
                    key={optionIndex}
                    className={"omr-option" + (isSelected ? " selected" : "")}
                    style={{ cursor: "default" }}
                  >
                    <span className={"omr-bubble" + bubbleClass}>{OPTION_LETTERS[optionIndex]}</span>
                    <span>{option}</span>
                  </li>
                );
              })}
            </ul>
            {question.selectedIndex == null && <p className="hint">Not answered.</p>}
            <p className="hint">{question.explanation}</p>
            <CitationList citations={question.citations} />
          </article>
        ))}
      </div>
    </div>
  );
}
