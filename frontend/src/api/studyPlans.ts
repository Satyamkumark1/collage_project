import { apiRequest, API_BASE, getAccessToken } from "./client";

export interface StudySession {
  id: string;
  scheduledDate: string;
}

export interface StudyPlanResponse {
  id: string;
  documentId: string;
  examDate: string;
  createdAt: string;
  sessions: StudySession[];
}

// Synchronous — no LLM call, pure scheduling arithmetic. See docs/DECISIONS.md.
export function createStudyPlan(documentId: string, examDate: string): Promise<StudyPlanResponse> {
  return apiRequest<StudyPlanResponse>(`/documents/${documentId}/study-plans`, {
    method: "POST",
    body: { examDate },
  });
}

export function listStudyPlans(documentId: string): Promise<StudyPlanResponse[]> {
  return apiRequest<StudyPlanResponse[]>(`/documents/${documentId}/study-plans`);
}

// Bearer-token auth can't ride a plain <a href> download, so this fetches the .ics text directly
// and triggers a client-side Blob download instead.
export async function downloadIcs(planId: string): Promise<void> {
  const response = await fetch(`${API_BASE}/study-plans/${planId}/export.ics`, {
    headers: { Authorization: `Bearer ${getAccessToken()}` },
    credentials: "include",
  });
  if (!response.ok) {
    throw new Error(`Export failed with status ${response.status}`);
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = "study-plan.ics";
  link.click();
  URL.revokeObjectURL(url);
}
