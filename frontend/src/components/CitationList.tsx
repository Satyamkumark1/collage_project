import type { KeyPointCitation } from "../api/keyPoints";

interface CitationListProps {
  citations: KeyPointCitation[];
}

export function CitationList({ citations }: CitationListProps) {
  if (citations.length === 0) {
    return null;
  }

  return (
    <div className="source-rail">
      <h3>Citations</h3>
      <ul style={{ listStyle: "none", margin: 0, padding: 0 }}>
        {citations.map((citation) => (
          <li
            key={`${citation.chunkId}-${citation.pageFrom ?? "na"}-${citation.pageTo ?? "na"}-${citation.sectionPath ?? "na"}`}
            className="source-rail-item"
          >
            <span className="source-rail-marker numeral" aria-hidden="true">
              {citation.chunkId.slice(0, 8)}
            </span>
            <span>
              {citation.sectionPath ?? "Untitled section"}
              {citation.pageFrom != null && (
                <span className="hint numeral">
                  {" "}
                  · p. {citation.pageFrom}
                  {citation.pageTo != null && citation.pageTo !== citation.pageFrom ? `–${citation.pageTo}` : ""}
                </span>
              )}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
