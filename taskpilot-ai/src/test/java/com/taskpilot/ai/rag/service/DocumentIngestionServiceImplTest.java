package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.config.RagEmbeddingProperties;
import com.taskpilot.ai.rag.domain.DocumentChunk;
import com.taskpilot.ai.rag.domain.DocumentStatus;
import com.taskpilot.ai.rag.entity.DocumentEntity;
import com.taskpilot.ai.rag.repository.DocumentChunkRepository;
import com.taskpilot.ai.rag.repository.DocumentRepository;
import com.taskpilot.infrastructure.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
    @DisplayName("Verify successful document ingestion lifecycle and vector persistence")
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
        when(storageService.downloadFile("documents", "documents/spec.pdf"))
                .thenReturn(new ByteArrayInputStream("mock stream".getBytes()));
        when(documentTextExtractor.extractText(any(), eq("spec.pdf"), eq("application/pdf")))
                .thenReturn("Parsed specification document text");
        when(documentChunker.chunkText("Parsed specification document text"))
                .thenReturn(List.of("chunk 1", "chunk 2"));

        float[] vec1 = new float[768];
        float[] vec2 = new float[768];
        vec1[0] = 0.5f;
        vec2[0] = 0.8f;
        when(embeddingGateway.embedForIngestion(List.of("chunk 1", "chunk 2")))
                .thenReturn(List.of(vec1, vec2));

        // Mock JDBC finalization lock and update
        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(1));
        when(jdbcTemplate.update(anyString(), eq(1L), eq(1))).thenReturn(1);

        ingestionService.ingestDocument(1L, 1);

        verify(documentChunkRepository).deleteByDocumentId(1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DocumentChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(documentChunkRepository).saveAll(chunksCaptor.capture());

        List<DocumentChunk> savedChunks = chunksCaptor.getValue();
        assertThat(savedChunks).hasSize(2);
        assertThat(savedChunks.get(0).projectId()).isEqualTo(10L);
        assertThat(savedChunks.get(0).content()).isEqualTo("chunk 1");
        assertThat(savedChunks.get(0).embedding()).isEqualTo(vec1);
        assertThat(savedChunks.get(1).chunkIndex()).isEqualTo(1);
        assertThat(savedChunks.get(1).content()).isEqualTo("chunk 2");
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
    @DisplayName("Verify deleteDocument removes vector chunks, S3 file and document entity")
    void testDeleteDocumentSuccess() {
        DocumentEntity doc = DocumentEntity.builder()
                .id(3L)
                .projectId(100L)
                .storageKey("documents/delete-me.txt")
                .build();

        when(documentRepository.findById(3L)).thenReturn(Optional.of(doc));

        ingestionService.deleteDocument(3L);

        verify(documentChunkRepository).deleteByDocumentId(3L);
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
