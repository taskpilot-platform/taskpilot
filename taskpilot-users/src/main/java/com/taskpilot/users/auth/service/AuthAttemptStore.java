package com.taskpilot.users.auth.service;

import java.time.Duration;
import java.time.Instant;

public interface AuthAttemptStore {

    long incrementAndGet(String action, String keyHash, Duration window, Instant now);

    long currentCount(String action, String keyHash, Instant now);

    /**
     * Atomically checks whether {@code (action, keyHash)} is outside any active cooldown window
     * and, if so, acquires the cooldown window. Returns remaining cooldown seconds (> 0) if blocked,
     * or 0 if acquired.
     */
    long tryAcquireCooldownSeconds(String action, String keyHash, Duration cooldown, Instant now);

    void clear(String action, String keyHash);

    int purgeExpired(Instant now);
}
