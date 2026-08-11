import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useParams } from "react-router-dom";
import { ApiError } from "../api/client";
import { getDocument } from "../api/documents";
import { createStudyPlan, downloadIcs, listStudyPlans, type StudyPlanResponse } from "../api/studyPlans";

const today = new Date().toISOString().slice(0, 10);

export function StudyPlanner() {
  const { id } = useParams<{ id: string }>();
  const documentId = id!;
  const queryClient = useQueryClient();
  const [examDate, setExamDate] = useState(today);

  const documentQuery = useQuery({ queryKey: ["document", documentId], queryFn: () => getDocument(documentId) });
  const plansQuery = useQuery({ queryKey: ["study-plans", documentId], queryFn: () => listStudyPlans(documentId) });

  const createMutation = useMutation({
    mutationFn: () => createStudyPlan(documentId, examDate),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ["study-plans", documentId] }),
  });

  if (documentQuery.isLoading) {
    return (
      <div className="page stack" aria-busy="true" aria-live="polite">
        <span className="visually-hidden">Loading planner…</span>
        <div className="skeleton" style={{ height: 32, width: "60%" }} />
        <div className="skeleton" style={{ height: 120 }} />
      </div>
    );
  }

  const doc = documentQuery.data;
  if (documentQuery.isError || !doc) {
    return (
      <div className="page">
        <div className="error-banner" role="alert">
          <strong>Couldn&apos;t load this document</strong>
          <span>Please go back and try again.</span>
        </div>
      </div>
    );
  }

  return (
    <div className="page stack">
      <div>
        <h1>Study planner</h1>
        <p className="hint numeral">{doc.title}</p>
      </div>

      <div className="card stack">
        <h2 style={{ margin: 0 }}>Build a plan</h2>
        <div className="field">
          <label htmlFor="exam-date">Exam date</label>
          <input
            id="exam-date"
            type="date"
            className="input"
            min={today}
            value={examDate}
            onChange={(e) => setExamDate(e.target.value)}
          />
        </div>
        <button
          type="button"
          className="button button-primary"
          disabled={createMutation.isPending}
          onClick={() => createMutation.mutate()}
        >
          {createMutation.isPending ? "Building…" : "Build plan"}
        </button>
        {createMutation.isError && (
          <div className="error-banner" role="alert">
            <strong>Couldn&apos;t build a plan</strong>
            <span>{planErrorMessageFor(createMutation.error)}</span>
          </div>
        )}
      </div>

      {plansQuery.isLoading && <div className="skeleton" style={{ height: 100 }} aria-busy="true" aria-live="polite" />}

      {plansQuery.data && plansQuery.data.length === 0 && (
        <p className="hint">No study plans yet. Build one above.</p>
      )}

      {plansQuery.data?.map((plan) => <PlanCard key={plan.id} plan={plan} />)}
    </div>
  );
}

function PlanCard({ plan }: { plan: StudyPlanResponse }) {
  return (
    <article className="card stack-sm">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", gap: "1rem" }}>
        <strong>Exam on {plan.examDate}</strong>
        <button type="button" className="button button-secondary" onClick={() => void downloadIcs(plan.id)}>
          Export .ics
        </button>
      </div>
      <ul style={{ listStyle: "none", margin: 0, padding: 0 }} className="stack-sm">
        {plan.sessions.map((session) => (
          <li key={session.id} className="card numeral" style={{ padding: "0.5rem 0.75rem" }}>
            {session.scheduledDate}
          </li>
        ))}
      </ul>
    </article>
  );
}

function planErrorMessageFor(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "DOCUMENT_NOT_FOUND":
        return "This document could not be found.";
      case "VALIDATION_FAILED":
        return "Pick an exam date today or later.";
      default:
        return "Something went wrong. Please try again.";
    }
  }
  return "Something went wrong. Please try again.";
}
