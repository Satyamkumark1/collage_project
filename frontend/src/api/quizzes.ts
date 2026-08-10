import { apiRequest, newIdempotencyKey } from "./client";
import type { Citation } from "../components/CitationList";

export type QuizMode = "PRACTICE" | "EXAM" | "REVISION";
export type QuizAttemptStatus = "IN_PROGRESS" | "SUBMITTED" | "EXPIRED";

export interface QuizJobResponse {
  jobId: string;
}

export interface QuizResponse {
  id: string;
  documentId: string;
  questionSetId: string;
  mode: QuizMode;
  questionCount: number;
  timeLimitSeconds: number | null;
  negativeMarkingFraction: number;
  createdAt: string;
}

/** Deliberately answer-key-free — see docs/DECISIONS.md. */
export interface QuizQuestionResponse {
  id: string;
  quizId: string;
  stem: string;
  options: string[];
}

export interface QuizAttemptResponse {
  id: string;
  quizId: string;
  status: QuizAttemptStatus;
  startedAt: string;
  deadlineAt: string | null;
  submittedAt: string | null;
  score: number | null;
  maxScore: number | null;
  correctCount: number | null;
  incorrectCount: number | null;
  unansweredCount: number | null;
}

export interface AnswerResponse {
  questionId: string;
  selectedIndex: number | null;
  answeredAt: string;
  isCorrect: boolean | null;
  explanation: string | null;
}

export interface QuizResultQuestion {
  id: string;
  stem: string;
  options: string[];
  correctIndex: number;
  explanation: string;
  citations: Citation[];
  selectedIndex: number | null;
  isCorrect: boolean | null;
}

export interface QuizResultResponse {
  attempt: QuizAttemptResponse;
  questions: QuizResultQuestion[];
}

export function requestQuiz(documentId: string, mode: QuizMode, requestedCount: number): Promise<QuizJobResponse> {
  return apiRequest<QuizJobResponse>(`/documents/${documentId}/quizzes`, {
    method: "POST",
    body: { mode, requestedCount },
    idempotencyKey: newIdempotencyKey(),
  });
}

export function listQuizzes(documentId: string): Promise<QuizResponse[]> {
  return apiRequest<QuizResponse[]>(`/documents/${documentId}/quizzes`);
}

export function getQuiz(id: string): Promise<QuizResponse> {
  return apiRequest<QuizResponse>(`/quizzes/${id}`);
}

export function getQuizQuestions(id: string): Promise<QuizQuestionResponse[]> {
  return apiRequest<QuizQuestionResponse[]>(`/quizzes/${id}/questions`);
}

export function startAttempt(quizId: string): Promise<QuizAttemptResponse> {
  return apiRequest<QuizAttemptResponse>(`/quizzes/${quizId}/attempts`, { method: "POST" });
}

export function listAttempts(quizId: string): Promise<QuizAttemptResponse[]> {
  return apiRequest<QuizAttemptResponse[]>(`/quizzes/${quizId}/attempts`);
}

export function getAttempt(id: string): Promise<QuizAttemptResponse> {
  return apiRequest<QuizAttemptResponse>(`/quiz-attempts/${id}`);
}

/** The student's own previously saved picks — used to resume an in-progress attempt after a
 * page reload. Never the answer key (isCorrect/explanation are always null here). */
export function listAnswers(attemptId: string): Promise<AnswerResponse[]> {
  return apiRequest<AnswerResponse[]>(`/quiz-attempts/${attemptId}/answers`);
}

export function saveAnswer(attemptId: string, questionId: string, selectedIndex: number | null): Promise<AnswerResponse> {
  return apiRequest<AnswerResponse>(`/quiz-attempts/${attemptId}/answers/${questionId}`, {
    method: "PUT",
    body: { selectedIndex },
  });
}

export function submitAttempt(attemptId: string): Promise<QuizResultResponse> {
  return apiRequest<QuizResultResponse>(`/quiz-attempts/${attemptId}/submit`, { method: "POST" });
}

export function getResult(attemptId: string): Promise<QuizResultResponse> {
  return apiRequest<QuizResultResponse>(`/quiz-attempts/${attemptId}/result`);
}
