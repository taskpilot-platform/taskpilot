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

    private final List<EmbeddingModel> embeddingModels;
    private final java.util.concurrent.atomic.AtomicInteger currentKeyIndex = new java.util.concurrent.atomic.AtomicInteger(0);
    private final int dimension;
    private final String modelName;

    @Autowired
    public GoogleAiEmbeddingServiceImpl(
            @Value("${ai.gemini.api-key:}") String apiKey,
            @Value("${ai.gemini.api-keys:}") String apiKeys,
            @Value("${ai.gemini.embedding-model:gemini-embedding-2}") String modelName,
            @Value("${ai.gemini.embedding-dimension:768}") int dimension) {
        this.modelName = modelName;
        this.dimension = dimension;

        List<String> effectiveKeys = resolveApiKeys(apiKey, apiKeys);
        if (effectiveKeys.isEmpty()) {
            log.warn("GEMINI_API_KEY is not configured. GoogleAiEmbeddingModel will fail if invoked.");
            this.embeddingModels = Collections.emptyList();
        } else {
            List<EmbeddingModel> models = new ArrayList<>();
            for (String key : effectiveKeys) {
                models.add(GoogleAiEmbeddingModel.builder()
                        .apiKey(key)
                        .modelName(modelName)
                        .outputDimensionality(dimension)
                        .maxRetries(0)
                        .timeout(Duration.ofSeconds(60))
                        .build());
            }
            this.embeddingModels = Collections.unmodifiableList(models);
            log.info("Initialized canonical GoogleAiEmbeddingModel with {} key(s): model={}, dimension={}, maxRetries=0",
                    models.size(), modelName, dimension);
        }
    }

    public GoogleAiEmbeddingServiceImpl(String apiKey, String modelName, int dimension) {
        this(apiKey, null, modelName, dimension);
    }

    public GoogleAiEmbeddingServiceImpl(EmbeddingModel embeddingModel, int dimension) {
        this.embeddingModels = List.of(embeddingModel);
        this.dimension = dimension;
        this.modelName = "gemini-embedding-2";
    }

    public EmbeddingModel getEmbeddingModel() {
        if (embeddingModels.isEmpty()) {
            return null;
        }
        int idx = Math.abs(currentKeyIndex.get() % embeddingModels.size());
        return embeddingModels.get(idx);
    }

    @Override
    public float[] embedText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Text to embed must not be null or blank");
        }
        ensureModelConfigured();

        int attempts = embeddingModels.size();
        Exception lastException = null;

        for (int i = 0; i < attempts; i++) {
            int modelIdx = Math.abs(currentKeyIndex.get() % embeddingModels.size());
            EmbeddingModel model = embeddingModels.get(modelIdx);
            try {
                Response<Embedding> response = model.embed(text);
                if (response == null || response.content() == null) {
                    throw new IllegalStateException("Embedding model returned empty response");
                }
                float[] vector = response.content().vector();
                validateDimension(vector);
                return vector;
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                lastException = e;
                if (attempts > 1) {
                    int nextIdx = Math.abs(currentKeyIndex.incrementAndGet() % embeddingModels.size());
                    log.warn("Gemini embedding attempt failed on key #{}: {}. Rotating to key #{} (attempt {} of {})",
                            modelIdx, e.getMessage(), nextIdx, i + 1, attempts);
                } else {
                    break;
                }
            }
        }

        log.error("Failed to generate embedding for text after {} attempts: model={}, error={}",
                attempts, modelName, lastException != null ? lastException.getMessage() : "unknown");
        throw new RuntimeException("Embedding generation failed: " + (lastException != null ? lastException.getMessage() : "unknown"), lastException);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }
        ensureModelConfigured();

        List<TextSegment> segments = texts.stream()
                .map(TextSegment::from)
                .toList();

        int attempts = embeddingModels.size();
        Exception lastException = null;

        for (int i = 0; i < attempts; i++) {
            int modelIdx = Math.abs(currentKeyIndex.get() % embeddingModels.size());
            EmbeddingModel model = embeddingModels.get(modelIdx);
            try {
                Response<List<Embedding>> response = model.embedAll(segments);
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
                lastException = e;
                if (attempts > 1) {
                    int nextIdx = Math.abs(currentKeyIndex.incrementAndGet() % embeddingModels.size());
                    log.warn("Gemini batch embedding attempt failed on key #{}: {}. Rotating to key #{} (attempt {} of {})",
                            modelIdx, e.getMessage(), nextIdx, i + 1, attempts);
                } else {
                    break;
                }
            }
        }

        log.error("Failed to generate batch embeddings after {} attempts: model={}, count={}, error={}",
                attempts, modelName, texts.size(), lastException != null ? lastException.getMessage() : "unknown");
        throw new RuntimeException("Batch embedding generation failed: " + (lastException != null ? lastException.getMessage() : "unknown"), lastException);
    }

    @Override
    public int getDimension() {
        return dimension;
    }

    private void ensureModelConfigured() {
        if (embeddingModels.isEmpty()) {
            throw new IllegalStateException("Embedding model is not configured (missing GEMINI_API_KEY)");
        }
    }

    private void validateDimension(float[] vector) {
        if (vector == null || vector.length != dimension) {
            int actual = vector == null ? 0 : vector.length;
            throw new IllegalStateException("Embedding dimension mismatch: expected " + dimension + ", got " + actual);
        }
    }

    private static List<String> resolveApiKeys(String primary, String multi) {
        java.util.LinkedHashSet<String> keySet = new java.util.LinkedHashSet<>();
        addKeys(keySet, primary);
        addKeys(keySet, multi);
        addKeys(keySet, System.getenv("GEMINI_API_KEY"));
        addKeys(keySet, System.getenv("GEMINI_API_KEYS"));
        addKeys(keySet, System.getProperty("GEMINI_API_KEY"));
        addKeys(keySet, System.getProperty("GEMINI_API_KEYS"));
        return new ArrayList<>(keySet);
    }

    private static void addKeys(java.util.Set<String> target, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                target.add(trimmed);
            }
        }
    }
}
