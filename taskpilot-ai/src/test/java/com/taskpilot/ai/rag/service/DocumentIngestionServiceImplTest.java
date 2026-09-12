package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.config.RagEmbeddingProperties;
import com.taskpilot.ai.rag.domain.DocumentStatus;
import com.taskpilot.ai.rag.domain.StagedChunk;
import com.taskpilot.ai.rag.entity.DocumentEntity;
import com.taskpilot.ai.rag.repository.DocumentChunkRepository;
import com.taskpilot.ai.rag.repository.DocumentChunkStagingRepository;
import com.taskpilot.ai.rag.repository.DocumentRepository;
import com.taskpilot.infrastructure.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionServiceImplTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DocumentChunkRepository documentChunkRepository;
    @Mock
    private DocumentChunkStagingRepository stagingRepository;
    @Mock
    private StorageService storageService;
    @Mock
    private DocumentTextExtractor documentTextExtractor;
    @Mock
    private DocumentChunker documentChunker;
    @Mock
    private EmbeddingGateway embeddingGateway;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private RagEmbeddingProperties properties;
    private DocumentIngestionServiceImpl ingestionService;

    @BeforeEach
    void setUp() {
        properties = new RagEmbeddingProperties();
        ingestionService = new DocumentIngestionServiceImpl(
                documentRepository,
                documentChunkRepository,
                stagingRepository,
                storageService,
                documentTextExtractor,
                documentChunker,
                embeddingGateway,
                properties,
                jdbcTemplate,
                null
        );
    }

    @Test
    @DisplayName("Verify successful document ingestion lifecycle, staging, and atomic vector publication")
    void testIngestDocumentSuccess() throws IOException {
        DocumentEntity doc = DocumentEntity.builder()
                .id(1L)
                .projectId(10L)
                .storageKey("documents/spec.pdf")
                .originalFilename("spec.pdf")
                .contentType("application/pdf")
                .status(DocumentStatus.PROCESSING)
                .processingVersion(1)
                .build();

        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(stagingRepository.hasStagedChunks(1L, 1)).thenReturn(false);
        when(storageService.downloadFile("documents", "documents/spec.pdf"))
                .thenReturn(new ByteArrayInputStream("mock stream".getBytes()));
        when(documentTextExtractor.extractText(any(), eq("spec.pdf"), eq("application/pdf")))
                .thenReturn("Parsed specification document text");
        when(documentChunker.chunkText("Parsed specification document text"))
                .thenReturn(List.of("chunk 1", "chunk 2"));

        StagedChunk staged1 = new StagedChunk(101L, 1L, 1, 0, "chunk 1", null, Instant.now());
        StagedChunk staged2 = new StagedChunk(102L, 1L, 1, 1, "chunk 2", null, Instant.now());
        when(stagingRepository.findPendingChunks(1L, 1)).thenReturn(List.of(staged1, staged2));

        float[] vec1 = new float[768];
        float[] vec2 = new float[768];
        vec1[0] = 0.5f;
        vec2[0] = 0.8f;
        when(embeddingGateway.embedForIngestion(List.of("chunk 1", "chunk 2")))
                .thenReturn(List.of(vec1, vec2));

        when(stagingRepository.countPendingChunks(1L, 1)).thenReturn(0L);

        // Mock JDBC finalization lock and update
        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(1));
        when(stagingRepository.copyStagedToPublished(1L, 1, 10L)).thenReturn(2);
        when(jdbcTemplate.update(anyString(), eq(1L), eq(1))).thenReturn(1);

        ingestionService.ingestDocument(1L, 1);

        verify(stagingRepository).stageInitialChunks(1L, 1, List.of("chunk 1", "chunk 2"));
        verify(stagingRepository).updateEmbeddings(List.of(staged1, staged2), List.of(vec1, vec2));
        verify(documentChunkRepository).deleteByDocumentId(1L);
        verify(stagingRepository).copyStagedToPublished(1L, 1, 10L);
        verify(stagingRepository).deleteStagedChunks(1L, 1);
    }

    @Test
    @DisplayName("Verify resume ingestion skips Tika parsing/chunking and only embeds chunks where embedding IS NULL")
    void testResumeIngestionFromPendingChunksWithoutReparsing() {
        DocumentEntity doc = DocumentEntity.builder()
                .id(5L)
                .projectId(10L)
                .storageKey("documents/large.pdf")
                .originalFilename("large.pdf")
                .status(DocumentStatus.PROCESSING)
                .processingVersion(2)
                .build();

        when(documentRepository.findById(5L)).thenReturn(Optional.of(doc));
        // Staged chunks already exist from previous attempt
        when(stagingRepository.hasStagedChunks(5L, 2)).thenReturn(true);

        // Only chunk 2 is pending (chunk 1 was already embedded in previous attempt)
        StagedChunk pendingChunk2 = new StagedChunk(202L, 5L, 2, 1, "chunk 2 remaining", null, Instant.now());
        when(stagingRepository.findPendingChunks(5L, 2)).thenReturn(List.of(pendingChunk2));

        float[] vec2 = new float[768];
        vec2[0] = 0.42f;
        when(embeddingGateway.embedForIngestion(List.of("chunk 2 remaining"))).thenReturn(List.of(vec2));

        when(stagingRepository.countPendingChunks(5L, 2)).thenReturn(0L);
        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(2));
        when(stagingRepository.copyStagedToPublished(5L, 2, 10L)).thenReturn(2);
        when(jdbcTemplate.update(anyString(), eq(5L), eq(2))).thenReturn(1);

        // WHEN
        ingestionService.ingestDocument(5L, 2);

        // THEN
        // Neither storage download nor text extraction nor chunking was called!
        verifyNoInteractions(storageService);
        verifyNoInteractions(documentTextExtractor);
        verifyNoInteractions(documentChunker);

        // Only chunk 2 was embedded and updated
        verify(embeddingGateway).embedForIngestion(List.of("chunk 2 remaining"));
        verify(stagingRepository).updateEmbeddings(List.of(pendingChunk2), List.of(vec2));

        // Atomic publication succeeded
        verify(documentChunkRepository).deleteByDocumentId(5L);
        verify(stagingRepository).copyStagedToPublished(5L, 2, 10L);
        verify(stagingRepository).deleteStagedChunks(5L, 2);
    }

    @Test
    @DisplayName("Verify resume ingestion adopts older staged chunks when current version has no chunks yet")
    void testResumeIngestionAdoptsOlderStagedChunks() {
        DocumentEntity doc = DocumentEntity.builder()
                .id(6L)
                .projectId(10L)
                .storageKey("documents/resume.pdf")
                .originalFilename("resume.pdf")
                .status(DocumentStatus.PROCESSING)
                .processingVersion(3)
                .build();

        when(documentRepository.findById(6L)).thenReturn(Optional.of(doc));
        // Current version 3 has no chunks directly, but older version 2 has chunks!
        when(stagingRepository.hasStagedChunks(6L, 3)).thenReturn(false);
        when(stagingRepository.adoptOlderStagedChunks(6L, 3)).thenReturn(50);

        StagedChunk pendingChunk = new StagedChunk(301L, 6L, 3, 40, "chunk 41", null, Instant.now());
        when(stagingRepository.findPendingChunks(6L, 3)).thenReturn(List.of(pendingChunk));

        float[] vec = new float[768];
        when(embeddingGateway.embedForIngestion(List.of("chunk 41"))).thenReturn(List.of(vec));
        when(stagingRepository.countPendingChunks(6L, 3)).thenReturn(0L);
        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(3));
        when(stagingRepository.copyStagedToPublished(6L, 3, 10L)).thenReturn(50);
        when(jdbcTemplate.update(anyString(), eq(6L), eq(3))).thenReturn(1);

        // WHEN
        ingestionService.ingestDocument(6L, 3);

        // THEN
        verify(stagingRepository).adoptOlderStagedChunks(6L, 3);
        verifyNoInteractions(storageService);
        verifyNoInteractions(documentTextExtractor);
        verifyNoInteractions(documentChunker);
        verify(stagingRepository).updateEmbeddings(List.of(pendingChunk), List.of(vec));
        verify(stagingRepository).copyStagedToPublished(6L, 3, 10L);
        verify(stagingRepository).deleteStagedChunks(6L, 3);
    }

    @Test
    @DisplayName("Verify ingestion retryable failure triggers conditional retry wait SQL update")
    void testIngestDocumentRetryableFailureHandling() throws IOException {
        DocumentEntity doc = DocumentEntity.builder()
                .id(2L)
                .projectId(10L)
                .storageKey("documents/corrupt.docx")
                .originalFilename("corrupt.docx")
                .contentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .status(DocumentStatus.PROCESSING)
                .processingVersion(2)
                .retryCount(1)
                .build();

        when(documentRepository.findById(2L)).thenReturn(Optional.of(doc));
        when(stagingRepository.hasStagedChunks(2L, 2)).thenReturn(false);
        when(storageService.downloadFile("documents", "documents/corrupt.docx"))
                .thenThrow(new IOException("S3 connection timeout"));

        ingestionService.ingestDocument(2L, 2);

        // Verify conditional SQL update was executed for retry wait
        verify(jdbcTemplate).update(
                contains("RETRY_WAIT"),
                eq(properties.getMaxRetryAttempts()),
                eq(properties.getMaxRetryAttempts()),
                eq(properties.getMaxRetryAttempts()),
                anyLong(),
                contains("S3 connection timeout"),
                eq(2L),
                eq(2)
        );
    }

    @Test
    @DisplayName("Verify deleteDocument removes vector chunks, staging chunks, S3 file and document entity")
    void testDeleteDocumentSuccess() {
        DocumentEntity doc = DocumentEntity.builder()
                .id(3L)
                .projectId(100L)
                .storageKey("documents/delete-me.txt")
                .build();

        when(documentRepository.findById(3L)).thenReturn(Optional.of(doc));

        ingestionService.deleteDocument(3L);

        verify(documentChunkRepository).deleteByDocumentId(3L);
        verify(stagingRepository).deleteAllByDocumentId(3L);
        verify(storageService).deleteFile("documents", "documents/delete-me.txt");
        verify(documentRepository).delete(doc);
    }

    @Test
    @DisplayName("Verify ingestDocument throws IllegalArgumentException when document not found")
    void testIngestDocumentNotFound() {
        when(documentRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ingestionService.ingestDocument(999L, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Document not found: 999");
    }

    @Test
    @DisplayName("Verify isRetryable correctly classifies 429, timeouts, and quota exceptions")
    void testErrorClassification() {
        assertThat(DocumentIngestionServiceImpl.isRetryable(new QuotaExceededException("Quota exhausted"))).isTrue();
        assertThat(DocumentIngestionServiceImpl.isRetryable(new IOException("Read timed out"))).isTrue();
        assertThat(DocumentIngestionServiceImpl.isRetryable(new RuntimeException("HTTP 429 Too Many Requests"))).isTrue();
        assertThat(DocumentIngestionServiceImpl.isRetryable(new RuntimeException("RESOURCE_EXHAUSTED"))).isTrue();

        // Permanent non-retryable
        assertThat(DocumentIngestionServiceImpl.isRetryable(new IllegalStateException("Empty text"))).isFalse();
        assertThat(DocumentIngestionServiceImpl.isRetryable(new IllegalArgumentException("Invalid format"))).isFalse();
    }

    @Test
    @DisplayName("Verify calculateBackoff produces bounded exponential values")
    void testCalculateBackoff() {
        long backoff0 = DocumentIngestionServiceImpl.calculateBackoff(0, new IOException("timeout"));
        assertThat(backoff0).isBetween(5L, 10L);

        long backoff3 = DocumentIngestionServiceImpl.calculateBackoff(3, new IOException("timeout"));
        assertThat(backoff3).isBetween(40L, 50L);

        // Capped at 300 seconds
        long backoff10 = DocumentIngestionServiceImpl.calculateBackoff(10, new IOException("timeout"));
        assertThat(backoff10).isLessThanOrEqualTo(300L);
    }
}
