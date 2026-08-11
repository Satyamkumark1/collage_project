package com.studyflow.identity.service;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * L1 in-process login-attempt limiter (5 failures / 15 min, then exponential lockout) — the
 * bucket specs/06-rate-limiting.md already claimed existed but was never actually built. In-memory
 * only, per instance, resets on restart. L2 ({@link LoginLockStore}, Redis-backed) durably mirrors
 * an established lock so it survives a restart — see docs/DECISIONS.md for the exact numbers and
 * rationale, and why sub-threshold failure counts stay L1-only (a restart quietly forgiving a
 * partial streak is an acceptable trade against the complexity of durably tracking every count).
 */
@Component
public class LoginRateLimiter {

    private static final int MAX_FAILURES_BEFORE_LOCKOUT = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration BASE_LOCKOUT = Duration.ofMinutes(1);
    private static final Duration MAX_LOCKOUT = Duration.ofMinutes(60);
    private static final Duration IDLE_EVICTION = Duration.ofHours(2);

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;
    private final LoginLockStore loginLockStore;

    public LoginRateLimiter(Clock clock, LoginLockStore loginLockStore) {
        this.clock = clock;
        this.loginLockStore = loginLockStore;
    }

    private record Bucket(int failureCount, Instant windowStart, Instant lockedUntil, int lockoutTier,
            Instant lastActivityAt) {
        static Bucket empty(Instant now) {
            return new Bucket(0, null, null, 0, now);
        }
    }

    /** Called before any DB/password work — see AuthController. */
    public void checkNotLocked(String rawEmail) {
        Bucket bucket = buckets.get(normalize(rawEmail));
        if (bucket != null && bucket.lockedUntil() != null) {
            Instant now = clock.instant();
            if (now.isBefore(bucket.lockedUntil())) {
                throwLocked(Duration.between(now, bucket.lockedUntil()).toSeconds() + 1);
            }
        }
        // L1 has no record of a lock — could be a clean account, or L1's memory of a real lock
        // was wiped by a restart. L2 is the durable backstop for the latter.
        loginLockStore.remainingLock(rawEmail).ifPresent(remaining -> throwLocked(remaining.toSeconds()));
    }

    /**
     * Called on wrong password OR unknown email alike — keyed on the raw submitted string, never
     * on whether the account exists, so this preserves login's existing non-enumeration property.
     */
    public void recordFailure(String rawEmail) {
        Bucket updated = buckets.compute(normalize(rawEmail), (key, existing) -> nextOnFailure(existing, clock.instant()));
        if (updated.lockedUntil() != null) {
            Duration remaining = Duration.between(clock.instant(), updated.lockedUntil());
            if (!remaining.isNegative()) {
                loginLockStore.lock(rawEmail, remaining);
            }
        }
    }

    private void throwLocked(long retryAfterSeconds) {
        throw new ApiException(ErrorCode.RATE_LIMITED,
                "Too many failed login attempts for this account; try again later", null, retryAfterSeconds);
    }

    public void recordSuccess(String rawEmail) {
        buckets.remove(normalize(rawEmail));
    }

    private Bucket nextOnFailure(Bucket existing, Instant now) {
        Bucket bucket = existing == null ? Bucket.empty(now) : existing;
        boolean windowExpired = bucket.windowStart() == null
                || Duration.between(bucket.windowStart(), now).compareTo(WINDOW) > 0;
        int failureCount = (windowExpired ? 0 : bucket.failureCount()) + 1;
        if (failureCount >= MAX_FAILURES_BEFORE_LOCKOUT) {
            Duration lockoutDuration = lockoutDurationForTier(bucket.lockoutTier());
            return new Bucket(0, null, now.plus(lockoutDuration), bucket.lockoutTier() + 1, now);
        }
        Instant windowStart = windowExpired ? now : bucket.windowStart();
        return new Bucket(failureCount, windowStart, bucket.lockedUntil(), bucket.lockoutTier(), now);
    }

    private Duration lockoutDurationForTier(int tier) {
        int safeTier = Math.min(tier, 60); // clamp before shift to avoid overflow
        long minutes = Math.min(MAX_LOCKOUT.toMinutes(), BASE_LOCKOUT.toMinutes() * (1L << safeTier));
        return Duration.ofMinutes(minutes);
    }

    private String normalize(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    @Scheduled(fixedDelay = 300_000)
    void evictIdleEntries() {
        Instant cutoff = clock.instant().minus(IDLE_EVICTION);
        buckets.entrySet().removeIf(e -> e.getValue().lastActivityAt().isBefore(cutoff));
    }
}
