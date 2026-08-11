package com.studyflow.identity.service;

import java.time.Duration;
import java.util.Optional;

/**
 * L2 durable lock backstop — survives what L1 ({@link LoginRateLimiter}'s in-memory map) loses on
 * a restart. An interface (one real implementation, {@link RedisLoginLockStore}) purely so
 * {@code LoginRateLimiterTest} can stay a fast, deterministic, network-free unit test of the L1
 * arithmetic — same reason {@code Clock} is injected there instead of called directly.
 */
public interface LoginLockStore {

    void lock(String rawEmail, Duration ttl);

    /** Empty if not locked (including "Redis unreachable" — fails open, see docs/DECISIONS.md). */
    Optional<Duration> remainingLock(String rawEmail);
}
