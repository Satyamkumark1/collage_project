package com.studyflow.identity.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** L1 arithmetic only — {@link LoginLockStore} is stubbed out (never locked) so this stays a
 * fast, deterministic, network-free unit test. L2's own durability behavior is covered by
 * {@code LoginRateLimitIntegrationTest} against the real Upstash instance. */
class LoginRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final LoginRateLimiter limiter = new LoginRateLimiter(clock, new NoopLoginLockStore());

    private static final class NoopLoginLockStore implements LoginLockStore {
        @Override
        public void lock(String rawEmail, Duration ttl) {
        }

        @Override
        public Optional<Duration> remainingLock(String rawEmail) {
            return Optional.empty();
        }
    }

    @Test
    void fourFailuresDoNotLock() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("student@example.com");
        }
        assertThatCode(() -> limiter.checkNotLocked("student@example.com")).doesNotThrowAnyException();
    }

    @Test
    void fifthFailureLocksForAboutOneMinute() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("student@example.com");
        }

        assertThatThrownBy(() -> limiter.checkNotLocked("student@example.com"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiException = (ApiException) e;
                    org.assertj.core.api.Assertions.assertThat(apiException.code()).isEqualTo(ErrorCode.RATE_LIMITED);
                    org.assertj.core.api.Assertions.assertThat(apiException.retryAfterSeconds())
                            .isBetween(55L, 61L);
                });
    }

    @Test
    void aSuccessResetsTheFailureCount() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("student@example.com");
        }
        limiter.recordSuccess("student@example.com");
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("student@example.com");
        }

        assertThatCode(() -> limiter.checkNotLocked("student@example.com")).doesNotThrowAnyException();
    }

    @Test
    void lockoutEscalatesOnASecondCycleAfterTheFirstExpires() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("student@example.com");
        }
        assertThatThrownBy(() -> limiter.checkNotLocked("student@example.com")).isInstanceOf(ApiException.class);

        clock.advance(java.time.Duration.ofMinutes(2)); // past the 1-minute first lockout
        assertThatCode(() -> limiter.checkNotLocked("student@example.com")).doesNotThrowAnyException();

        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("student@example.com");
        }

        assertThatThrownBy(() -> limiter.checkNotLocked("student@example.com"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((ApiException) e).retryAfterSeconds())
                        .isBetween(115L, 121L)); // ~2 minutes, tier 1
    }

    @Test
    void caseVariantsOfTheSameEmailShareABucket() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("Student@Example.com");
        }
        limiter.recordFailure("student@example.com");

        assertThatThrownBy(() -> limiter.checkNotLocked("STUDENT@EXAMPLE.COM")).isInstanceOf(ApiException.class);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(java.time.Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
