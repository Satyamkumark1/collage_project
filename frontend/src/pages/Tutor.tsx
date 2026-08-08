import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ApiError } from "../api/client";
import { getDocument } from "../api/documents";
import {
  createConversation,
  getConversationMessages,
  listConversations,
  streamTutorMessage,
  type ConversationResponse,
  type MessageResponse,
  type TutorCitation,
} from "../api/tutor";
import { TutorCitations } from "../components/TutorCitations";

interface PendingExchange {
  conversationId: string;
  userContent: string;
  assistantText: string;
  status: "streaming" | "error";
  errorCode?: string;
}

export function Tutor() {
  const { id } = useParams<{ id: string }>();
  const documentId = id!;
  const queryClient = useQueryClient();

  const [activeConversationId, setActiveConversationId] = useState<string | null>(null);
  const [explainBeyondNotes, setExplainBeyondNotes] = useState(false);
  const [inputValue, setInputValue] = useState("");
  const [pending, setPending] = useState<PendingExchange | null>(null);
  const threadEndRef = useRef<HTMLDivElement>(null);
  const autoCreatedRef = useRef(false);

  const documentQuery = useQuery({
    queryKey: ["document", documentId],
    queryFn: () => getDocument(documentId),
  });

  const conversationsQuery = useQuery({
    queryKey: ["conversations", documentId],
    queryFn: () => listConversations(documentId),
    enabled: documentQuery.data?.status === "READY",
  });

  const createConversationMutation = useMutation({
    mutationFn: () => createConversation(documentId),
    onSuccess: (conversation) => {
      setActiveConversationId(conversation.id);
      void queryClient.invalidateQueries({ queryKey: ["conversations", documentId] });
    },
    onError: () => {
      autoCreatedRef.current = false;
    },
  });

  useEffect(() => {
    const items = conversationsQuery.data?.items;
    if (!items) return;
    if (items.length > 0 && activeConversationId === null) {
      setActiveConversationId(items[0].id);
    } else if (items.length === 0 && !autoCreatedRef.current && !createConversationMutation.isPending) {
      autoCreatedRef.current = true;
      createConversationMutation.mutate();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [conversationsQuery.data]);

  useEffect(() => {
    if (pending && pending.conversationId !== activeConversationId) {
      setPending(null);
    }
  }, [activeConversationId, pending]);

  const messagesQuery = useQuery({
    queryKey: ["tutor-messages", activeConversationId],
    queryFn: () => getConversationMessages(activeConversationId!),
    enabled: activeConversationId !== null,
  });

  useEffect(() => {
    threadEndRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [messagesQuery.data, pending?.assistantText]);

  async function handleSend() {
    const content = inputValue.trim();
    if (!content || !activeConversationId || pending?.status === "streaming") {
      return;
    }
    const conversationId = activeConversationId;
    setInputValue("");
    setPending({ conversationId, userContent: content, assistantText: "", status: "streaming" });

    await streamTutorMessage(conversationId, content, explainBeyondNotes, {
      onToken: (delta) => {
        setPending((prev) =>
          prev && prev.conversationId === conversationId ? { ...prev, assistantText: prev.assistantText + delta } : prev,
        );
      },
      onDone: async () => {
        await queryClient.invalidateQueries({ queryKey: ["tutor-messages", conversationId] });
        setPending((prev) => (prev && prev.conversationId === conversationId ? null : prev));
      },
      onError: (code) => {
        setPending((prev) =>
          prev && prev.conversationId === conversationId ? { ...prev, status: "error", errorCode: code } : prev,
        );
      },
    });
  }

  function handleRetry() {
    if (!pending) return;
    setInputValue(pending.userContent);
    setPending(null);
  }

  if (documentQuery.isLoading) {
    return (
      <div className="page stack" aria-busy="true" aria-live="polite">
        <span className="visually-hidden">Loading document…</span>
        <div className="skeleton" style={{ height: 32, width: "60%" }} />
        <div className="skeleton" style={{ height: 240 }} />
      </div>
    );
  }

  if (documentQuery.isError) {
    return (
      <div className="page">
        <div className="error-banner" role="alert">
          <strong>Couldn&apos;t load this document</strong>
          <button type="button" className="button button-secondary" onClick={() => documentQuery.refetch()}>
            Retry
          </button>
        </div>
      </div>
    );
  }

  const doc = documentQuery.data;
  if (!doc) return null;

  if (doc.status !== "READY") {
    return (
      <div className="page stack">
        <h1>Tutor</h1>
        <div className="empty-state">
          <p>This document is still being processed. The tutor needs ingestion to finish first.</p>
          <Link to={`/documents/${documentId}`} className="button button-secondary">
            Back to document
          </Link>
        </div>
      </div>
    );
  }

  const messages: MessageResponse[] = messagesQuery.data ?? [];

  return (
    <div className="page stack">
      <div>
        <h1>Ask the tutor</h1>
        <p className="hint">
          About <strong>{doc.title}</strong> — answers only from your notes, with citations, unless you turn on
          &ldquo;explain beyond my notes&rdquo;.
        </p>
      </div>

      {(conversationsQuery.data?.items?.length ?? 0) > 1 && (
        <div className="field">
          <label htmlFor="conversation-select">Conversation</label>
          <select
            id="conversation-select"
            className="input"
            value={activeConversationId ?? ""}
            disabled={pending?.status === "streaming"}
            onChange={(e) => setActiveConversationId(e.target.value)}
          >
            {conversationsQuery.data!.items.map((c: ConversationResponse) => (
              <option key={c.id} value={c.id}>
                Chat started {new Date(c.createdAt).toLocaleString()}
              </option>
            ))}
          </select>
        </div>
      )}
      <button
        type="button"
        className="button button-secondary"
        style={{ alignSelf: "flex-start" }}
        disabled={createConversationMutation.isPending || pending?.status === "streaming"}
        onClick={() => {
          autoCreatedRef.current = true;
          createConversationMutation.mutate();
        }}
      >
        + New chat
      </button>

      {createConversationMutation.isError && (
        <div className="error-banner" role="alert">
          <strong>Couldn&apos;t start a new chat</strong>
          <span>{conversationErrorMessageFor(createConversationMutation.error)}</span>
        </div>
      )}

      <div className="card stack">
        {(messagesQuery.isLoading || (activeConversationId === null && !createConversationMutation.isError)) && (
          <div className="skeleton" style={{ height: 160 }} aria-busy="true" aria-live="polite" />
        )}

        {messagesQuery.isError && (
          <div className="error-banner" role="alert">
            <strong>Couldn&apos;t load this conversation</strong>
            <button type="button" className="button button-secondary" onClick={() => messagesQuery.refetch()}>
              Retry
            </button>
          </div>
        )}

        {activeConversationId !== null && !messagesQuery.isLoading && !messagesQuery.isError && (
          <>
            {messages.length === 0 && !pending && (
              <p className="hint">Ask anything about this document to get started.</p>
            )}

            <div className="chat-thread" tabIndex={0} aria-label="Tutor conversation messages">
              {messages.map((message) => (
                <ChatBubble key={message.id} message={message} />
              ))}

              {pending && (
                <>
                  <div className="chat-bubble chat-bubble-user">{pending.userContent}</div>
                  {pending.status === "streaming" && (
                    <div className="chat-bubble chat-bubble-assistant" aria-live="polite">
                      <span className={pending.assistantText ? "typing-cursor" : undefined}>
                        {pending.assistantText || "Thinking…"}
                      </span>
                    </div>
                  )}
                  {pending.status === "error" && (
                    <div className="error-banner" role="alert">
                      <strong>Couldn&apos;t get a reply</strong>
                      <span>{tutorErrorMessageFor(pending.errorCode)}</span>
                      <button type="button" className="button button-secondary" onClick={handleRetry}>
                        Retry
                      </button>
                    </div>
                  )}
                </>
              )}
              <div ref={threadEndRef} />
            </div>
          </>
        )}

        <form
          className="stack-sm"
          onSubmit={(e) => {
            e.preventDefault();
            void handleSend();
          }}
        >
          <div className="chat-input-row">
            <label htmlFor="tutor-input" className="visually-hidden">
              Your question
            </label>
            <textarea
              id="tutor-input"
              className="input"
              rows={2}
              maxLength={4000}
              value={inputValue}
              disabled={pending?.status === "streaming" || activeConversationId === null}
              onChange={(e) => setInputValue(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey) {
                  e.preventDefault();
                  void handleSend();
                }
              }}
              placeholder="Ask a question about this document…"
            />
            <button
              type="submit"
              className="button button-primary"
              disabled={!inputValue.trim() || pending?.status === "streaming" || activeConversationId === null}
            >
              {pending?.status === "streaming" ? "Sending…" : "Send"}
            </button>
          </div>
          <label className="chat-toggle-row">
            <input
              type="checkbox"
              checked={explainBeyondNotes}
              onChange={(e) => setExplainBeyondNotes(e.target.checked)}
            />
            Explain beyond my notes
          </label>
        </form>
      </div>
    </div>
  );
}

function ChatBubble({ message }: { message: MessageResponse }) {
  const isUser = message.role === "USER";
  return (
    <div className="stack-sm">
      <div className={`chat-bubble ${isUser ? "chat-bubble-user" : "chat-bubble-assistant"}`}>
        {message.content}
      </div>
      {!isUser && <GroundingBadge grounded={message.grounded} beyondNotes={message.beyondNotes} />}
      {!isUser && message.citations && message.citations.length > 0 && (
        <TutorCitations citations={message.citations as TutorCitation[]} />
      )}
    </div>
  );
}

function GroundingBadge({ grounded, beyondNotes }: { grounded: boolean | null; beyondNotes: boolean | null }) {
  if (grounded === null && beyondNotes === null) {
    return null;
  }
  if (beyondNotes) {
    return <span className="badge badge-beyond-notes">Beyond your notes</span>;
  }
  if (grounded) {
    return <span className="badge badge-grounded">From your notes</span>;
  }
  return <span className="badge badge-failed">Not found in your notes</span>;
}

function tutorErrorMessageFor(code: string | undefined): string {
  switch (code) {
    case "QUOTA_AI_EXCEEDED":
      return "You've hit today's tutor message limit. Try again tomorrow.";
    case "AUTH_GUARDIAN_CONSENT_REQUIRED":
      return "Using the tutor requires guardian consent for accounts under 18.";
    case "AI_PROVIDER_UNAVAILABLE":
      return "The tutor is temporarily unavailable. Please try again.";
    case "NETWORK_ERROR":
      return "Please check your connection and try again.";
    default:
      return "Something went wrong. Please try again.";
  }
}

function conversationErrorMessageFor(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "DOCUMENT_NOT_READY":
        return "This document is still being processed.";
      case "AUTH_GUARDIAN_CONSENT_REQUIRED":
        return "Using the tutor requires guardian consent for accounts under 18.";
      default:
        return "Something went wrong. Please try again.";
    }
  }
  return "Something went wrong. Please try again.";
}
