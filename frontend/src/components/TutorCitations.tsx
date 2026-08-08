import type { TutorCitation } from "../api/tutor";

interface TutorCitationsProps {
  citations: TutorCitation[];
}

/**
 * Tutor-chat citations carry page/section provenance (unlike the summary Source Rail's
 * chunk-id-only list — see specs/11-frontend.md), because the grounding contract's whole point is
 * showing exactly where in the student's own notes an answer came from.
 */
export function TutorCitations({ citations }: TutorCitationsProps) {
  if (citations.length === 0) {
    return null;
  }

  return (
    <div className="source-rail">
      <h3>Sourced from your notes</h3>
      <ul style={{ listStyle: "none", margin: 0, padding: 0 }}>
        {citations.map((citation) => (
          <li key={citation.marker} className="source-rail-item">
            <span className="source-rail-marker numeral">
              [{citation.marker}]
            </span>
            <span>
              {citation.sectionPath ?? "Untitled section"}
              {citation.pageFrom != null && (
                <span className="hint numeral">
                  {" "}
                  · p. {citation.pageFrom}
                  {citation.pageTo != null && citation.pageTo !== citation.pageFrom
                    ? `–${citation.pageTo}`
                    : ""}
                </span>
              )}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
