package com.studyflow.study.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Piotr Wozniak's original 1990 SM-2 algorithm, math unmodified — a fresh design call for this
 * build (the master spec's SM-2 formulas were never actually transcribed anywhere retrievable in
 * this repo, see docs/DECISIONS.md). Pure function, no DB/HTTP dependency, so it's plain-JUnit
 * testable without Spring context.
 */
public final class Sm2Calculator {

    private static final BigDecimal MIN_EASE_FACTOR = new BigDecimal("1.3");
    private static final BigDecimal DEFAULT_EASE_FACTOR = new BigDecimal("2.50");

    private Sm2Calculator() {
    }

    public record Sm2Result(BigDecimal easeFactor, int intervalDays, int repetitions, Instant dueAt) {
    }

    /** A newly generated card starts here: due immediately, nothing reviewed yet. */
    public static Sm2Result initial() {
        return new Sm2Result(DEFAULT_EASE_FACTOR, 0, 0, Instant.now());
    }

    /**
     * @param quality 0-5, the student's self-rated recall quality for this review.
     */
    public static Sm2Result compute(BigDecimal currentEaseFactor, int currentIntervalDays, int currentRepetitions,
            int quality, ZoneId userZone) {
        if (quality < 0 || quality > 5) {
            throw new IllegalArgumentException("quality must be 0-5, got " + quality);
        }

        double ef = currentEaseFactor.doubleValue();
        double delta = 0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02);
        BigDecimal newEaseFactor = BigDecimal.valueOf(ef + delta).setScale(2, RoundingMode.HALF_UP);
        if (newEaseFactor.compareTo(MIN_EASE_FACTOR) < 0) {
            newEaseFactor = MIN_EASE_FACTOR;
        }

        int newIntervalDays;
        int newRepetitions;
        if (quality < 3) {
            // A lapse resets the "learning" progression — same posture Anki/SuperMemo use.
            newRepetitions = 0;
            newIntervalDays = 1;
        } else {
            if (currentRepetitions == 0) {
                newIntervalDays = 1;
            } else if (currentRepetitions == 1) {
                newIntervalDays = 6;
            } else {
                newIntervalDays = (int) Math.round(currentIntervalDays * newEaseFactor.doubleValue());
            }
            newRepetitions = currentRepetitions + 1;
        }

        // Calendar days in the owner's timezone, not a naive now + N*24h — avoids the "due" wall-
        // clock time drifting forward on every review. Not truncated to local midnight (batching
        // a day's due cards together) — nothing asked for it; an easy add later, not a silent gap.
        Instant dueAt = ZonedDateTime.now(userZone).plusDays(newIntervalDays).toInstant();

        return new Sm2Result(newEaseFactor, newIntervalDays, newRepetitions, dueAt);
    }
}
