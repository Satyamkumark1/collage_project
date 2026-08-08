import type { Citation } from "../api/summaries";

interface SourceRailProps {
  citations: Citation[];
}

/**
 * The signature "trust" element — see specs/11-frontend.md. This phase: a simple citation list
 * (chunk reference only, no snippet/hover-linking yet), but every entry shown here is a real
 * chunk reference the backend validated against the document, not decorative.
 */
export function SourceRail({ citations }: SourceRailProps) {
  if (citations.length === 0) {
    return null;
  }

  return (
    <div className="source-rail">
      <h3>Sourced from your notes</h3>
      <ul style={{ listStyle: "none", margin: 0, padding: 0 }}>
        {citations.map((citation) => (
          <li key={citation.chunkId} className="source-rail-item">
            <span className="source-rail-marker" aria-hidden="true">
              ✓
            </span>
            <span className="numeral">chunk {citation.chunkId.slice(0, 8)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
