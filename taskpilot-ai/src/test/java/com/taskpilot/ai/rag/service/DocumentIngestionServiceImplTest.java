package com.taskpilot.ai.rag.service;

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
    private EmbeddingService embeddingService;

    private DocumentIngestionServiceImpl ingestionService;

    @BeforeEach
    void setUp() {
        ingestionService = new DocumentIngestionServiceImpl(
                documentRepository,
                documentChunkRepository,
                storageService,
                documentTextExtractor,
                documentChunker,
                embeddingService
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
                .status(DocumentStatus.UPLOADING)
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
        when(embeddingService.embedBatch(List.of("chunk 1", "chunk 2")))
                .thenReturn(List.of(vec1, vec2));

        ingestionService.ingestDocument(1L);

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(doc.getErrorMessage()).isNull();

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
    @DisplayName("Verify ingestion failure transitions document to FAILED status and cleans up partial chunks")
    void testIngestDocumentFailureHandling() throws IOException {
        DocumentEntity doc = DocumentEntity.builder()
                .id(2L)
                .projectId(10L)
                .storageKey("documents/corrupt.docx")
                .originalFilename("corrupt.docx")
                .contentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .status(DocumentStatus.UPLOADING)
                .build();

        when(documentRepository.findById(2L)).thenReturn(Optional.of(doc));
        when(storageService.downloadFile("documents", "documents/corrupt.docx"))
                .thenThrow(new IOException("S3 connection timeout"));

        assertThatThrownBy(() -> ingestionService.ingestDocument(2L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("S3 connection timeout");

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(doc.getErrorMessage()).contains("S3 connection timeout");
        verify(documentChunkRepository).deleteByDocumentId(2L);
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

        assertThatThrownBy(() -> ingestionService.ingestDocument(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Document not found: 999");
    }

    @Test
    @DisplayName("Verify recoverStuckDocuments transitions stale PROCESSING documents to FAILED with cleanup")
    void testRecoverStuckDocuments() {
        DocumentEntity stuck1 = DocumentEntity.builder()
                .id(10L)
                .projectId(1L)
                .originalFilename("spec.pdf")
                .status(DocumentStatus.PROCESSING)
                .build();

        DocumentEntity stuck2 = DocumentEntity.builder()
                .id(11L)
                .projectId(1L)
                .originalFilename("notes.docx")
                .status(DocumentStatus.PROCESSING)
                .build();

        when(documentRepository.findByStatusAndUpdatedAtBefore(eq(DocumentStatus.PROCESSING), any(java.time.Instant.class)))
                .thenReturn(List.of(stuck1, stuck2));

        int recoveredCount = ingestionService.recoverStuckDocuments(java.time.Duration.ofMinutes(15));

        assertThat(recoveredCount).isEqualTo(2);

        assertThat(stuck1.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(stuck1.getErrorMessage()).contains("Processing timed out or was interrupted by system restart. Please retry.");

        assertThat(stuck2.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(stuck2.getErrorMessage()).contains("Processing timed out or was interrupted by system restart. Please retry.");

        verify(documentChunkRepository).deleteByDocumentId(10L);
        verify(documentChunkRepository).deleteByDocumentId(11L);
        verify(documentRepository).save(stuck1);
        verify(documentRepository).save(stuck2);
    }

    @Test
    @DisplayName("Verify recoverStuckDocuments returns 0 when no stale documents found")
    void testRecoverStuckDocumentsNoneFound() {
        when(documentRepository.findByStatusAndUpdatedAtBefore(eq(DocumentStatus.PROCESSING), any(java.time.Instant.class)))
                .thenReturn(List.of());

        int recoveredCount = ingestionService.recoverStuckDocuments(java.time.Duration.ofMinutes(15));

        assertThat(recoveredCount).isEqualTo(0);
        verifyNoInteractions(documentChunkRepository);
    }
}

