package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.config.RagEmbeddingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe rolling 60-second window rate limiter governing both request rate (RPM)
 * and estimated token consumption (TPM).
 * Enforces one shared global accounting boundary protecting provider quota,
 * while reserving interactive headroom for user-facing search traffic and
 * supporting bounded normal pacing for background ingestion batches.
 */
@Slf4j
@Component
public class RpmRateLimiter {

    private static final long WINDOW_MS = 60_000L;

    public record AdmissionRecord(long timestamp, int requestCount, int tokens) {}

    private final RagEmbeddingProperties properties;
    private final Deque<AdmissionRecord> records = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition capacityAvailable = lock.newCondition();

    public RpmRateLimiter(RagEmbeddingProperties properties) {
        this.properties = properties;
    }

    /**
     * Attempts immediate admission for interactive search embedding without tokens specified.
     * Backwards-compatible convenience method assuming small default query (~15 tokens, 1 request).
     */
    public boolean tryAcquireInteractive() {
        return tryAcquireInteractive(1, 15);
    }

    /**
     * Attempts admission for interactive search embedding with estimated token count (1 request).
     */
    public boolean tryAcquireInteractive(int estimatedTokens) {
        return tryAcquireInteractive(1, estimatedTokens);
    }

    /**
     * Attempts admission for interactive search embedding with request count and estimated token count.
     * Higher priority: can consume up to the global maxRpm and maxTpm limits.
     *
     * @param requestCount number of embedding requests
     * @param estimatedTokens token count estimated for the query
     * @return true if admitted, false if capacity exhausted
     */
    public boolean tryAcquireInteractive(int requestCount, int estimatedTokens) {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            pruneExpired(now);
            int currentRequests = calculateTotalRequests();
            int currentTokens = calculateTotalTokens();

            if ((currentRequests + requestCount) <= properties.getMaxRpm()
                    && (currentTokens + estimatedTokens) <= properties.getMaxTpm()) {
                records.addLast(new AdmissionRecord(now, requestCount, estimatedTokens));
                log.debug("Interactive embedding admitted (requests={}, tokens={}). Window RPM: {}/{}, TPM: {}/{}",
                        requestCount, estimatedTokens, currentRequests + requestCount, properties.getMaxRpm(),
                        currentTokens + estimatedTokens, properties.getMaxTpm());
                return true;
            }
            log.warn("Interactive embedding rejected: global quota exhausted (RPM: {}/{}, TPM: {}/{})",
                    currentRequests + requestCount, properties.getMaxRpm(),
                    currentTokens + estimatedTokens, properties.getMaxTpm());
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts immediate admission for background ingestion embedding without tokens specified.
     * Backwards-compatible convenience method assuming default batch (~100 tokens, 1 request).
     */
    public boolean tryAcquireBackground() {
        return tryAcquireBackground(1, 100);
    }

    /**
     * Attempts immediate admission for background ingestion embedding (1 request).
     */
    public boolean tryAcquireBackground(int estimatedTokens) {
        return tryAcquireBackground(1, estimatedTokens);
    }

    /**
     * Attempts immediate admission for background ingestion embedding.
     * Capacity is constrained to (maxRpm - interactiveHeadroom) and (maxTpm - interactiveTpmHeadroom).
     *
     * @param requestCount number of embedding requests in the batch
     * @param estimatedTokens token count estimated for the batch
     * @return true if admitted, false if capacity exhausted or headroom reserved
     */
    public boolean tryAcquireBackground(int requestCount, int estimatedTokens) {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            pruneExpired(now);
            int backgroundRpmLimit = properties.getMaxRpm() - properties.getInteractiveHeadroom();
            int backgroundTpmLimit = properties.getMaxTpm() - properties.getInteractiveTpmHeadroom();
            int currentRequests = calculateTotalRequests();
            int currentTokens = calculateTotalTokens();

            if ((currentRequests + requestCount) <= backgroundRpmLimit
                    && (currentTokens + estimatedTokens) <= backgroundTpmLimit) {
                records.addLast(new AdmissionRecord(now, requestCount, estimatedTokens));
                log.debug("Background embedding admitted (requests={}, tokens={}). Window RPM: {}/{} (limit: {}), TPM: {}/{} (limit: {})",
                        requestCount, estimatedTokens, currentRequests + requestCount, properties.getMaxRpm(), backgroundRpmLimit,
                        currentTokens + estimatedTokens, properties.getMaxTpm(), backgroundTpmLimit);
                return true;
            }
            log.warn("Background embedding rejected: capacity limit reached (RPM: {}/{}, TPM: {}/{})",
                    currentRequests + requestCount, backgroundRpmLimit, currentTokens + estimatedTokens, backgroundTpmLimit);
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Normal pacing for background ingestion (single request): waits up to maxWaitMs for sliding-window capacity.
     */
    public boolean acquireBackgroundWithPacing(int estimatedTokens, long maxWaitMs) {
        return acquireBackgroundWithPacing(1, estimatedTokens, maxWaitMs);
    }

    /**
     * Normal pacing for background ingestion: waits up to maxWaitMs for sliding-window capacity
     * to become available instead of immediately throwing an exception or thrashing RETRY_WAIT.
     *
     * @param requestCount number of embedding requests in the batch
     * @param estimatedTokens tokens needed for the batch
     * @param maxWaitMs maximum time in milliseconds to wait before giving up
     * @return true if acquired within maxWaitMs, false if capacity could not be acquired
     */
    public boolean acquireBackgroundWithPacing(int requestCount, int estimatedTokens, long maxWaitMs) {
        if (maxWaitMs <= 0) {
            return tryAcquireBackground(requestCount, estimatedTokens);
        }

        long deadline = System.currentTimeMillis() + maxWaitMs;
        lock.lock();
        try {
            while (true) {
                long now = System.currentTimeMillis();
                pruneExpired(now);
                int backgroundRpmLimit = properties.getMaxRpm() - properties.getInteractiveHeadroom();
                int backgroundTpmLimit = properties.getMaxTpm() - properties.getInteractiveTpmHeadroom();
                int currentRequests = calculateTotalRequests();
                int currentTokens = calculateTotalTokens();

                if ((currentRequests + requestCount) <= backgroundRpmLimit
                        && (currentTokens + estimatedTokens) <= backgroundTpmLimit) {
                    records.addLast(new AdmissionRecord(now, requestCount, estimatedTokens));
                    log.info("Background embedding admitted with pacing (requests={}, tokens={}). Window RPM: {}/{}, TPM: {}/{}",
                            requestCount, estimatedTokens, currentRequests + requestCount, backgroundRpmLimit,
                            currentTokens + estimatedTokens, backgroundTpmLimit);
                    return true;
                }

                long remaining = deadline - now;
                if (remaining <= 0) {
                    log.warn("Background pacing timeout after {}ms (RPM: {}/{}, TPM: {}/{}). Yielding to RETRY_WAIT.",
                            maxWaitMs, currentRequests + requestCount, backgroundRpmLimit,
                            currentTokens + estimatedTokens, backgroundTpmLimit);
                    return false;
                }

                // Determine duration until the oldest record in the window expires
                long oldestExpiry = records.isEmpty() ? remaining : Math.max(1L, (records.peekFirst().timestamp() + WINDOW_MS - now));
                long waitTime = Math.min(oldestExpiry, remaining);

                try {
                    capacityAvailable.await(waitTime, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("Background pacing interrupted while waiting for capacity");
                    return false;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clears all admission records (useful for testing).
     */
    public void reset() {
        lock.lock();
        try {
            records.clear();
            capacityAvailable.signalAll();
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
            return calculateTotalRequests();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current total estimated tokens tracked in the rolling window.
     */
    public int getCurrentWindowTokens() {
        lock.lock();
        try {
            pruneExpired(System.currentTimeMillis());
            return calculateTotalTokens();
        } finally {
            lock.unlock();
        }
    }

    private void pruneExpired(long now) {
        long threshold = now - WINDOW_MS;
        boolean removed = false;
        while (!records.isEmpty() && records.peekFirst().timestamp() <= threshold) {
            records.pollFirst();
            removed = true;
        }
        if (removed) {
            capacityAvailable.signalAll();
        }
    }

    private int calculateTotalRequests() {
        int sum = 0;
        for (AdmissionRecord rec : records) {
            sum += rec.requestCount();
        }
        return sum;
    }

    private int calculateTotalTokens() {
        int sum = 0;
        for (AdmissionRecord rec : records) {
            sum += rec.tokens();
        }
        return sum;
    }

    /**
     * Conservative token estimator: assumes ~1 token per 3 characters.
     * Safe upper bound for multilingual Vietnamese/English text, markdown, and code.
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(text.length() / 3.0));
    }

    /**
     * Conservative token estimator for a collection of text segments.
     */
    public static int estimateTokens(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (String text : texts) {
            total += estimateTokens(text);
        }
        return total;
    }
}
