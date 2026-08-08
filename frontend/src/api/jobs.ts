import { apiRequest } from "./client";

export interface JobResponse {
  id: string;
  taskType: string;
  status: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
  progressPct: number;
  progressStage: string | null;
  resultRef: Record<string, unknown> | null;
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}

export function getJob(id: string): Promise<JobResponse> {
  return apiRequest<JobResponse>(`/jobs/${id}`);
}

export function isTerminal(status: JobResponse["status"]): boolean {
  return status === "SUCCEEDED" || status === "FAILED" || status === "CANCELLED";
}
