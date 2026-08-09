package com.studyflow.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Accumulates the 5 metrics named in specs/08-ai-layer.md's "Eval harness" section across every
 * eval case, then renders a markdown report. Thresholds are proposals logged in
 * docs/DECISIONS.md, not enforced here — CI gating is deferred to Phase 5 (docs/status/phase-3.md).
 */
final class EvalReport {

    private int schemaCallsOk;
    private int schemaCallsTotal;
    private int mcqValid;
    private int mcqTotal;
    private int citationsStructurallyGrounded;
    private int citationsLexicallyGrounded;
    private int citationsTotal;
    private int retrievalProbesPassed;
    private int retrievalProbesTotal;
    private final List<Long> jobLatenciesMs = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    void recordSchemaCalls(int ok, int total) {
        schemaCallsOk += ok;
        schemaCallsTotal += total;
    }

    void recordMcqValidity(int valid, int total) {
        mcqValid += valid;
        mcqTotal += total;
    }

    void recordCitationGroundedness(int structurallyGrounded, int lexicallyGrounded, int total) {
        citationsStructurallyGrounded += structurallyGrounded;
        citationsLexicallyGrounded += lexicallyGrounded;
        citationsTotal += total;
    }

    void recordRetrievalProbe(boolean passed) {
        retrievalProbesTotal++;
        if (passed) {
            retrievalProbesPassed++;
        }
    }

    void recordJobLatency(long ms) {
        jobLatenciesMs.add(ms);
    }

    void addNote(String note) {
        notes.add(note);
    }

    String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Eval harness run\n\n");
        sb.append("| Metric | Result | Proposed threshold |\n");
        sb.append("|---|---|---|\n");
        sb.append(row("Schema pass rate", schemaCallsOk, schemaCallsTotal, ">= 95%"));
        sb.append(row("MCQ validity (persisted, re-checked)", mcqValid, mcqTotal, "100%"));
        sb.append(row("Citation groundedness (structural)", citationsStructurallyGrounded, citationsTotal, "100%"));
        sb.append(row("Citation groundedness (lexical overlap, approximate)", citationsLexicallyGrounded,
                citationsTotal, ">= 70%"));
        sb.append(row("Retrieval recall", retrievalProbesPassed, retrievalProbesTotal, ">= 80%"));
        sb.append(String.format("| Job latency (p95) | %dms | <= 180000ms |%n", p95(jobLatenciesMs)));
        if (!notes.isEmpty()) {
            sb.append("\n## Notes\n\n");
            for (String note : notes) {
                sb.append("- ").append(note).append('\n');
            }
        }
        return sb.toString();
    }

    private String row(String label, int n, int d, String threshold) {
        return String.format("| %s | %.1f%% (%d/%d) | %s |%n", label, pct(n, d), n, d, threshold);
    }

    private double pct(int n, int d) {
        return d == 0 ? 0.0 : 100.0 * n / d;
    }

    private long p95(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(0.95 * sorted.size()) - 1);
        return sorted.get(Math.max(index, 0));
    }
}
