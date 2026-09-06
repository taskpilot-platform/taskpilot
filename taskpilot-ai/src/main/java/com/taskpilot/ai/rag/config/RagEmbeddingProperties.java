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
    private int maxRpm = 12;

    /**
     * Capacity reserved exclusively for high-priority interactive search traffic.
     */
    private int interactiveHeadroom = 2;

    /**
     * Maximum number of text segments allowed in a single embedding batch request.
     * Google Gemini provider enforces an upper bound of 100 segments.
     */
    private int maxBatchSize = 100;

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
