package com.taskpilot.ai.rag.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class GoogleAiEmbeddingServiceImpl implements EmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final int dimension;
    private final String modelName;

    @Autowired
    public GoogleAiEmbeddingServiceImpl(
            @Value("${ai.gemini.api-key:}") String apiKey,
            @Value("${ai.gemini.embedding-model:gemini-embedding-2}") String modelName,
            @Value("${ai.gemini.embedding-dimension:768}") int dimension) {
        this.modelName = modelName;
        this.dimension = dimension;

        String effectiveKey = resolveApiKey(apiKey);
        if (effectiveKey == null || effectiveKey.isBlank()) {
            log.warn("GEMINI_API_KEY is not configured. GoogleAiEmbeddingModel will fail if invoked.");
            this.embeddingModel = null;
        } else {
            this.embeddingModel = GoogleAiEmbeddingModel.builder()
                    .apiKey(effectiveKey)
                    .modelName(modelName)
                    .outputDimensionality(dimension)
                    .timeout(Duration.ofSeconds(60))
                    .build();
            log.info("Initialized canonical GoogleAiEmbeddingModel: model={}, dimension={}", modelName, dimension);
        }
    }

    public GoogleAiEmbeddingServiceImpl(EmbeddingModel embeddingModel, int dimension) {
        this.embeddingModel = embeddingModel;
        this.dimension = dimension;
        this.modelName = "gemini-embedding-2";
    }

    @Override
    public float[] embedText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Text to embed must not be null or blank");
        }
        ensureModelConfigured();

        try {
            Response<Embedding> response = embeddingModel.embed(text);
            if (response == null || response.content() == null) {
                throw new IllegalStateException("Embedding model returned empty response");
            }
            float[] vector = response.content().vector();
            validateDimension(vector);
            return vector;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate embedding for text: model={}, error={}", modelName, e.getMessage());
            throw new RuntimeException("Embedding generation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }
        ensureModelConfigured();

        try {
            List<TextSegment> segments = texts.stream()
                    .map(TextSegment::from)
                    .toList();

            Response<List<Embedding>> response = embeddingModel.embedAll(segments);
            if (response == null || response.content() == null) {
                throw new IllegalStateException("Batch embedding returned empty response");
            }

            List<float[]> results = new ArrayList<>(response.content().size());
            for (Embedding emb : response.content()) {
                float[] vec = emb.vector();
                validateDimension(vec);
                results.add(vec);
            }
            return results;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate batch embeddings: model={}, count={}, error={}",
                    modelName, texts.size(), e.getMessage());
            throw new RuntimeException("Batch embedding generation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int getDimension() {
        return dimension;
    }

    private void ensureModelConfigured() {
        if (embeddingModel == null) {
            throw new IllegalStateException("Embedding model is not configured (missing GEMINI_API_KEY)");
        }
    }

    private void validateDimension(float[] vector) {
        if (vector == null || vector.length != dimension) {
            int actual = vector == null ? 0 : vector.length;
            throw new IllegalStateException("Embedding dimension mismatch: expected " + dimension + ", got " + actual);
        }
    }

    private static String resolveApiKey(String primary) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        String envKey = System.getenv("GEMINI_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey.trim();
        }
        return System.getProperty("GEMINI_API_KEY");
    }
}
