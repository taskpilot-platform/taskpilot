package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.domain.ScoredChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentDiversityContextSelectorTest {

    private DocumentDiversityContextSelector selector;

    @BeforeEach
    void setUp() {
        selector = new DocumentDiversityContextSelector();
    }

    @Test
    @DisplayName("Test A — Document Crowding: Doc 3 chunks capped at 2 in Pass 1, allowing Doc 9 chunk at rank #6 to enter final context")
    void testDocumentCrowding() {
        // Reproduce the exact real-world scenario:
        // Rank 1-5: Doc 3 chunks
        // Rank 6: Doc 9 chunk 517 (similarity 0.6603)
        // Rank 7-16: Doc 3 chunks
        List<ScoredChunk> candidates = new ArrayList<>();

        // Ranks 1 to 5 from Doc 3
        for (int i = 1; i <= 5; i++) {
            candidates.add(new ScoredChunk((long) i, 3L, 4L, i, "Doc 3 chunk " + i, 0.72 - (i * 0.01), "Doc3.pdf"));
        }

        // Rank 6: Target chunk from Doc 9
        ScoredChunk doc9Chunk517 = new ScoredChunk(1270L, 9L, 4L, 517, "Target OOAD Data Security and Privacy", 0.6603, "OOAD Report.docx");
        candidates.add(doc9Chunk517);

        // Ranks 7 to 16 from Doc 3
        for (int i = 7; i <= 16; i++) {
            candidates.add(new ScoredChunk((long) (i + 100), 3L, 4L, i, "Doc 3 chunk " + i, 0.65 - (i * 0.01), "Doc3.pdf"));
        }

        int maxContext = 6;
        int maxPerDocument = 2;

        List<ScoredChunk> selected = selector.selectProjectContext(candidates, maxContext, maxPerDocument);

        // Assert final context size
        assertThat(selected).hasSize(6);

        // Assert Doc 3 does not monopolize all initial slots (only 2 in Pass 1)
        long doc3CountInFirst3 = selected.subList(0, 3).stream().filter(c -> c.documentId().equals(3L)).count();
        assertThat(doc3CountInFirst3).isEqualTo(2);

        // Assert Target Doc 9 chunk is present in final context (rescued by diversity policy)
        assertThat(selected).contains(doc9Chunk517);

        // Specifically verify that Doc 9 chunk appears at index 2 (after the first 2 Doc 3 chunks)
        assertThat(selected.get(2)).isEqualTo(doc9Chunk517);

        // And remaining slots 3, 4, 5 were backfilled from Doc 3 overflow
        assertThat(selected.get(3).documentId()).isEqualTo(3L);
        assertThat(selected.get(4).documentId()).isEqualTo(3L);
        assertThat(selected.get(5).documentId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Test B — Single-Document Starvation Prevention: Backfill pass fills remaining slots up to maxContext")
    void testSingleDocumentStarvationPrevention() {
        // 10 chunks, all from the same document (Doc 1)
        List<ScoredChunk> candidates = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            candidates.add(new ScoredChunk((long) i, 1L, 10L, i, "Single doc content " + i, 0.90 - (i * 0.02), "Single.pdf"));
        }

        int maxContext = 6;
        int maxPerDocument = 2;

        List<ScoredChunk> selected = selector.selectProjectContext(candidates, maxContext, maxPerDocument);

        // Assert that even though Doc 1 was capped at 2 in Pass 1, Pass 2 backfills to 6
        assertThat(selected).hasSize(6);
        assertThat(selected).extracting(ScoredChunk::chunkIndex).containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    @DisplayName("Test C — Underflow: Returns all available candidates without exception when count < maxContext")
    void testUnderflow() {
        List<ScoredChunk> candidates = List.of(
                new ScoredChunk(1L, 10L, 100L, 0, "Chunk 1", 0.85, "A.pdf"),
                new ScoredChunk(2L, 20L, 100L, 0, "Chunk 2", 0.80, "B.pdf"),
                new ScoredChunk(3L, 30L, 100L, 0, "Chunk 3", 0.75, "C.pdf")
        );

        int maxContext = 6;
        int maxPerDocument = 2;

        List<ScoredChunk> selected = selector.selectProjectContext(candidates, maxContext, maxPerDocument);

        assertThat(selected).hasSize(3);
        assertThat(selected).containsExactlyElementsOf(candidates);
    }

    @Test
    @DisplayName("Verify edge cases: null or empty candidate list, non-positive maxContext")
    void testEdgeCases() {
        assertThat(selector.selectProjectContext(null, 6, 2)).isEmpty();
        assertThat(selector.selectProjectContext(List.of(), 6, 2)).isEmpty();
        assertThat(selector.selectProjectContext(List.of(new ScoredChunk(1L, 1L, 1L, 0, "test", 0.5)), 0, 2)).isEmpty();
        assertThat(selector.selectProjectContext(List.of(new ScoredChunk(1L, 1L, 1L, 0, "test", 0.5)), -1, 2)).isEmpty();
    }
}
