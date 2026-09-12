package com.taskpilot.ai.rag.service;

/**
 * Thrown when embedding quota/RPM limit is exhausted.
 * This is explicitly classified as a retryable error in the ingestion pipeline.
 */
public class QuotaExceededException extends RuntimeException {
    public QuotaExceededException(String message) {
        super(message);
    }
}
