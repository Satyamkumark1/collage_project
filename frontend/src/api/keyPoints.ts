import { apiRequest, newIdempotencyKey } from "./client";

export interface KeyPointJobResponse {
  jobId: string;
}

export interface KeyPointCitation {
  chunkId: string;
  pageFrom: number | null;
  pageTo: number | null;
  sectionPath: string | null;
}

export interface KeyPointResponse {
  id: string;
  documentId: string;
  category: string;
  label: string;
  contentMd: string;
  citations: KeyPointCitation[];
  model: string;
  createdAt: string;
}

export function requestKeyPoints(documentId: string): Promise<KeyPointJobResponse> {
  return apiRequest<KeyPointJobResponse>(`/documents/${documentId}/key-points`, {
    method: "POST",
    idempotencyKey: newIdempotencyKey(),
  });
}

export function listKeyPoints(documentId: string): Promise<KeyPointResponse[]> {
  return apiRequest<KeyPointResponse[]>(`/documents/${documentId}/key-points`);
}
