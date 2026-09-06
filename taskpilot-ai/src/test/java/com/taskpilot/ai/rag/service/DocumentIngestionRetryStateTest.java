package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.config.RagEmbeddingProperties;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionRetryStateTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DocumentChunkRepository documentChunkRepository;
    @Mock
    private StorageService storageService;
    @Mock
    private DocumentTextExtractor textExtractor;
    @Mock
    private DocumentChunker chunker;
    @Mock
    private EmbeddingGateway embeddingGateway;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private RagEmbeddingProperties properties;
    private DocumentIngestionServiceImpl ingestionService;

    @BeforeEach
    void setUp() {
        properties = new RagEmbeddingProperties(12, 2, 100, 10, 5, 3000L);
        ingestionService = new DocumentIngestionServiceImpl(
                documentRepository,
                documentChunkRepository,
                storageService,
                textExtractor,
                chunker,
                embeddingGateway,
                properties,
                jdbcTemplate,
                null
        );
    }

    @Test
    @DisplayName("TEST 5 — Retry State Transition: retryable failure triggers conditional RETRY_WAIT SQL update")
    void testRetryStateTransition() throws Exception {
        DocumentEntity doc = DocumentEntity.builder()
                .id(10L)
                .projectId(100L)
                .storageKey("key.pdf")
                .originalFilename("key.pdf")
                .status(DocumentStatus.PROCESSING)
                .processingVersion(1)
                .retryCount(0)
                .build();

        when(documentRepository.findById(10L)).thenReturn(Optional.of(doc));
        when(storageService.downloadFile(anyString(), anyString())).thenReturn(new ByteArrayInputStream("data".getBytes()));
        when(textExtractor.extractText(any(), any(), any())).thenReturn("valid text");
        when(chunker.chunkText(anyString())).thenReturn(List.of("chunk"));
        when(embeddingGateway.embedForIngestion(anyList())).thenThrow(new RuntimeException("Connection reset by peer", new IOException("Connection reset by peer")));

        ingestionService.ingestDocument(10L, 1);

        // Verify the atomic conditional update was triggered
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(
                sqlCaptor.capture(),
                eq(5), // maxRetries
                eq(5),
                eq(5),
                anyLong(), // backoffSeconds
                contains("Connection reset by peer"),
                eq(10L),
                eq(1)
        );

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("status = CASE");
        assertThat(sql).contains("'RETRY_WAIT'");
        assertThat(sql).contains("'FAILED'");
        assertThat(sql).contains("retry_count = CASE");
        assertThat(sql).contains("lease_until = NULL");
        assertThat(sql).contains("processing_version = ?");
        assertThat(sql).contains("status = 'PROCESSING'");
    }

    @Test
    @DisplayName("TEST 6 — Retry Limit: Failure when retry_count >= 5 leads to FAILED status in conditional update")
    void testRetryLimitFailsDocument() throws Exception {
        DocumentEntity doc = DocumentEntity.builder()
                .id(20L)
                .projectId(100L)
                .storageKey("key.pdf")
                .originalFilename("key.pdf")
                .status(DocumentStatus.PROCESSING)
                .processingVersion(3)
                .retryCount(5) // Exhausted retries
                .build();

        when(documentRepository.findById(20L)).thenReturn(Optional.of(doc));
        when(storageService.downloadFile(anyString(), anyString())).thenReturn(new ByteArrayInputStream("data".getBytes()));
        when(textExtractor.extractText(any(), any(), any())).thenReturn("valid text");
        when(chunker.chunkText(anyString())).thenReturn(List.of("chunk"));
        when(embeddingGateway.embedForIngestion(anyList())).thenThrow(new QuotaExceededException("Quota exceeded"));

        ingestionService.ingestDocument(20L, 3);

        // Verify update passes maxRetries = 5, docId = 20, version = 3
        verify(jdbcTemplate).update(
                contains("status = CASE"),
                eq(5),
                eq(5),
                eq(5),
                anyLong(),
                contains("Quota exceeded"),
                eq(20L),
                eq(3)
        );
    }

    @Test
    @DisplayName("TEST 7 — Successful State Hygiene: READY update resets retry_count, clears next_attempt_at and lease_until")
    void testSuccessfulStateHygiene() throws Exception {
        DocumentEntity doc = DocumentEntity.builder()
                .id(30L)
                .projectId(100L)
                .storageKey("key.pdf")
                .originalFilename("key.pdf")
                .status(DocumentStatus.PROCESSING)
                .processingVersion(2)
                .retryCount(2)
                .leaseUntil(Instant.now().plusSeconds(600))
                .build();

        when(documentRepository.findById(30L)).thenReturn(Optional.of(doc));
        when(storageService.downloadFile(anyString(), anyString())).thenReturn(new ByteArrayInputStream("data".getBytes()));
        when(textExtractor.extractText(any(), any(), any())).thenReturn("valid text");
        when(chunker.chunkText(anyString())).thenReturn(List.of("chunk"));
        when(embeddingGateway.embedForIngestion(anyList())).thenReturn(List.of(new float[768]));

        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(2));
        when(jdbcTemplate.update(anyString(), eq(30L), eq(2))).thenReturn(1);

        ingestionService.ingestDocument(30L, 2);

        ArgumentCaptor<String> readySqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(readySqlCaptor.capture(), eq(30L), eq(2));

        String readySql = readySqlCaptor.getValue();
        assertThat(readySql).contains("status = 'READY'");
        assertThat(readySql).contains("retry_count = 0");
        assertThat(readySql).contains("next_attempt_at = NULL");
        assertThat(readySql).contains("lease_until = NULL");
        assertThat(readySql).contains("error_message = NULL");
    }

    @Test
    @DisplayName("TEST 12 — 429 Becomes RETRY_WAIT: Provider 429 triggers RETRY_WAIT and releases worker without sleep")
    void testProvider429TriggersRetryWait() throws Exception {
        DocumentEntity doc = DocumentEntity.builder()
                .id(40L)
                .projectId(100L)
                .storageKey("key.pdf")
                .originalFilename("key.pdf")
                .status(DocumentStatus.PROCESSING)
                .processingVersion(1)
                .retryCount(0)
                .build();

        when(documentRepository.findById(40L)).thenReturn(Optional.of(doc));
        when(storageService.downloadFile(anyString(), anyString())).thenReturn(new ByteArrayInputStream("data".getBytes()));
        when(textExtractor.extractText(any(), any(), any())).thenReturn("valid text");
        when(chunker.chunkText(anyString())).thenReturn(List.of("chunk"));
        when(embeddingGateway.embedForIngestion(anyList()))
                .thenThrow(new RuntimeException("GoogleGenerativeAIException: 429 RESOURCE_EXHAUSTED - quota exceeded"));

        long startTime = System.currentTimeMillis();
        ingestionService.ingestDocument(40L, 1);
        long duration = System.currentTimeMillis() - startTime;

        // Verify worker was NOT blocked sleeping
        assertThat(duration).isLessThan(2000L);

        // Verify conditional SQL update for RETRY_WAIT was executed
        verify(jdbcTemplate).update(
                contains("RETRY_WAIT"),
                eq(5), eq(5), eq(5),
                anyLong(),
                contains("429 RESOURCE_EXHAUSTED"),
                eq(40L),
                eq(1)
        );
    }

    @Test
    @DisplayName("TEST 13 — Non-retryable Error: Corrupt/empty document immediately transitions to FAILED")
    void testNonRetryableErrorMarksFailed() throws Exception {
        DocumentEntity doc = DocumentEntity.builder()
                .id(50L)
                .projectId(100L)
                .storageKey("empty.pdf")
                .originalFilename("empty.pdf")
                .status(DocumentStatus.PROCESSING)
                .processingVersion(1)
                .build();

        when(documentRepository.findById(50L)).thenReturn(Optional.of(doc));
        when(storageService.downloadFile(anyString(), anyString())).thenReturn(new ByteArrayInputStream("".getBytes()));
        when(textExtractor.extractText(any(), any(), any())).thenReturn(""); // empty text

        ingestionService.ingestDocument(50L, 1);

        // Verify permanent failure update (status = 'FAILED' directly, not RETRY_WAIT)
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(
                sqlCaptor.capture(),
                contains("Extracted text from document is empty"),
                eq(50L),
                eq(1)
        );

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("status = 'FAILED'");
        assertThat(sql).doesNotContain("RETRY_WAIT");
        assertThat(sql).contains("lease_until = NULL");
        assertThat(sql).contains("next_attempt_at = NULL");
    }
}
