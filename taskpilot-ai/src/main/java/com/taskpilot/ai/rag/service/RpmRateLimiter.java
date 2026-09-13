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

    public record AdmissionRecord(long timestamp, int providerRequestUnits, int tokens) {}

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
     * @param providerRequestUnits number of provider quota units (1 for interactive single query)
     * @param estimatedTokens token count estimated for the query
     * @return true if admitted, false if capacity exhausted
     */
    public boolean tryAcquireInteractive(int providerRequestUnits, int estimatedTokens) {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            pruneExpired(now);
            int currentRequests = calculateTotalRequests();
            int currentTokens = calculateTotalTokens();

            if ((currentRequests + providerRequestUnits) <= properties.getMaxRpm()
                    && (currentTokens + estimatedTokens) <= properties.getMaxTpm()) {
                records.addLast(new AdmissionRecord(now, providerRequestUnits, estimatedTokens));
                log.debug("Interactive embedding admitted (providerRequestUnits={}, tokens={}). Window RPM: {}/{}, TPM: {}/{}",
                        providerRequestUnits, estimatedTokens, currentRequests + providerRequestUnits, properties.getMaxRpm(),
                        currentTokens + estimatedTokens, properties.getMaxTpm());
                return true;
            }
            log.warn("Interactive embedding rejected: global quota exhausted (RPM: {}/{}, TPM: {}/{})",
                    currentRequests + providerRequestUnits, properties.getMaxRpm(),
                    currentTokens + estimatedTokens, properties.getMaxTpm());
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts immediate admission for background ingestion embedding without tokens specified.
     * Backwards-compatible convenience method assuming default batch (~100 tokens, 1 request unit).
     */
    public boolean tryAcquireBackground() {
        return tryAcquireBackground(1, 100);
    }

    /**
     * Attempts immediate admission for background ingestion embedding (1 request unit).
     */
    public boolean tryAcquireBackground(int estimatedTokens) {
        return tryAcquireBackground(1, estimatedTokens);
    }

    /**
     * Attempts immediate admission for background ingestion embedding.
     * Capacity is constrained to (maxRpm - interactiveHeadroom) and (maxTpm - interactiveTpmHeadroom).
     *
     * @param providerRequestUnits number of provider quota units in the batch (e.g. batch.size())
     * @param estimatedTokens token count estimated for the batch
     * @return true if admitted, false if capacity exhausted or headroom reserved
     */
    public boolean tryAcquireBackground(int providerRequestUnits, int estimatedTokens) {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            pruneExpired(now);
            int backgroundRpmLimit = properties.getMaxRpm() - properties.getInteractiveHeadroom();
            int backgroundTpmLimit = properties.getMaxTpm() - properties.getInteractiveTpmHeadroom();
            int currentRequests = calculateTotalRequests();
            int currentTokens = calculateTotalTokens();

            if ((currentRequests + providerRequestUnits) <= backgroundRpmLimit
                    && (currentTokens + estimatedTokens) <= backgroundTpmLimit) {
                records.addLast(new AdmissionRecord(now, providerRequestUnits, estimatedTokens));
                log.debug("Background embedding admitted (providerRequestUnits={}, tokens={}). Window RPM: {}/{} (limit: {}), TPM: {}/{} (limit: {})",
                        providerRequestUnits, estimatedTokens, currentRequests + providerRequestUnits, properties.getMaxRpm(), backgroundRpmLimit,
                        currentTokens + estimatedTokens, properties.getMaxTpm(), backgroundTpmLimit);
                return true;
            }
            log.warn("Background embedding rejected: capacity limit reached (RPM: {}/{}, TPM: {}/{})",
                    currentRequests + providerRequestUnits, backgroundRpmLimit, currentTokens + estimatedTokens, backgroundTpmLimit);
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Atomically acquires background capacity or yields with QuotaBackpressureException.
     * Enforces:
     * 1. Atomic admission check and reservation in one critical section.
     * 2. Small inline quota wait optimization (<= 2000ms, at most once per batch).
     * 3. Exact sliding-window wait calculation (max(requestWaitMs, tokenWaitMs)).
     * 4. QuotaBackpressureException when capacity must yield without consuming retry budget.
     *
     * @param providerRequestUnits number of provider quota units (batch.size() for batchEmbedContents)
     * @param estimatedTokens total estimated tokens for the batch
     * @throws QuotaBackpressureException when capacity is not available and worker must yield to RETRY_WAIT
     */
    public void acquireBackgroundOrYield(int providerRequestUnits, int estimatedTokens) throws QuotaBackpressureException {
        long waitMs;
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            pruneExpired(now);
            int backgroundRpmLimit = properties.getMaxRpm() - properties.getInteractiveHeadroom();
            int backgroundTpmLimit = properties.getMaxTpm() - properties.getInteractiveTpmHeadroom();
            int currentRequests = calculateTotalRequests();
            int currentTokens = calculateTotalTokens();

            if ((currentRequests + providerRequestUnits) <= backgroundRpmLimit
                    && (currentTokens + estimatedTokens) <= backgroundTpmLimit) {
                records.addLast(new AdmissionRecord(now, providerRequestUnits, estimatedTokens));
                log.debug("Background embedding admitted (providerRequestUnits={}, tokens={}). Window RPM: {}/{} (limit: {}), TPM: {}/{} (limit: {})",
                        providerRequestUnits, estimatedTokens, currentRequests + providerRequestUnits, properties.getMaxRpm(), backgroundRpmLimit,
                        currentTokens + estimatedTokens, properties.getMaxTpm(), backgroundTpmLimit);
                return;
            }

            waitMs = calculateBackgroundWaitMs(providerRequestUnits, estimatedTokens, now);
        } finally {
            lock.unlock();
        }

        // Small inline quota wait optimization (<= 2000ms, once per batch)
        if (waitMs <= 2000L) {
            log.info("Background quota near capacity, performing short inline wait of {}ms (providerRequestUnits={}, tokens={})",
                    waitMs, providerRequestUnits, estimatedTokens);
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new QuotaBackpressureException(waitMs, "Interrupted during inline quota wait");
            }

            // Re-enter limiter and recompute quota from scratch atomically
            lock.lock();
            try {
                long now = System.currentTimeMillis();
                pruneExpired(now);
                int backgroundRpmLimit = properties.getMaxRpm() - properties.getInteractiveHeadroom();
                int backgroundTpmLimit = properties.getMaxTpm() - properties.getInteractiveTpmHeadroom();
                int currentRequests = calculateTotalRequests();
                int currentTokens = calculateTotalTokens();

                if ((currentRequests + providerRequestUnits) <= backgroundRpmLimit
                        && (currentTokens + estimatedTokens) <= backgroundTpmLimit) {
                    records.addLast(new AdmissionRecord(now, providerRequestUnits, estimatedTokens));
                    log.info("Background embedding admitted after inline wait (providerRequestUnits={}, tokens={}). Window RPM: {}/{}, TPM: {}/{}",
                            providerRequestUnits, estimatedTokens, currentRequests + providerRequestUnits, backgroundRpmLimit,
                            currentTokens + estimatedTokens, backgroundTpmLimit);
                    return;
                }

                long recalculatedWaitMs = calculateBackgroundWaitMs(providerRequestUnits, estimatedTokens, now);
                log.warn("Capacity unavailable after inline wait (RPM: {}/{}, TPM: {}/{}); yielding with waitMs={}",
                        currentRequests + providerRequestUnits, backgroundRpmLimit, currentTokens + estimatedTokens, backgroundTpmLimit, recalculatedWaitMs);
                throw new QuotaBackpressureException(recalculatedWaitMs,
                        String.format("Background quota capacity exhausted after inline wait (waitMs=%d)", recalculatedWaitMs));
            } finally {
                lock.unlock();
            }
        } else {
            log.warn("Background quota limit reached; waitMs={} exceeds inline threshold 2000ms; yielding to RETRY_WAIT", waitMs);
            throw new QuotaBackpressureException(waitMs,
                    String.format("Background quota capacity exhausted (waitMs=%d)", waitMs));
        }
    }

    /**
     * Exact sliding-window wait calculation.
     * Determines when sufficient capacity actually becomes available for both RPM and TPM.
     * Must be called while holding `lock`.
     */
    public long calculateBackgroundWaitMs(int providerRequestUnits, int estimatedTokens, long now) {
        int backgroundRpmLimit = properties.getMaxRpm() - properties.getInteractiveHeadroom();
        int backgroundTpmLimit = properties.getMaxTpm() - properties.getInteractiveTpmHeadroom();

        int currentRequests = calculateTotalRequests();
        int currentTokens = calculateTotalTokens();

        int neededRequests = (currentRequests + providerRequestUnits) - backgroundRpmLimit;
        int neededTokens = (currentTokens + estimatedTokens) - backgroundTpmLimit;

        if (neededRequests <= 0 && neededTokens <= 0) {
            return 0L;
        }

        long requestWaitMs = 0L;
        if (neededRequests > 0) {
            int freed = 0;
            long targetExpiry = now + WINDOW_MS;
            for (AdmissionRecord rec : records) {
                freed += rec.providerRequestUnits();
                if (freed >= neededRequests) {
                    targetExpiry = rec.timestamp() + WINDOW_MS;
                    break;
                }
            }
            requestWaitMs = Math.max(0L, targetExpiry - now);
        }

        long tokenWaitMs = 0L;
        if (neededTokens > 0) {
            int freed = 0;
            long targetExpiry = now + WINDOW_MS;
            for (AdmissionRecord rec : records) {
                freed += rec.tokens();
                if (freed >= neededTokens) {
                    targetExpiry = rec.timestamp() + WINDOW_MS;
                    break;
                }
            }
            tokenWaitMs = Math.max(0L, targetExpiry - now);
        }

        long waitMs = Math.max(requestWaitMs, tokenWaitMs);
        return Math.max(waitMs, 50L);
    }

    /**
     * Normal pacing for background ingestion (single request unit): waits up to maxWaitMs for sliding-window capacity.
     */
    public boolean acquireBackgroundWithPacing(int estimatedTokens, long maxWaitMs) {
        return acquireBackgroundWithPacing(1, estimatedTokens, maxWaitMs);
    }

    /**
     * Normal pacing for background ingestion: waits up to maxWaitMs for sliding-window capacity
     * to become available instead of immediately throwing an exception or thrashing RETRY_WAIT.
     *
     * @param providerRequestUnits number of provider quota units in the batch (e.g. batch.size())
     * @param estimatedTokens tokens needed for the batch
     * @param maxWaitMs maximum time in milliseconds to wait before giving up
     * @return true if acquired within maxWaitMs, false if capacity could not be acquired
     */
    public boolean acquireBackgroundWithPacing(int providerRequestUnits, int estimatedTokens, long maxWaitMs) {
        if (maxWaitMs <= 0) {
            return tryAcquireBackground(providerRequestUnits, estimatedTokens);
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

                if ((currentRequests + providerRequestUnits) <= backgroundRpmLimit
                        && (currentTokens + estimatedTokens) <= backgroundTpmLimit) {
                    records.addLast(new AdmissionRecord(now, providerRequestUnits, estimatedTokens));
                    log.info("Background embedding admitted with pacing (providerRequestUnits={}, tokens={}). Window RPM: {}/{}, TPM: {}/{}",
                            providerRequestUnits, estimatedTokens, currentRequests + providerRequestUnits, backgroundRpmLimit,
                            currentTokens + estimatedTokens, backgroundTpmLimit);
                    return true;
                }

                long remaining = deadline - now;
                if (remaining <= 0) {
                    log.warn("Background pacing timeout after {}ms (RPM: {}/{}, TPM: {}/{}). Yielding to RETRY_WAIT.",
                            maxWaitMs, currentRequests + providerRequestUnits, backgroundRpmLimit,
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
            sum += rec.providerRequestUnits();
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
