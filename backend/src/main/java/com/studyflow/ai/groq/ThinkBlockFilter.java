package com.studyflow.ai.groq;

/**
 * Streaming-safe equivalent of {@link GroqAiProvider}'s regex-based {@code <think>...</think>}
 * stripping (see specs/00-product-and-constraints.md constraint #9) — a reasoning model's
 * scratchpad must never reach a student, but a token-by-token stream can split a tag across two
 * deltas, so this can't just regex each delta in isolation. Feed deltas via {@link #accept}, call
 * {@link #finish} once the stream ends to flush anything still safely bufferable.
 */
final class ThinkBlockFilter {

    private static final String OPEN_TAG = "<think>";
    private static final String CLOSE_TAG = "</think>";

    private final StringBuilder pending = new StringBuilder();
    private boolean insideThink = false;

    /** Returns the portion of buffered text (possibly empty) that's now safe to emit. */
    String accept(String delta) {
        pending.append(delta);
        StringBuilder visible = new StringBuilder();
        while (true) {
            if (!insideThink) {
                int openIdx = pending.indexOf(OPEN_TAG);
                if (openIdx == -1) {
                    int safe = safeLength(pending, OPEN_TAG);
                    visible.append(pending, 0, safe);
                    pending.delete(0, safe);
                    break;
                }
                visible.append(pending, 0, openIdx);
                pending.delete(0, openIdx + OPEN_TAG.length());
                insideThink = true;
            } else {
                int closeIdx = pending.indexOf(CLOSE_TAG);
                if (closeIdx == -1) {
                    // Discard everything confirmed to not be the start of "</think>"; hold back
                    // a possible partial match in case the next delta completes it.
                    pending.delete(0, safeLength(pending, CLOSE_TAG));
                    break;
                }
                pending.delete(0, closeIdx + CLOSE_TAG.length());
                insideThink = false;
            }
        }
        return visible.toString();
    }

    /** Anything still buffered inside an unterminated think block is dropped, never emitted. */
    String finish() {
        String rest = insideThink ? "" : pending.toString();
        pending.setLength(0);
        return rest;
    }

    /** Length of {@code buf}'s prefix that provably isn't the start of {@code tag}. */
    private int safeLength(StringBuilder buf, String tag) {
        int maxSuffix = Math.min(buf.length(), tag.length() - 1);
        for (int len = maxSuffix; len > 0; len--) {
            if (buf.substring(buf.length() - len).equals(tag.substring(0, len))) {
                return buf.length() - len;
            }
        }
        return buf.length();
    }
}
