import { apiRequest, newIdempotencyKey } from "./client";

export interface SummaryJobResponse {
  jobId: string;
}

export interface Citation {
  chunkId: string;
}

export interface SummaryResponse {
  id: string;
  documentId: string;
  summaryType: string;
  contentMd: string;
  citations: Citation[];
  model: string;
  createdAt: string;
}

export function requestSummary(documentId: string): Promise<SummaryJobResponse> {
  return apiRequest<SummaryJobResponse>(`/documents/${documentId}/summaries`, {
    method: "POST",
    idempotencyKey: newIdempotencyKey(),
  });
}

export function listSummaries(documentId: string): Promise<SummaryResponse[]> {
  return apiRequest<SummaryResponse[]>(`/documents/${documentId}/summaries`);
}
