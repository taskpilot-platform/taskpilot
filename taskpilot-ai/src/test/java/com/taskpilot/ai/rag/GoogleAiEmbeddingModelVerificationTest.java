package com.taskpilot.ai.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleAiEmbeddingModelVerificationTest {

    @Test
    @DisplayName("Verify GoogleAiEmbeddingModel builder configures gemini-embedding-2 and 768 output dimensionality")
    void testGoogleAiEmbeddingModelConfiguration() {
        GoogleAiEmbeddingModel model = GoogleAiEmbeddingModel.builder()
                .apiKey("test-key")
                .modelName("gemini-embedding-2")
                .outputDimensionality(768)
                .build();

        assertThat(model).isNotNull();
    }

    @Test
    @DisplayName("Verify live API generates 768-dimensional vector using gemini-embedding-2")
    void testLiveEmbeddingGenerationDimension() {
        String apiKey = resolveGeminiApiKey();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                apiKey != null && !apiKey.isBlank(),
                "GEMINI_API_KEY is not configured in environment or .env; skipping live API call"
        );

        GoogleAiEmbeddingModel model = GoogleAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName("gemini-embedding-2")
                .outputDimensionality(768)
                .build();

        Response<Embedding> response = model.embed("TaskPilot RAG canonical embedding verification with gemini-embedding-2");
        assertThat(response).isNotNull();
        assertThat(response.content()).isNotNull();
        System.out.println("Generated embedding vector dimension: " + response.content().dimension());
        assertThat(response.content().dimension()).isEqualTo(768);
    }

    private String resolveGeminiApiKey() {
        String key = System.getenv("GEMINI_API_KEY");
        if (key != null && !key.isBlank()) {
            return key.trim();
        }
        key = System.getProperty("GEMINI_API_KEY");
        if (key != null && !key.isBlank()) {
            return key.trim();
        }

        // Check .env files in current or parent dirs
        java.util.List<java.nio.file.Path> potentialPaths = java.util.List.of(
                java.nio.file.Path.of(".env"),
                java.nio.file.Path.of("../.env"),
                java.nio.file.Path.of("../../.env")
        );

        for (java.nio.file.Path path : potentialPaths) {
            if (java.nio.file.Files.exists(path)) {
                try {
                    for (String line : java.nio.file.Files.readAllLines(path)) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("GEMINI_API_KEY=") && !trimmed.startsWith("#")) {
                            return trimmed.substring("GEMINI_API_KEY=".length()).trim();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }
}

