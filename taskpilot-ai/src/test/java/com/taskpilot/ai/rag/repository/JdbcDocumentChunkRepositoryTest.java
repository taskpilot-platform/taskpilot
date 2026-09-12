package com.taskpilot.ai.rag.repository;

import com.taskpilot.ai.rag.domain.DocumentChunk;
import com.taskpilot.ai.rag.domain.ScoredChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JdbcDocumentChunkRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private JdbcDocumentChunkRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcDocumentChunkRepository(jdbcTemplate);
    }

    @Test
    @DisplayName("Verify toVectorString formats float array into PostgreSQL vector literal format")
    void testToVectorString() {
        float[] vector = new float[]{0.15f, -0.85f, 0.0f, 1.2345f};
        String formatted = JdbcDocumentChunkRepository.toVectorString(vector);

        assertThat(formatted).isEqualTo("[0.15,-0.85,0.0,1.2345]");
        assertThat(JdbcDocumentChunkRepository.toVectorString(null)).isEqualTo("[]");
        assertThat(JdbcDocumentChunkRepository.toVectorString(new float[0])).isEqualTo("[]");
    }

    @Test
    @DisplayName("Verify saveAll delegates to jdbcTemplate batchUpdate")
    void testSaveAll() {
        DocumentChunk chunk = new DocumentChunk(
                null,
                1L,
                10L,
                0,
                "Content sample",
                new float[]{0.1f, 0.2f},
                Instant.now()
        );

        repository.saveAll(List.of(chunk));

        verify(jdbcTemplate).batchUpdate(
                anyString(),
                anyList(),
                eq(1),
                any()
        );
    }

    @Test
    @DisplayName("Verify saveAll handles null and empty list gracefully")
    void testSaveAllNullEmpty() {
        repository.saveAll(null);
        repository.saveAll(List.of());
    }

    @Test
    @DisplayName("Verify findNearestChunks input validations")
    void testFindNearestChunksValidations() {
        assertThatThrownBy(() -> repository.findNearestChunks(null, new float[]{0.1f}, 5, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectId must not be null");

        assertThatThrownBy(() -> repository.findNearestChunks(1L, null, 5, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queryVector must not be null or empty");

        assertThatThrownBy(() -> repository.findNearestChunks(1L, new float[0], 5, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queryVector must not be null or empty");
    }

    @Test
    @DisplayName("Verify findNearestChunks filters by minScore")
    @SuppressWarnings("unchecked")
    void testFindNearestChunksFiltering() {
        ScoredChunk high = new ScoredChunk(1L, 10L, 100L, 0, "High match", 0.85);
        ScoredChunk low = new ScoredChunk(2L, 10L, 100L, 1, "Low match", 0.40);

        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(high, low));

        List<ScoredChunk> results = repository.findNearestChunks(100L, new float[]{0.1f, 0.2f}, 10, 0.70);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).chunkId()).isEqualTo(1L);
        assertThat(results.get(0).similarity()).isEqualTo(0.85);
    }

    @Test
    @DisplayName("Verify deleteByDocumentId and deleteByProjectId execute SQL delete")
    void testDeletions() {
        when(jdbcTemplate.update(contains("WHERE document_id = ?"), eq(5L))).thenReturn(3);
        when(jdbcTemplate.update(contains("WHERE project_id = ?"), eq(10L))).thenReturn(6);

        repository.deleteByDocumentId(5L);
        repository.deleteByProjectId(10L);

        verify(jdbcTemplate).update(contains("WHERE document_id = ?"), eq(5L));
        verify(jdbcTemplate).update(contains("WHERE project_id = ?"), eq(10L));
    }
}
