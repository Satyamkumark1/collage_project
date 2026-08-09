import { apiRequest, newIdempotencyKey } from "./client";
import type { Citation } from "../components/CitationList";

export interface FlashcardJobResponse {
  jobId: string;
}

export interface FlashcardResponse {
  id: string;
  documentId: string;
  frontMd: string;
  backMd: string;
  citations: Citation[];
  easeFactor: number;
  intervalDays: number;
  repetitions: number;
  dueAt: string;
  lastReviewedAt: string | null;
  lastQuality: number | null;
  model: string;
  createdAt: string;
}

export function requestFlashcards(documentId: string): Promise<FlashcardJobResponse> {
  return apiRequest<FlashcardJobResponse>(`/documents/${documentId}/flashcards`, {
    method: "POST",
    idempotencyKey: newIdempotencyKey(),
  });
}

export function listFlashcards(documentId: string): Promise<FlashcardResponse[]> {
  return apiRequest<FlashcardResponse[]>(`/documents/${documentId}/flashcards`);
}

export function listDueFlashcards(limit = 20): Promise<FlashcardResponse[]> {
  return apiRequest<FlashcardResponse[]>(`/flashcards/due?limit=${limit}`);
}

export function reviewFlashcard(id: string, quality: number): Promise<FlashcardResponse> {
  return apiRequest<FlashcardResponse>(`/flashcards/${id}/review`, {
    method: "POST",
    body: { quality },
  });
}
