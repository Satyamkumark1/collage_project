import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { listDocuments, type DocumentResponse } from "../api/documents";

const STATUS_LABEL: Record<DocumentResponse["status"], string> = {
  UPLOADED: "Queued",
  PARSING: "Reading…",
  CHUNKING: "Organizing…",
  EMBEDDING: "Indexing…",
  READY: "Ready",
  FAILED: "Failed",
};

export function Library() {
  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ["documents"],
    queryFn: () => listDocuments(),
  });

  return (
    <div className="page">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
        <h1>Your library</h1>
        <Link to="/upload" className="button button-primary">
          Upload notes
        </Link>
      </div>

      {isLoading && (
        <div className="stack" aria-busy="true" aria-live="polite">
          <span className="visually-hidden">Loading your documents…</span>
          {[0, 1, 2].map((i) => (
            <div key={i} className="skeleton" style={{ height: 68 }} />
          ))}
        </div>
      )}

      {isError && (
        <div className="error-banner" role="alert">
          <strong>Couldn&apos;t load your library</strong>
          <span>{error instanceof Error ? error.message : "Unknown error"}</span>
          <button type="button" className="button button-secondary" onClick={() => refetch()}>
            Retry
          </button>
        </div>
      )}

      {data && data.items.length === 0 && (
        <div className="empty-state">
          <p>No documents yet. Upload a PDF, TXT, or MD file to get started.</p>
          <Link to="/upload" className="button button-primary">
            Upload your first document
          </Link>
        </div>
      )}

      {data && data.items.length > 0 && (
        <ul className="stack-sm" style={{ listStyle: "none", margin: 0, padding: 0 }}>
          {data.items.map((doc) => (
            <li key={doc.id}>
              <Link to={`/documents/${doc.id}`} className="document-row">
                <span className="document-row-title">{doc.title}</span>
                <span className={`badge ${badgeClassFor(doc.status)}`}>{STATUS_LABEL[doc.status]}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function badgeClassFor(status: DocumentResponse["status"]): string {
  if (status === "READY") return "badge-ready";
  if (status === "FAILED") return "badge-failed";
  return "badge-progress";
}
