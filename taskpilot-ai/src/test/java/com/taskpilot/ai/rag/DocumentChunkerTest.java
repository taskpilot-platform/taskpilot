package com.taskpilot.ai.rag;

import com.taskpilot.ai.rag.service.LangChain4jDocumentChunker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentChunkerTest {

    private LangChain4jDocumentChunker chunker;

    @BeforeEach
    void setUp() {
        chunker = new LangChain4jDocumentChunker(700, 100);
    }

    @Test
    @DisplayName("Verify empty or null text returns empty chunk list")
    void testEmptyText() {
        assertThat(chunker.chunkText(null)).isEmpty();
        assertThat(chunker.chunkText("   ")).isEmpty();
    }

    @Test
    @DisplayName("Verify small text returns single chunk")
    void testSmallText() {
        String text = "TaskPilot is an AI-powered project management platform.";
        List<String> chunks = chunker.chunkText(text);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo(text);
    }

    @Test
    @DisplayName("Verify long document is split into recursive chunks respecting bounds")
    void testLongDocumentSplitting() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            sb.append("Section ").append(i).append(": TaskPilot RAG architecture specifies project-scoped knowledge retrieval. ")
              .append("All vector embeddings are generated via canonical gemini-embedding-2. ")
              .append("Security checks must precede vector retrieval with 403 Forbidden enforcement.\n\n");
        }

        String longText = sb.toString();
        List<String> chunks = chunker.chunkText(longText);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.size()).isGreaterThan(1);

        for (String chunk : chunks) {
            assertThat(chunk.length()).isLessThanOrEqualTo(750); // Allow slight leeway on word boundary
            assertThat(chunk).isNotBlank();
        }
    }
}
