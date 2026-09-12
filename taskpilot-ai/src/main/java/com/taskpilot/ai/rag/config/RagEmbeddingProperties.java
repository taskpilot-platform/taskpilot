package com.taskpilot.ai.rag.config;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Component
@ConfigurationProperties(prefix = "rag.embedding")
public class RagEmbeddingProperties {

    /**
     * Maximum embedding requests per minute across the application.
     */
    private int maxRpm = 80;

    /**
     * Capacity reserved exclusively for high-priority interactive search traffic.
     */
    private int interactiveHeadroom = 10;

    /**
     * Maximum estimated tokens per minute across the application (e.g. Gemini 30k TPM quota).
     */
    private int maxTpm = 30000;

    /**
     * Token capacity reserved exclusively for high-priority interactive search queries.
     */
    private int interactiveTpmHeadroom = 2000;

    /**
     * Maximum duration in milliseconds a worker thread can wait for normal pacing sliding-window capacity
     * before yielding into RETRY_WAIT.
     */
    private long pacingWaitMs = 10000L;

    /**
     * Maximum number of text segments allowed in a single embedding batch request.
     * Set conservatively to 20 to stay well within provider free-tier request and token limits per minute.
     */
    private int maxBatchSize = 20;

    /**
     * Ingestion job lease duration in minutes before a PROCESSING document is considered stale and reclaimable.
     */
    private int leaseDurationMinutes = 10;

    /**
     * Maximum number of retry attempts following an initial failed attempt.
     */
    private int maxRetryAttempts = 5;

    /**
     * Ingestion poller fixed delay interval in milliseconds.
     */
    private long pollIntervalMs = 3000;

    public RagEmbeddingProperties(int maxRpm, int interactiveHeadroom, int maxBatchSize, int leaseDurationMinutes, int maxRetryAttempts, long pollIntervalMs) {
        this.maxRpm = maxRpm;
        this.interactiveHeadroom = interactiveHeadroom;
        this.maxTpm = 30000;
        this.interactiveTpmHeadroom = 2000;
        this.pacingWaitMs = 10000L;
        this.maxBatchSize = maxBatchSize;
        this.leaseDurationMinutes = leaseDurationMinutes;
        this.maxRetryAttempts = maxRetryAttempts;
        this.pollIntervalMs = pollIntervalMs;
    }

    @PostConstruct
    public void validate() {
        if (maxRpm <= 0) {
            throw new IllegalStateException("rag.embedding.max-rpm must be greater than 0, got: " + maxRpm);
        }
        if (interactiveHeadroom < 0) {
            throw new IllegalStateException("rag.embedding.interactive-headroom must be >= 0, got: " + interactiveHeadroom);
        }
        if (interactiveHeadroom >= maxRpm) {
            throw new IllegalStateException(String.format(
                    "rag.embedding.interactive-headroom (%d) must be strictly less than max-rpm (%d)",
                    interactiveHeadroom, maxRpm));
        }
        if (maxTpm <= 0) {
            throw new IllegalStateException("rag.embedding.max-tpm must be greater than 0, got: " + maxTpm);
        }
        if (interactiveTpmHeadroom < 0) {
            throw new IllegalStateException("rag.embedding.interactive-tpm-headroom must be >= 0, got: " + interactiveTpmHeadroom);
        }
        if (interactiveTpmHeadroom >= maxTpm) {
            throw new IllegalStateException(String.format(
                    "rag.embedding.interactive-tpm-headroom (%d) must be strictly less than max-tpm (%d)",
                    interactiveTpmHeadroom, maxTpm));
        }
        if (pacingWaitMs < 0) {
            throw new IllegalStateException("rag.embedding.pacing-wait-ms must be >= 0, got: " + pacingWaitMs);
        }
        if (maxBatchSize < 1 || maxBatchSize > 100) {
            throw new IllegalStateException(String.format(
                    "rag.embedding.max-batch-size must be between 1 and 100, got: %d", maxBatchSize));
        }
        if (leaseDurationMinutes <= 0) {
            throw new IllegalStateException("rag.embedding.lease-duration-minutes must be greater than 0, got: " + leaseDurationMinutes);
        }
        if (maxRetryAttempts < 0) {
            throw new IllegalStateException("rag.embedding.max-retry-attempts must be >= 0, got: " + maxRetryAttempts);
        }
        if (pollIntervalMs <= 0) {
            throw new IllegalStateException("rag.embedding.poll-interval-ms must be greater than 0, got: " + pollIntervalMs);
        }
    }
}
