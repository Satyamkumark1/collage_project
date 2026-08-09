package com.studyflow.study.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.studyflow.study.service.Sm2Calculator.Sm2Result;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class Sm2CalculatorTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final BigDecimal DEFAULT_EASE = new BigDecimal("2.50");

    @Test
    void initialCardIsDueImmediatelyAtDefaultEase() {
        Sm2Result initial = Sm2Calculator.initial();
        assertThat(initial.easeFactor()).isEqualByComparingTo(DEFAULT_EASE);
        assertThat(initial.intervalDays()).isZero();
        assertThat(initial.repetitions()).isZero();
        assertThat(initial.dueAt()).isCloseTo(Instant.now(), org.assertj.core.api.Assertions.within(2,
                ChronoUnit.SECONDS));
    }

    @Test
    void firstSuccessfulReviewSchedulesOneDayOut() {
        Sm2Result result = Sm2Calculator.compute(DEFAULT_EASE, 0, 0, 4, ZONE);
        assertThat(result.repetitions()).isEqualTo(1);
        assertThat(result.intervalDays()).isEqualTo(1);
    }

    @Test
    void secondSuccessfulReviewSchedulesSixDaysOut() {
        Sm2Result result = Sm2Calculator.compute(DEFAULT_EASE, 1, 1, 4, ZONE);
        assertThat(result.repetitions()).isEqualTo(2);
        assertThat(result.intervalDays()).isEqualTo(6);
    }

    @Test
    void thirdAndLaterReviewsMultiplyIntervalByEaseFactor() {
        Sm2Result result = Sm2Calculator.compute(DEFAULT_EASE, 6, 2, 4, ZONE);
        // quality 4 -> ease delta = 0.1 - 1*(0.08 + 1*0.02) = 0.1 - 0.10 = 0.00, ease stays 2.50
        assertThat(result.easeFactor()).isEqualByComparingTo(DEFAULT_EASE);
        assertThat(result.intervalDays()).isEqualTo(Math.round(6 * 2.50));
        assertThat(result.repetitions()).isEqualTo(3);
    }

    @Test
    void lapseQualityBelowThreeResetsRepetitionsAndInterval() {
        Sm2Result result = Sm2Calculator.compute(new BigDecimal("2.80"), 15, 4, 1, ZONE);
        assertThat(result.repetitions()).isZero();
        assertThat(result.intervalDays()).isEqualTo(1);
    }

    @Test
    void perfectQualityIncreasesEaseFactor() {
        Sm2Result result = Sm2Calculator.compute(DEFAULT_EASE, 1, 1, 5, ZONE);
        assertThat(result.easeFactor()).isGreaterThan(DEFAULT_EASE);
    }

    @Test
    void poorButPassingQualityDecreasesEaseFactor() {
        Sm2Result result = Sm2Calculator.compute(DEFAULT_EASE, 1, 1, 3, ZONE);
        assertThat(result.easeFactor()).isLessThan(DEFAULT_EASE);
    }

    @Test
    void easeFactorNeverDropsBelowTheSm2Floor() {
        BigDecimal lowEase = new BigDecimal("1.30");
        Sm2Result result = Sm2Calculator.compute(lowEase, 1, 1, 0, ZONE);
        assertThat(result.easeFactor()).isEqualByComparingTo(new BigDecimal("1.3"));
    }

    @Test
    void dueAtIsComputedInTheOwnersTimezoneAsCalendarDays() {
        Sm2Result result = Sm2Calculator.compute(DEFAULT_EASE, 0, 0, 4, ZONE);
        ZonedDateTime expected = ZonedDateTime.now(ZONE).plusDays(1);
        ZonedDateTime actual = result.dueAt().atZone(ZONE);
        assertThat(actual).isCloseTo(expected, org.assertj.core.api.Assertions.within(2, ChronoUnit.SECONDS));
    }

    @Test
    void qualityOutsideZeroToFiveIsRejected() {
        assertThatThrownBy(() -> Sm2Calculator.compute(DEFAULT_EASE, 0, 0, 6, ZONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sm2Calculator.compute(DEFAULT_EASE, 0, 0, -1, ZONE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
