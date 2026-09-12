package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.config.RagEmbeddingProperties;
import com.taskpilot.ai.rag.domain.ClaimedDocumentJob;
import com.taskpilot.ai.rag.domain.DocumentChunk;
import com.taskpilot.ai.rag.domain.DocumentStatus;
import com.taskpilot.ai.rag.dto.DocumentResponse;
import com.taskpilot.ai.rag.entity.DocumentEntity;
import com.taskpilot.ai.rag.repository.DocumentChunkRepository;
import com.taskpilot.ai.rag.repository.DocumentChunkStagingRepository;
import com.taskpilot.ai.rag.repository.DocumentRepository;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
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
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentJobPollerAndEndToEndTest {

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
    private DocumentJobClaimer claimer;
    @Mock
    private DocumentChunkRepository chunkRepository;
    @Mock
    private ProjectMemberPort projectMemberPort;
    @Mock
    private ProjectKnowledgeService knowledgeService;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private RagEmbeddingProperties properties;
    private DocumentIngestionServiceImpl ingestionService;
    private DocumentJobPoller poller;
    private ProjectDocumentServiceImpl projectDocumentService;

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

        poller = new DocumentJobPoller(claimer, ingestionService, properties);

        projectDocumentService = new ProjectDocumentServiceImpl(
                documentRepository,
                documentChunkRepository,
                ingestionService,
                projectKnowledgeServiceMock(),
                storageService,
                projectMemberPort
        );
    }

    private ProjectKnowledgeService projectKnowledgeServiceMock() {
        return knowledgeService;
    }

    @Test
    @DisplayName("TEST 14 — End-to-End Ingestion: upload document -> QUEUED -> poller claims -> PROCESSING -> Tika & Chunker -> Embedding -> READY")
    void testEndToEndIngestionLifecycle() throws Exception {
        Long projectId = 100L;
        Long userId = 5L;

        // 1. Upload flow: produces QUEUED document
        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        when(projectMemberPort.isProjectManager(projectId, userId)).thenReturn(true);
        when(storageService.uploadFile(any(), eq("projects/100/documents"), eq("documents")))
                .thenReturn("projects/100/documents/requirements.pdf");

        DocumentEntity queuedDoc = DocumentEntity.builder()
                .id(77L)
                .projectId(projectId)
                .storageKey("projects/100/documents/requirements.pdf")
                .originalFilename("requirements.pdf")
                .contentType("application/pdf")
                .status(DocumentStatus.QUEUED)
                .processingVersion(0)
                .build();

        when(documentRepository.save(any(DocumentEntity.class))).thenReturn(queuedDoc);

        MockMultipartFile file = new MockMultipartFile(
                "file", "requirements.pdf", "application/pdf", "Requirements text content".getBytes()
        );
        DocumentResponse uploadResponse = projectDocumentService.uploadDocument(projectId, file, userId);

        assertThat(uploadResponse.status()).isEqualTo(DocumentStatus.QUEUED);
        assertThat(uploadResponse.id()).isEqualTo(77L);

        // 2. Poller flow: claims job and drives ingestion
        when(claimer.claimNextJob(properties.getLeaseDurationMinutes()))
                .thenReturn(Optional.of(new ClaimedDocumentJob(77L, 1)));

        // Document state after claim: PROCESSING v1
        DocumentEntity processingDoc = DocumentEntity.builder()
                .id(77L)
                .projectId(projectId)
                .storageKey("projects/100/documents/requirements.pdf")
                .originalFilename("requirements.pdf")
                .contentType("application/pdf")
                .status(DocumentStatus.PROCESSING)
                .processingVersion(1)
                .build();

        when(documentRepository.findById(77L)).thenReturn(Optional.of(processingDoc));
        when(stagingRepository.hasStagedChunks(77L, 1)).thenReturn(false);
        when(storageService.downloadFile("documents", "projects/100/documents/requirements.pdf"))
                .thenReturn(new ByteArrayInputStream("Requirements raw text".getBytes()));
        when(textExtractor.extractText(any(), eq("requirements.pdf"), eq("application/pdf")))
                .thenReturn("Functional Requirements: RAG Pipeline with pgvector");
        when(chunker.chunkText(anyString()))
                .thenReturn(List.of("Chunk 1: RAG Architecture", "Chunk 2: pgvector HNSW"));

        com.taskpilot.ai.rag.domain.StagedChunk s1 = new com.taskpilot.ai.rag.domain.StagedChunk(101L, 77L, 1, 0, "Chunk 1: RAG Architecture", null, java.time.Instant.now());
        com.taskpilot.ai.rag.domain.StagedChunk s2 = new com.taskpilot.ai.rag.domain.StagedChunk(102L, 77L, 1, 1, "Chunk 2: pgvector HNSW", null, java.time.Instant.now());
        when(stagingRepository.findPendingChunks(77L, 1)).thenReturn(List.of(s1, s2));

        float[] v1 = new float[768];
        float[] v2 = new float[768];
        v1[0] = 0.8f;
        v2[0] = 0.9f;
        when(embeddingGateway.embedForIngestion(List.of("Chunk 1: RAG Architecture", "Chunk 2: pgvector HNSW")))
                .thenReturn(List.of(v1, v2));

        when(stagingRepository.countPendingChunks(77L, 1)).thenReturn(0L);

        // Mock finalization query & update
        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(1));
        when(stagingRepository.copyStagedToPublished(77L, 1, projectId)).thenReturn(2);
        when(jdbcTemplate.update(anyString(), eq(77L), eq(1))).thenReturn(1);

        // WHEN: Poller executes scheduled tick
        poller.pollAndProcess();

        // THEN: Verify chunks staged, published atomically, and cleaned up
        verify(stagingRepository).stageInitialChunks(77L, 1, List.of("Chunk 1: RAG Architecture", "Chunk 2: pgvector HNSW"));
        verify(stagingRepository).copyStagedToPublished(77L, 1, projectId);
        verify(stagingRepository).deleteStagedChunks(77L, 1);

        // Verify READY update executed
        verify(jdbcTemplate).update(
                contains("status = 'READY'"),
                eq(77L),
                eq(1)
        );
    }
}
