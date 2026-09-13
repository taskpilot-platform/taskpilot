package com.taskpilot.ai.rag.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
class GeminiBatchQuotaExperimentTest {

    @Test
    void runControlledBatchExperiment() {
        String testKey = System.getProperty("gemini.test.api-key");
        if (testKey == null || testKey.isBlank()) {
            testKey = System.getenv("GEMINI_TEST_API_KEY");
        }
        if (testKey == null || testKey.isBlank()) {
            testKey = System.getenv("GEMINI_API_KEY");
        }

        assertThat(testKey)
                .withFailMessage("Test Gemini API key must be provided via -Dgemini.test.api-key or GEMINI_TEST_API_KEY or GEMINI_API_KEY")
                .isNotBlank();

        String maskedKey = testKey.length() > 10
                ? testKey.substring(0, 8) + "..." + testKey.substring(testKey.length() - 4)
                : "***";
        log.info("Starting controlled quota experiment with API key: {}", maskedKey);

        // Exactly 20 distinct chunk-like texts
        List<String> texts = IntStream.range(0, 20)
                .mapToObj(i -> "Controlled Gemini embedding quota test. " +
                        "This is input text number " + i + ". " +
                        "The purpose is to measure how one batch of multiple " +
                        "input texts affects provider request quota usage.")
                .toList();

        assertThat(texts).hasSize(20);

        int estimatedTokens = RpmRateLimiter.estimateTokens(texts);

        log.info("=== PRE-CALL APPLICATION METRICS ===");
        log.info("inputTextCount = {}", texts.size());
        log.info("batchSize = {}", texts.size());
        log.info("estimatedTokens = {}", estimatedTokens);
        log.info("requestCount passed to limiter = 1");

        // Provider configuration: single key, maxRetries = 0, no hidden retries
        GoogleAiEmbeddingModel rawModel = GoogleAiEmbeddingModel.builder()
                .apiKey(testKey)
                .modelName("gemini-embedding-2")
                .outputDimensionality(768)
                .maxRetries(0)
                .timeout(Duration.ofSeconds(60))
                .build();

        AtomicInteger providerCallCount = new AtomicInteger(0);

        EmbeddingModel instrumentedModel = new EmbeddingModel() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
                int count = providerCallCount.incrementAndGet();
                log.info(">>> [PROVIDER INVOCATION #{}] batchSize={}", count, textSegments.size());
                return rawModel.embedAll(textSegments);
            }

            @Override
            public Response<Embedding> embed(String text) {
                int count = providerCallCount.incrementAndGet();
                log.info(">>> [PROVIDER SINGLE INVOCATION #{}] length={}", count, text.length());
                return rawModel.embed(text);
            }

            @Override
            public Response<Embedding> embed(TextSegment textSegment) {
                int count = providerCallCount.incrementAndGet();
                log.info(">>> [PROVIDER SEGMENT INVOCATION #{}] length={}", count, textSegment.text().length());
                return rawModel.embed(textSegment);
            }
        };

        // Single key embedding service (no key rotation, no hidden retries)
        GoogleAiEmbeddingServiceImpl embeddingService = new GoogleAiEmbeddingServiceImpl(instrumentedModel, 768);

        log.info("Executing exactly ONE batch call to provider with 20 texts...");
        List<float[]> embeddings = embeddingService.embedBatch(texts);

        // Verification of execution isolation and safety rules
        assertThat(providerCallCount.get())
                .withFailMessage("SAFETY VIOLATION: Expected exactly 1 provider invocation, but got %d", providerCallCount.get())
                .isEqualTo(1);

        assertThat(embeddings).hasSize(20);
        for (int i = 0; i < embeddings.size(); i++) {
            float[] vec = embeddings.get(i);
            assertThat(vec).isNotNull();
            assertThat(vec.length)
                    .withFailMessage("Embedding at index %d has dimension %d, expected 768", i, vec.length)
                    .isEqualTo(768);
        }

        log.info("=== PROVIDER BATCH CALL COMPLETE ===");
        log.info("Returned embeddings: {}", embeddings.size());
        log.info("Embedding dimension: {}", embeddings.get(0).length);
        log.info("Actual provider HTTP invocations: {}", providerCallCount.get());
        log.info("Batch execution completed successfully with 0 retries and exactly 1 HTTP call.");
    }
}
