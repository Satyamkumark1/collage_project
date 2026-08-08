import { apiRequest, newIdempotencyKey } from "./client";

export interface UploadResponse {
  documentId: string;
  jobId: string | null;
}

export interface DocumentResponse {
  id: string;
  title: string;
  originalFilename: string;
  mimeType: string;
  fileType: string;
  sizeBytes: number;
  pageCount: number | null;
  charCount: number | null;
  status: "UPLOADED" | "PARSING" | "CHUNKING" | "EMBEDDING" | "READY" | "FAILED";
  failureCode: string | null;
  failureDetail: string | null;
  createdAt: string;
}

export interface DocumentListResponse {
  items: DocumentResponse[];
  nextCursor: string | null;
}

export function uploadDocument(file: File, title?: string): Promise<UploadResponse> {
  const formData = new FormData();
  formData.append("file", file);
  if (title) {
    formData.append("title", title);
  }
  return apiRequest<UploadResponse>("/documents", {
    method: "POST",
    body: formData,
    isFormData: true,
    idempotencyKey: newIdempotencyKey(),
  });
}

export function listDocuments(cursor?: string): Promise<DocumentListResponse> {
  const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : "";
  return apiRequest<DocumentListResponse>(`/documents${query}`);
}

export function getDocument(id: string): Promise<DocumentResponse> {
  return apiRequest<DocumentResponse>(`/documents/${id}`);
}

export function deleteDocument(id: string): Promise<void> {
  return apiRequest<void>(`/documents/${id}`, { method: "DELETE" });
}
