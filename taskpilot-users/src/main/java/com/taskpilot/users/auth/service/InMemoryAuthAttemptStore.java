package com.taskpilot.users.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded atomic in-memory {@link AuthAttemptStore} for single-instance or pure unit testing.
 */
public class InMemoryAuthAttemptStore implements AuthAttemptStore {

    private static final int DEFAULT_MAX_KEYS = 10_000;

    private final int maxKeys;
    private final Map<String, WindowEntry> entries = new ConcurrentHashMap<>();

    public InMemoryAuthAttemptStore() {
        this(DEFAULT_MAX_KEYS);
    }

    public InMemoryAuthAttemptStore(int maxKeys) {
        this.maxKeys = Math.max(16, maxKeys);
    }

    @Override
    public long incrementAndGet(String action, String keyHash, Duration window, Instant now) {
        evictIfNeeded(now);
        String key = action + ":" + keyHash;
        WindowEntry updated = entries.compute(key, (k, current) -> {
            if (current == null || !current.expiresAt().isAfter(now)) {
                return new WindowEntry(1L, now.plus(window));
            }
            return new WindowEntry(current.count() + 1L, current.expiresAt());
        });
        return updated.count();
    }

    @Override
    public long currentCount(String action, String keyHash, Instant now) {
        WindowEntry entry = entries.get(action + ":" + keyHash);
        if (entry == null || !entry.expiresAt().isAfter(now)) {
            return 0L;
        }
        return entry.count();
    }

    @Override
    public long tryAcquireCooldownSeconds(String action, String keyHash, Duration cooldown, Instant now) {
        evictIfNeeded(now);
        String key = action + ":" + keyHash;
        AtomicLong remaining = new AtomicLong(0L);
        entries.compute(key, (k, current) -> {
            if (current == null || !current.expiresAt().isAfter(now)) {
                remaining.set(0L);
                return new WindowEntry(1L, now.plus(cooldown));
            }
            remaining.set(Math.max(1L, Duration.between(now, current.expiresAt()).toSeconds()));
            return current;
        });
        return remaining.get();
    }

    @Override
    public void clear(String action, String keyHash) {
        entries.remove(action + ":" + keyHash);
    }

    @Override
    public int purgeExpired(Instant now) {
        int removed = 0;
        Iterator<Map.Entry<String, WindowEntry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (!it.next().getValue().expiresAt().isAfter(now)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    private void evictIfNeeded(Instant now) {
        if (entries.size() < maxKeys) {
            return;
        }
        purgeExpired(now);
        if (entries.size() >= maxKeys) {
            int targetRemove = Math.max(1, entries.size() / 4);
            Iterator<String> it = entries.keySet().iterator();
            while (it.hasNext() && targetRemove > 0) {
                it.next();
                it.remove();
                targetRemove--;
            }
        }
    }

    private record WindowEntry(long count, Instant expiresAt) {}
}
