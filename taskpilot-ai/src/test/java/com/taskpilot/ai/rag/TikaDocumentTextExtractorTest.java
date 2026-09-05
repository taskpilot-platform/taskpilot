package com.taskpilot.ai.rag;

import com.taskpilot.ai.rag.service.TikaDocumentTextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TikaDocumentTextExtractorTest {

    private TikaDocumentTextExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new TikaDocumentTextExtractor();
    }

    @Test
    @DisplayName("Verify plain text extraction from stream")
    void testExtractPlainText() {
        String content = "Project TaskPilot requires OAuth2 authentication and pgvector RAG subsystem.";
        ByteArrayInputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        String result = extractor.extractText(stream, "spec.txt", "text/plain");
        assertThat(result).contains("OAuth2 authentication");
        assertThat(result).contains("pgvector RAG subsystem");
    }

    @Test
    @DisplayName("Verify markdown text extraction from stream")
    void testExtractMarkdownText() {
        String content = "# Architecture Document\n\nTaskPilot provides intelligent multi-agent task distribution.\n\n- Point 1\n- Point 2";
        ByteArrayInputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        String result = extractor.extractText(stream, "architecture.md", "text/markdown");
        assertThat(result).contains("Architecture Document");
        assertThat(result).contains("intelligent multi-agent");
    }

    @Test
    @DisplayName("Verify CSV text extraction from stream")
    void testExtractCsvText() {
        String content = "id,name,role\n1,Alice,Manager\n2,Bob,Developer\n";
        ByteArrayInputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        String result = extractor.extractText(stream, "team.csv", "text/csv");
        assertThat(result).contains("Alice");
        assertThat(result).contains("Bob");
    }

    @Test
    @DisplayName("Verify null stream throws IllegalArgumentException")
    void testExtractNullStream() {
        assertThatThrownBy(() -> extractor.extractText(null, "test.txt", "text/plain"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("InputStream must not be null");
    }
}
