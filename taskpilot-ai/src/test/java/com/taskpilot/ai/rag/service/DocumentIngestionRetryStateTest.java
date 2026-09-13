package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.config.RagEmbeddingProperties;
import com.taskpilot.ai.rag.domain.DocumentStatus;
import com.taskpilot.ai.rag.entity.DocumentEntity;
import com.taskpilot.ai.rag.repository.DocumentChunkRepository;
import com.taskpilot.ai.rag.repository.DocumentChunkStagingRepository;
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
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class DocumentIngestionRetryStateTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DocumentChunkRepository documentChunkRepository;
    @Mock
    private DocumentChunkStagingRepository stagingRepository;
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
                stagingRepository,
                storageService,
                textExtractor,
                chunker,
                embeddingGateway,
                properties,
                jdbcTemplate,
                null
        );

        lenient().when(stagingRepository.findPendingChunks(anyLong(), anyInt()))
                .thenAnswer(inv -> List.of(new com.taskpilot.ai.rag.domain.StagedChunk(
                        1L, inv.getArgument(0), 0, "chunk", null, java.time.Instant.now()
                )));
        lenient().when(jdbcTemplate.update(contains("lease_until = NOW() + INTERVAL '3 minutes'"), anyLong(), anyInt())).thenReturn(1);
        lenient().when(jdbcTemplate.update(contains("retry_count = 0"), anyLong(), anyInt())).thenReturn(1);
        lenient().when(stagingRepository.updateEmbeddingsFenced(anyLong(), anyInt(), anyList(), anyList())).thenReturn(1);
        lenient().when(jdbcTemplate.query(contains("SELECT retry_count FROM documents"), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(0));
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

        // Verify the atomic conditional update was triggered with incremented retry_count = 1
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(
                sqlCaptor.capture(),
                eq(1), // newRetryCount
                anyLong(), // delaySeconds
                contains("Connection reset by peer"),
                eq(10L),
                eq(1)
        );

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("status = 'RETRY_WAIT'");
        assertThat(sql).contains("retry_count = ?");
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
        when(jdbcTemplate.query(contains("SELECT retry_count FROM documents"), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(5));

        ingestionService.ingestDocument(20L, 3);

        // Verify update marks FAILED when retry limit reached
        verify(jdbcTemplate).update(
                contains("status = 'FAILED'"),
                contains("Exceeded consecutive retry limit"),
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

        com.taskpilot.ai.rag.domain.StagedChunk staged = new com.taskpilot.ai.rag.domain.StagedChunk(
                1L, 30L, 0, "chunk", null, java.time.Instant.now()
        );
        when(stagingRepository.hasStagedChunks(30L)).thenReturn(false);
        when(stagingRepository.findPendingChunks(eq(30L), anyInt())).thenReturn(List.of(staged), java.util.Collections.emptyList());
        when(stagingRepository.updateEmbeddingsFenced(eq(30L), eq(2), anyList(), anyList())).thenReturn(1);
        when(stagingRepository.copyStagedToPublished(30L, 100L)).thenReturn(1);

        when(jdbcTemplate.update(contains("lease_until = NOW() + INTERVAL '3 minutes'"), eq(30L), eq(2))).thenReturn(1);
        when(jdbcTemplate.query(contains("FOR UPDATE"), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(30L));
        when(jdbcTemplate.queryForObject(contains("COUNT(*) FROM document_chunk_staging"), eq(Long.class), eq(30L)))
                .thenReturn(0L);
        when(jdbcTemplate.update(contains("status = 'READY'"), eq(30L), eq(2))).thenReturn(1);

        ingestionService.ingestDocument(30L, 2);

        ArgumentCaptor<String> readySqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).update(readySqlCaptor.capture(), eq(30L), eq(2));

        String readySql = readySqlCaptor.getAllValues().stream()
                .filter(s -> s.contains("status = 'READY'"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("status = 'READY' update not found"));
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

        // Verify conditional SQL update for RETRY_WAIT was executed with retry_count = 1
        verify(jdbcTemplate).update(
                contains("RETRY_WAIT"),
                eq(1),
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
