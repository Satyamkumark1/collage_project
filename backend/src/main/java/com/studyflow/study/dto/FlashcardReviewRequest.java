package com.studyflow.study.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The backend accepts the full canonical SM-2 0-5 scale even though the frontend collapses this
 * to 4 buttons (Again/Hard/Good/Easy → 0/3/4/5) — see docs/DECISIONS.md.
 */
public record FlashcardReviewRequest(@NotNull @Min(0) @Max(5) Integer quality) {
}
