package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.config.RagEmbeddingProperties;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmbeddingGatewayAndBatchTest {

    @Mock
    private EmbeddingService embeddingService;

    private RagEmbeddingProperties properties;
    private RpmRateLimiter rateLimiter;
    private EmbeddingGateway embeddingGateway;

    @BeforeEach
    void setUp() {
        properties = new RagEmbeddingProperties(500, 10, 100, 10, 5, 3000L);
        rateLimiter = new RpmRateLimiter(properties);
        embeddingGateway = new EmbeddingGateway(embeddingService, rateLimiter, properties);
    }

    @Test
    @DisplayName("TEST 10 — Embedding Batch Limit: Segments > 100 are partitioned into batches of <= 100")
    @SuppressWarnings("unchecked")
    void testEmbeddingBatchLimitEnforcement() {
        // GIVEN: 235 text segments
        int totalChunks = 235;
        List<String> chunks = new ArrayList<>(totalChunks);
        for (int i = 0; i < totalChunks; i++) {
            chunks.add("Document chunk content " + i);
        }

        when(embeddingService.embedBatch(anyList())).thenAnswer(invocation -> {
            List<String> batch = invocation.getArgument(0);
            List<float[]> result = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                result.add(new float[768]);
            }
            return result;
        });

        // WHEN: Calling embedForIngestion
        List<float[]> embeddings = embeddingGateway.embedForIngestion(chunks);

        // THEN: Verify all embeddings returned
        assertThat(embeddings).hasSize(totalChunks);

        // Verify provider was invoked 3 times with sizes: 100, 100, 35 (all <= maxBatchSize)
        ArgumentCaptor<List<String>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingService, times(3)).embedBatch(batchCaptor.capture());

        List<List<String>> capturedBatches = batchCaptor.getAllValues();
        assertThat(capturedBatches.get(0)).hasSize(100);
        assertThat(capturedBatches.get(1)).hasSize(100);
        assertThat(capturedBatches.get(2)).hasSize(35);

        for (List<String> batch : capturedBatches) {
            assertThat(batch.size()).isLessThanOrEqualTo(100);
        }
    }

    @Test
    @DisplayName("TEST 11 — SDK Retry Disabled: GoogleAiEmbeddingModel has maxRetries == 0")
    void testSdkRetriesDisabled() throws Exception {
        GoogleAiEmbeddingServiceImpl service = new GoogleAiEmbeddingServiceImpl(
                "fake-api-key",
                "gemini-embedding-2",
                768
        );

        EmbeddingModel model = service.getEmbeddingModel();
        assertThat(model).isNotNull();
        assertThat(model).isInstanceOf(GoogleAiEmbeddingModel.class);

        // Inspect internal maxRetries field via reflection
        Field maxRetriesField = GoogleAiEmbeddingModel.class.getDeclaredField("maxRetries");
        maxRetriesField.setAccessible(true);
        Integer maxRetries = (Integer) maxRetriesField.get(model);

        assertThat(maxRetries)
                .as("LangChain4j GoogleAiEmbeddingModel internal retries must be strictly 0")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("Shared RPM Invariant: Background quota is capped at (maxRpm - interactiveHeadroom) while interactive can use headroom")
    void testSharedRpmQuotaAccounting() {
        // Properties: maxRpm = 12, interactiveHeadroom = 2 -> backgroundLimit = 10
        RagEmbeddingProperties quotaProps = new RagEmbeddingProperties(12, 2, 100, 10, 5, 3000L);
        RpmRateLimiter limiter = new RpmRateLimiter(quotaProps);

        // 10 background requests succeed
        for (int i = 0; i < 10; i++) {
            boolean acquired = limiter.tryAcquireBackground();
            assertThat(acquired).as("Background request %d should be admitted", i + 1).isTrue();
        }

        // 11th background request is REJECTED because interactive headroom (2 slots) is reserved
        boolean backgroundBlocked = limiter.tryAcquireBackground();
        assertThat(backgroundBlocked).as("11th background request must be rejected to protect headroom").isFalse();

        // But INTERACTIVE search can still be admitted into the reserved headroom!
        boolean interactive1 = limiter.tryAcquireInteractive();
        assertThat(interactive1).as("Interactive request 1 into headroom must succeed").isTrue();

        boolean interactive2 = limiter.tryAcquireInteractive();
        assertThat(interactive2).as("Interactive request 2 into headroom must succeed").isTrue();

        // Now total 12 RPM reached: both background and interactive are rejected
        assertThat(limiter.tryAcquireInteractive()).as("13th total request must be rejected").isFalse();
        assertThat(limiter.tryAcquireBackground()).as("Background request after quota filled must be rejected").isFalse();
    }

    @Test
    @DisplayName("EmbeddingGateway rejects background requests with QuotaExceededException when RPM limit reached")
    void testGatewayQuotaExceededBehavior() {
        RagEmbeddingProperties quotaProps = new RagEmbeddingProperties(12, 2, 100, 10, 5, 3000L);
        RpmRateLimiter limiter = new RpmRateLimiter(quotaProps);
        EmbeddingGateway gateway = new EmbeddingGateway(embeddingService, limiter, quotaProps);

        // Exhaust background capacity (10 slots)
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquireBackground()).isTrue();
        }

        // Next background call throws QuotaExceededException
        assertThatThrownBy(() -> gateway.embedForIngestion(List.of("text 1")))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("quota limit reached for background ingestion");

        verifyNoInteractions(embeddingService);
    }
}
