package com.taskpilot.ai.rag;

import com.taskpilot.ai.rag.service.GoogleAiEmbeddingServiceImpl;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleAiEmbeddingServiceImplTest {

    @Mock
    private EmbeddingModel mockEmbeddingModel;

    @Test
    @DisplayName("Verify embedText returns 768-dimensional float vector on success")
    void testEmbedTextSuccess() {
        float[] sampleVector = new float[768];
        sampleVector[0] = 0.42f;
        sampleVector[767] = -0.18f;

        when(mockEmbeddingModel.embed("TaskPilot RAG test"))
                .thenReturn(Response.from(Embedding.from(sampleVector)));

        GoogleAiEmbeddingServiceImpl service = new GoogleAiEmbeddingServiceImpl(mockEmbeddingModel, 768);

        float[] result = service.embedText("TaskPilot RAG test");

        assertThat(result).isNotNull();
        assertThat(result.length).isEqualTo(768);
        assertThat(result[0]).isEqualTo(0.42f);
        assertThat(result[767]).isEqualTo(-0.18f);
        verify(mockEmbeddingModel).embed("TaskPilot RAG test");
    }

    @Test
    @DisplayName("Verify embedText rejects null or blank input")
    void testEmbedTextValidation() {
        GoogleAiEmbeddingServiceImpl service = new GoogleAiEmbeddingServiceImpl(mockEmbeddingModel, 768);

        assertThatThrownBy(() -> service.embedText(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null or blank");

        assertThatThrownBy(() -> service.embedText("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null or blank");
    }

    @Test
    @DisplayName("Verify embedText fails fast on dimensionality mismatch")
    void testEmbedTextDimensionMismatch() {
        float[] wrongDimensionVector = new float[1536];

        when(mockEmbeddingModel.embed("mismatch test"))
                .thenReturn(Response.from(Embedding.from(wrongDimensionVector)));

        GoogleAiEmbeddingServiceImpl service = new GoogleAiEmbeddingServiceImpl(mockEmbeddingModel, 768);

        assertThatThrownBy(() -> service.embedText("mismatch test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Embedding dimension mismatch: expected 768, got 1536");
    }

    @Test
    @DisplayName("Verify embedBatch handles empty list and batch segment embedding")
    void testEmbedBatch() {
        GoogleAiEmbeddingServiceImpl service = new GoogleAiEmbeddingServiceImpl(mockEmbeddingModel, 768);

        assertThat(service.embedBatch(null)).isEmpty();
        assertThat(service.embedBatch(List.of())).isEmpty();

        float[] vec1 = new float[768];
        float[] vec2 = new float[768];
        vec1[0] = 0.1f;
        vec2[0] = 0.2f;

        when(mockEmbeddingModel.embedAll(anyList()))
                .thenReturn(Response.from(List.of(Embedding.from(vec1), Embedding.from(vec2))));

        List<float[]> results = service.embedBatch(List.of("chunk 1", "chunk 2"));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).length).isEqualTo(768);
        assertThat(results.get(1).length).isEqualTo(768);
    }

    @Test
    @DisplayName("Verify unconfigured model throws informative IllegalStateException")
    void testUnconfiguredModel() {
        GoogleAiEmbeddingServiceImpl unconfigured = new GoogleAiEmbeddingServiceImpl("", "gemini-embedding-2", 768);

        assertThat(unconfigured.getDimension()).isEqualTo(768);
        assertThatThrownBy(() -> unconfigured.embedText("hello"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing GEMINI_API_KEY");
    }
}
