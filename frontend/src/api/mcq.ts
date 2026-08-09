import { apiRequest, newIdempotencyKey } from "./client";
import type { Citation } from "../components/CitationList";

export interface QuestionSetJobResponse {
  jobId: string;
}

export interface DifficultyMix {
  EASY: number;
  MEDIUM: number;
  HARD: number;
}

export interface QuestionSetResponse {
  id: string;
  documentId: string;
  requestedCount: number;
  generatedCount: number;
  difficultyMix: DifficultyMix;
  model: string;
  createdAt: string;
}

export interface QuestionResponse {
  id: string;
  questionSetId: string;
  stem: string;
  options: string[];
  correctIndex: number;
  explanation: string;
  difficulty: "EASY" | "MEDIUM" | "HARD";
  bloomLevel: "REMEMBER" | "UNDERSTAND" | "APPLY" | "ANALYZE";
  citations: Citation[];
}

export function requestQuestionSet(documentId: string, requestedCount: number): Promise<QuestionSetJobResponse> {
  return apiRequest<QuestionSetJobResponse>(`/documents/${documentId}/question-sets`, {
    method: "POST",
    body: { requestedCount },
    idempotencyKey: newIdempotencyKey(),
  });
}

export function listQuestionSets(documentId: string): Promise<QuestionSetResponse[]> {
  return apiRequest<QuestionSetResponse[]>(`/documents/${documentId}/question-sets`);
}

export function listQuestions(questionSetId: string): Promise<QuestionResponse[]> {
  return apiRequest<QuestionResponse[]>(`/question-sets/${questionSetId}/questions`);
}
