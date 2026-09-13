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

        // Next background call throws QuotaBackpressureException
        assertThatThrownBy(() -> gateway.embedForIngestion(List.of("text 1")))
                .isInstanceOf(QuotaBackpressureException.class)
                .hasMessageContaining("quota capacity exhausted");

        verifyNoInteractions(embeddingService);
    }

    @Test
    @DisplayName("Step 5 Test A — Provider unit accounting: batchSize=20 passes providerRequestUnits=20 to limiter, not 1")
    void testA_providerUnitAccounting() {
        RpmRateLimiter mockLimiter = mock(RpmRateLimiter.class);
        EmbeddingGateway gateway = new EmbeddingGateway(embeddingService, mockLimiter, properties);

        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            texts.add("text " + i);
        }

        when(embeddingService.embedBatch(anyList())).thenAnswer(inv -> {
            List<?> b = inv.getArgument(0);
            List<float[]> res = new ArrayList<>();
            for (int i = 0; i < b.size(); i++) res.add(new float[768]);
            return res;
        });

        gateway.embedForIngestion(texts);

        // Verify the limiter receives providerRequestUnits = 20, NOT 1
        verify(mockLimiter, times(1)).acquireBackgroundOrYield(eq(20), anyInt());
        verify(mockLimiter, never()).acquireBackgroundOrYield(eq(1), anyInt());
    }

    @Test
    @DisplayName("Step 5 Test B — Multi-input batch: 20 texts -> 1 HTTP batchEmbedContents call -> 20 embeddings returned")
    void testB_multiInputBatch() {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            texts.add("text item " + i);
        }

        when(embeddingService.embedBatch(anyList())).thenAnswer(inv -> {
            List<?> b = inv.getArgument(0);
            List<float[]> res = new ArrayList<>();
            for (int i = 0; i < b.size(); i++) res.add(new float[768]);
            return res;
        });

        List<float[]> results = embeddingGateway.embedForIngestion(texts);

        // 1 HTTP invocation to provider
        verify(embeddingService, times(1)).embedBatch(eq(texts));
        // 20 embeddings returned
        assertThat(results).hasSize(20);
    }

    @Test
    @DisplayName("Step 5 Test C — RPM accounting: 20-text batch consumes 20 RPM units, not 1")
    void testC_rpmAccounting() {
        // maxRpm = 25, interactiveHeadroom = 5 -> backgroundRpmLimit = 20
        RagEmbeddingProperties props = new RagEmbeddingProperties(25, 5, 100, 10, 5, 3000L);
        RpmRateLimiter limiter = new RpmRateLimiter(props);

        // First batch of 20 items: consumes 20 RPM units -> exactly fills background limit of 20
        boolean batch1Admitted = limiter.tryAcquireBackground(20, 100);
        assertThat(batch1Admitted).as("First batch of 20 should be admitted").isTrue();

        // Second batch of 10 items: needs 10 RPM units (current 20 + 10 = 30 > 20 limit) -> rejected!
        // (Note: if batch 1 had consumed only 1 unit, 1 + 10 = 11 <= 20, it would have been admitted)
        boolean batch2Admitted = limiter.tryAcquireBackground(10, 100);
        assertThat(batch2Admitted).as("Second batch must be rejected because batch 1 consumed 20 RPM units").isFalse();
    }

    @Test
    @DisplayName("Step 5 Test D — TPM accounting: token usage enforced independently of RPM units")
    void testD_tpmAccounting() {
        // maxRpm = 100, maxTpm = 2000, interactiveTpmHeadroom = 500 -> backgroundTpmLimit = 1500
        RagEmbeddingProperties props = new RagEmbeddingProperties(100, 10, 100, 10, 5, 3000L);
        props.setMaxTpm(2000);
        props.setInteractiveTpmHeadroom(500);
        RpmRateLimiter limiter = new RpmRateLimiter(props);

        // 5 providerRequestUnits (well under RPM limit of 90) but 1600 estimated tokens (exceeds TPM limit of 1500)
        boolean admitted = limiter.tryAcquireBackground(5, 1600);
        assertThat(admitted).as("Batch exceeding TPM limit must be rejected even with ample RPM headroom").isFalse();

        // Within TPM limit (e.g. 1200 tokens)
        boolean withinLimit = limiter.tryAcquireBackground(5, 1200);
        assertThat(withinLimit).as("Batch within TPM limit should be admitted").isTrue();
    }
}
