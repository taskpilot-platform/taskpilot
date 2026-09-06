package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.config.RagEmbeddingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single application-level choke point for all embedding traffic.
 * Governs shared RPM quota admission between high-priority interactive search
 * and lower-priority background ingestion batches.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingGateway {

    private final EmbeddingService embeddingService;
    private final RpmRateLimiter rpmRateLimiter;
    private final RagEmbeddingProperties properties;

    /**
     * Interactive search embedding request.
     * Higher priority: checks admission against the shared global limit.
     */
    public float[] embedForSearch(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Search query text must not be null or blank");
        }

        if (!rpmRateLimiter.tryAcquireInteractive()) {
            throw new QuotaExceededException("Embedding quota exhausted for interactive search. Please retry shortly.");
        }

        log.debug("Dispatching interactive embedding request to provider");
        return embeddingService.embedText(text);
    }

    /**
     * Background document ingestion embedding request.
     * Enforces that sub-batches never exceed maxBatchSize (<= 100).
     * Enforces rate limiting per sub-batch, respecting interactive headroom.
     */
    public List<float[]> embedForIngestion(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        int maxBatchSize = properties.getMaxBatchSize();
        List<float[]> allEmbeddings = new ArrayList<>(texts.size());

        for (int i = 0; i < texts.size(); i += maxBatchSize) {
            int end = Math.min(i + maxBatchSize, texts.size());
            List<String> batch = texts.subList(i, end);

            if (!rpmRateLimiter.tryAcquireBackground()) {
                throw new QuotaExceededException(String.format(
                        "Embedding quota limit reached for background ingestion (batch size: %d). Yielding for backoff.",
                        batch.size()));
            }

            log.info("Dispatching background embedding batch to provider: offset={}, batchSize={}, total={}",
                    i, batch.size(), texts.size());
            List<float[]> batchEmbeddings = embeddingService.embedBatch(batch);
            if (batchEmbeddings.size() != batch.size()) {
                throw new IllegalStateException(String.format(
                        "Provider returned %d embeddings for batch of %d items", batchEmbeddings.size(), batch.size()));
            }
            allEmbeddings.addAll(batchEmbeddings);
        }

        return allEmbeddings;
    }

    public int getDimension() {
        return embeddingService.getDimension();
    }
}
