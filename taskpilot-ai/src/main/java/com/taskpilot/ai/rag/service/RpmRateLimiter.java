package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.config.RagEmbeddingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe rolling 60-second window rate limiter.
 * Enforces one shared global accounting boundary protecting provider quota,
 * while reserving interactive headroom for user-facing search traffic.
 */
@Slf4j
@Component
public class RpmRateLimiter {

    private static final long WINDOW_MS = 60_000L;

    private final RagEmbeddingProperties properties;
    private final Deque<Long> requestTimestamps = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();

    public RpmRateLimiter(RagEmbeddingProperties properties) {
        this.properties = properties;
    }

    /**
     * Attempts admission for interactive search embedding.
     * Can consume up to the global maxRpm limit.
     *
     * @return true if admitted, false if capacity exhausted
     */
    public boolean tryAcquireInteractive() {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            pruneExpired(now);
            if (requestTimestamps.size() < properties.getMaxRpm()) {
                requestTimestamps.addLast(now);
                log.debug("Interactive embedding admitted. Current window size: {}/{}",
                        requestTimestamps.size(), properties.getMaxRpm());
                return true;
            }
            log.warn("Interactive embedding rejected: global quota exhausted ({}/{})",
                    requestTimestamps.size(), properties.getMaxRpm());
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts admission for background ingestion embedding.
     * Capacity is constrained to (maxRpm - interactiveHeadroom) to guarantee interactive headroom.
     *
     * @return true if admitted, false if capacity exhausted or headroom reserved
     */
    public boolean tryAcquireBackground() {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            pruneExpired(now);
            int backgroundLimit = properties.getMaxRpm() - properties.getInteractiveHeadroom();
            if (requestTimestamps.size() < backgroundLimit) {
                requestTimestamps.addLast(now);
                log.debug("Background embedding admitted. Current window size: {}/{} (background limit: {})",
                        requestTimestamps.size(), properties.getMaxRpm(), backgroundLimit);
                return true;
            }
            log.warn("Background embedding rejected: capacity limit reached ({}/{}, background limit: {})",
                    requestTimestamps.size(), properties.getMaxRpm(), backgroundLimit);
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clears all timestamps (useful for testing).
     */
    public void reset() {
        lock.lock();
        try {
            requestTimestamps.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current number of requests tracked in the rolling window.
     */
    public int getCurrentWindowCount() {
        lock.lock();
        try {
            pruneExpired(System.currentTimeMillis());
            return requestTimestamps.size();
        } finally {
            lock.unlock();
        }
    }

    private void pruneExpired(long now) {
        long threshold = now - WINDOW_MS;
        while (!requestTimestamps.isEmpty() && requestTimestamps.peekFirst() <= threshold) {
            requestTimestamps.pollFirst();
        }
    }
}
