package com.taskpilot.ai.rag.service;

/**
 * Thrown when local rate limiter sliding-window capacity is saturated and the worker must yield.
 * This is normal quota backpressure and MUST NOT increment the failure retry count.
 */
public class QuotaBackpressureException extends QuotaExceededException {

    private final long waitMs;

    public QuotaBackpressureException(long waitMs) {
        this(waitMs, String.format("Local quota capacity saturated (waitMs=%d)", waitMs));
    }

    public QuotaBackpressureException(long waitMs, String message) {
        super(message);
        this.waitMs = Math.max(1000L, waitMs);
    }

    public long getWaitMs() {
        return waitMs;
    }
}
