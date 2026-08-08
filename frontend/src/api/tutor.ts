import { API_BASE, apiRequest, getAccessToken } from "./client";

export interface ConversationResponse {
  id: string;
  documentId: string;
  createdAt: string;
}

export interface ConversationListResponse {
  items: ConversationResponse[];
  nextCursor: string | null;
}

export interface TutorCitation {
  marker: number;
  chunkId: string;
  pageFrom: number | null;
  pageTo: number | null;
  sectionPath: string | null;
}

export interface MessageResponse {
  id: string;
  role: "USER" | "ASSISTANT";
  content: string;
  citations: TutorCitation[] | null;
  grounded: boolean | null;
  beyondNotes: boolean | null;
  createdAt: string;
}

export interface DoneEventPayload {
  messageId: string;
  citations: TutorCitation[];
  grounded: boolean;
  beyondNotes: boolean;
}

export function createConversation(documentId: string): Promise<ConversationResponse> {
  return apiRequest<ConversationResponse>(`/documents/${documentId}/conversations`, { method: "POST" });
}

export function listConversations(documentId: string): Promise<ConversationListResponse> {
  return apiRequest<ConversationListResponse>(`/documents/${documentId}/conversations`);
}

export function getConversationMessages(conversationId: string): Promise<MessageResponse[]> {
  return apiRequest<MessageResponse[]>(`/conversations/${conversationId}/messages`);
}

export interface StreamCallbacks {
  onToken: (delta: string) => void;
  onDone: (payload: DoneEventPayload) => void;
  onError: (code: string) => void;
}

/**
 * The tutor endpoint streams `text/event-stream` over a plain POST — native `EventSource`
 * doesn't support POST bodies or an `Authorization` header, so this reads the response body
 * directly via `fetch` + `ReadableStream` instead (see specs/11-frontend.md, docs/DECISIONS.md).
 * Buffers on the `\n\n` event boundary the server writes between SSE frames.
 */
export async function streamTutorMessage(
  conversationId: string,
  content: string,
  explainBeyondNotes: boolean,
  callbacks: StreamCallbacks,
): Promise<void> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    "X-Request-Id": crypto.randomUUID(),
  };
  const token = getAccessToken();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  let response: Response;
  try {
    response = await fetch(`${API_BASE}/conversations/${conversationId}/messages`, {
      method: "POST",
      headers,
      credentials: "include",
      body: JSON.stringify({ content, explainBeyondNotes }),
    });
  } catch {
    callbacks.onError("NETWORK_ERROR");
    return;
  }

  if (!response.ok || !response.body) {
    callbacks.onError(await errorCodeFrom(response));
    return;
  }

  try {
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    for (;;) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      let boundary = buffer.indexOf("\n\n");
      while (boundary !== -1) {
        dispatchSseEvent(buffer.slice(0, boundary), callbacks);
        buffer = buffer.slice(boundary + 2);
        boundary = buffer.indexOf("\n\n");
      }
    }
    if (buffer.trim().length > 0) {
      dispatchSseEvent(buffer, callbacks);
    }
  } catch {
    callbacks.onError("NETWORK_ERROR");
  }
}

async function errorCodeFrom(response: Response): Promise<string> {
  try {
    const problem = (await response.json()) as { code?: string };
    return problem.code ?? "UNKNOWN";
  } catch {
    return "UNKNOWN";
  }
}

function dispatchSseEvent(rawEvent: string, callbacks: StreamCallbacks): void {
  let name = "";
  let data = "";
  for (const line of rawEvent.split("\n")) {
    if (line.startsWith("event:")) {
      name = line.slice("event:".length).trim();
    } else if (line.startsWith("data:")) {
      data += line.slice("data:".length).trim();
    }
  }
  if (!name || !data) {
    return;
  }
  try {
    if (name === "token") {
      const parsed = JSON.parse(data) as { delta: string };
      callbacks.onToken(parsed.delta);
    } else if (name === "done") {
      callbacks.onDone(JSON.parse(data) as DoneEventPayload);
    } else if (name === "error") {
      const parsed = JSON.parse(data) as { code: string };
      callbacks.onError(parsed.code);
    }
  } catch {
    // A malformed event is dropped rather than crashing the whole stream — the "done" event
    // (or the fetch failing outright) still terminates the exchange either way.
  }
}
