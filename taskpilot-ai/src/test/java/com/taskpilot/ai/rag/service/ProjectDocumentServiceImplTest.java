package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.domain.DocumentStatus;
import com.taskpilot.ai.rag.domain.ScoredChunk;
import com.taskpilot.ai.rag.dto.DocumentResponse;
import com.taskpilot.ai.rag.entity.DocumentEntity;
import com.taskpilot.ai.rag.repository.DocumentChunkRepository;
import com.taskpilot.ai.rag.repository.DocumentRepository;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.contracts.storage.StorageObjectLocator;
import com.taskpilot.contracts.storage.StorageProviderKind;
import com.taskpilot.contracts.storage.StorageTargetDomain;
import com.taskpilot.contracts.storage.StorageUploadOperationDto;
import com.taskpilot.contracts.storage.StorageUploadOperationPort;
import com.taskpilot.contracts.storage.StorageUploadOperationStatus;
import com.taskpilot.contracts.storage.StorageUploadReservationCommand;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectDocumentServiceImplTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DocumentChunkRepository documentChunkRepository;
    @Mock
    private DocumentIngestionService documentIngestionService;
    @Mock
    private ProjectKnowledgeService projectKnowledgeService;
    @Mock
    private StorageService storageService;
    @Mock
    private ProjectMemberPort projectMemberPort;
    @Mock
    private StorageUploadOperationPort uploadOperationPort;

    private ProjectDocumentServiceImpl projectDocumentService;

    @BeforeEach
    void setUp() {
        projectDocumentService = new ProjectDocumentServiceImpl(
                documentRepository,
                documentChunkRepository,
                documentIngestionService,
                projectKnowledgeService,
                storageService,
                projectMemberPort,
                uploadOperationPort,
                null
        );
    }

    @Test
    @DisplayName("Verify document upload succeeds with durable reservation and attach for active project manager")
    void testUploadDocumentSuccess() throws IOException {
        Long projectId = 100L;
        Long userId = 42L;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "architecture.pdf",
                "application/pdf",
                "Architecture spec content".getBytes()
        );
        UUID opId = UUID.randomUUID();
        String plannedKey = "projects/100/documents/u1-architecture.pdf";

        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        when(projectMemberPort.isProjectManager(projectId, userId)).thenReturn(true);
        when(projectMemberPort.isProjectArchived(projectId)).thenReturn(false);
        when(storageService.planObjectKey("projects/100/documents", "architecture.pdf")).thenReturn(plannedKey);
        when(uploadOperationPort.reserve(any(StorageUploadReservationCommand.class))).thenReturn(
                new StorageUploadOperationDto(opId, userId, projectId, StorageTargetDomain.RAG_DOCUMENT,
                        StorageProviderKind.S3, "documents", plannedKey, null,
                        StorageUploadOperationStatus.RESERVED, null, Instant.now().plusSeconds(600))
        );
        when(storageService.uploadFileAtKey(file, "documents", plannedKey)).thenReturn(plannedKey);

        DocumentEntity savedEntity = DocumentEntity.builder()
                .id(1L)
                .projectId(projectId)
                .storageKey(plannedKey)
                .originalFilename("architecture.pdf")
                .contentType("application/pdf")
                .fileSize((long) file.getBytes().length)
                .status(DocumentStatus.QUEUED)
                .createdBy(userId)
                .createdAt(Instant.now())
                .build();

        when(documentRepository.save(any(DocumentEntity.class))).thenReturn(savedEntity);
        when(documentChunkRepository.countByDocumentId(1L)).thenReturn(0L);

        DocumentResponse response = projectDocumentService.uploadDocument(projectId, file, userId);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.projectId()).isEqualTo(projectId);
        assertThat(response.originalFilename()).isEqualTo("architecture.pdf");
        assertThat(response.status()).isEqualTo(DocumentStatus.QUEUED);
        verify(uploadOperationPort).reserve(any());
        verify(uploadOperationPort).markProviderWritten(opId, plannedKey);
        verify(uploadOperationPort).markAttachedInCurrentTransaction(opId, 1L);
    }

    @Test
    @DisplayName("Verify metadata save failure triggers durable upload compensation for RAG document")
    void testUploadDocumentMetadataFailureCompensates() throws IOException {
        Long projectId = 100L;
        Long userId = 42L;
        MockMultipartFile file = new MockMultipartFile(
                "file", "spec.pdf", "application/pdf", "Spec content".getBytes()
        );
        UUID opId = UUID.randomUUID();
        String plannedKey = "projects/100/documents/u2-spec.pdf";

        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        when(projectMemberPort.isProjectManager(projectId, userId)).thenReturn(true);
        when(projectMemberPort.isProjectArchived(projectId)).thenReturn(false);
        when(storageService.planObjectKey("projects/100/documents", "spec.pdf")).thenReturn(plannedKey);
        when(uploadOperationPort.reserve(any())).thenReturn(
                new StorageUploadOperationDto(opId, userId, projectId, StorageTargetDomain.RAG_DOCUMENT,
                        StorageProviderKind.S3, "documents", plannedKey, null,
                        StorageUploadOperationStatus.RESERVED, null, Instant.now().plusSeconds(600))
        );
        when(storageService.uploadFileAtKey(file, "documents", plannedKey)).thenReturn(plannedKey);
        when(documentRepository.save(any(DocumentEntity.class))).thenThrow(new RuntimeException("DB write failed"));

        assertThatThrownBy(() -> projectDocumentService.uploadDocument(projectId, file, userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB write failed");

        verify(uploadOperationPort).compensateFailedUpload(
                eq(opId),
                argThat(loc -> loc != null && plannedKey.equals(loc.objectKey())),
                eq("RAG_METADATA_ATTACH_FAILED"),
                contains("DB write failed")
        );
    }

    @Test
    @DisplayName("Verify archived project rejects RAG upload, delete, and retry")
    void testArchivedProjectRejectsRagMutations() {
        Long projectId = 100L;
        Long userId = 42L;
        MockMultipartFile file = new MockMultipartFile("file", "spec.pdf", "application/pdf", "Spec".getBytes());

        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        when(projectMemberPort.isProjectManager(projectId, userId)).thenReturn(true);
        when(projectMemberPort.isProjectArchived(projectId)).thenReturn(true);

        assertThatThrownBy(() -> projectDocumentService.uploadDocument(projectId, file, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Project is archived");
        assertThatThrownBy(() -> projectDocumentService.deleteDocument(projectId, 1L, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Project is archived");
        assertThatThrownBy(() -> projectDocumentService.retryIngestion(projectId, 1L, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Project is archived");

        verifyNoInteractions(storageService);
        verifyNoInteractions(uploadOperationPort);
        verifyNoInteractions(documentIngestionService);
    }

    @Test
    @DisplayName("Verify listProjectOwnedObjects returns all non-blank RAG document locators")
    void testListProjectOwnedObjects() {
        DocumentEntity doc1 = DocumentEntity.builder().id(1L).projectId(100L).storageKey("projects/100/documents/a.pdf").build();
        DocumentEntity doc2 = DocumentEntity.builder().id(2L).projectId(100L).storageKey("projects/100/documents/b.md").build();
        when(documentRepository.findByProjectId(100L)).thenReturn(List.of(doc1, doc2));

        List<StorageObjectLocator> locators = projectDocumentService.listProjectOwnedObjects(100L);

        assertThat(locators).containsExactly(
                StorageObjectLocator.of("documents", "projects/100/documents/a.pdf"),
                StorageObjectLocator.of("documents", "projects/100/documents/b.md")
        );
    }

    @Test
    @DisplayName("Verify document upload throws 403 Forbidden when user is MEMBER but not MANAGER")
    void testUploadDocumentForbiddenForNonManagerMember() {
        Long projectId = 100L;
        Long userId = 42L;
        MockMultipartFile file = new MockMultipartFile(
                "file", "plan.md", "text/markdown", "# Plan".getBytes()
        );

        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        when(projectMemberPort.isProjectManager(projectId, userId)).thenReturn(false);

        assertThatThrownBy(() -> projectDocumentService.uploadDocument(projectId, file, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only Project Manager can perform this action");

        verifyNoInteractions(storageService);
        verifyNoInteractions(documentRepository);
    }

    @Test
    @DisplayName("Verify document upload is rejected with 403 AccessDeniedException for non-member")
    void testUploadDocumentAccessDenied() {
        Long projectId = 100L;
        Long userId = 999L;
        MockMultipartFile file = new MockMultipartFile(
                "file", "plan.md", "text/markdown", "# Plan".getBytes()
        );

        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(false);

        assertThatThrownBy(() -> projectDocumentService.uploadDocument(projectId, file, userId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("User is not authorized to access project knowledge: 100");

        verifyNoInteractions(storageService);
        verifyNoInteractions(documentRepository);
        verifyNoInteractions(documentIngestionService);
    }

    @Test
    @DisplayName("Verify document upload rejects empty file with 400 Bad Request")
    void testUploadDocumentEmptyFile() {
        Long projectId = 100L;
        Long userId = 42L;
        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        when(projectMemberPort.isProjectManager(projectId, userId)).thenReturn(true);

        assertThatThrownBy(() -> projectDocumentService.uploadDocument(projectId, emptyFile, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("File must not be empty");
    }

    @Test
    @DisplayName("Verify document upload rejects unsupported file extensions")
    void testUploadDocumentUnsupportedFormat() {
        Long projectId = 100L;
        Long userId = 42L;
        MockMultipartFile exeFile = new MockMultipartFile("file", "malicious.exe", "application/octet-stream", "bad".getBytes());

        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        when(projectMemberPort.isProjectManager(projectId, userId)).thenReturn(true);

        assertThatThrownBy(() -> projectDocumentService.uploadDocument(projectId, exeFile, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unsupported file extension");
    }

    @Test
    @DisplayName("Verify listing project documents returns document list with chunk counts")
    void testGetProjectDocumentsSuccess() {
        Long projectId = 100L;
        Long userId = 42L;

        DocumentEntity doc1 = DocumentEntity.builder()
                .id(1L).projectId(projectId).originalFilename("doc1.pdf").status(DocumentStatus.READY).build();
        DocumentEntity doc2 = DocumentEntity.builder()
                .id(2L).projectId(projectId).originalFilename("doc2.docx").status(DocumentStatus.PROCESSING).build();

        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        when(documentRepository.findByProjectId(projectId)).thenReturn(List.of(doc1, doc2));
        when(documentChunkRepository.countByDocumentId(1L)).thenReturn(5L);
        when(documentChunkRepository.countByDocumentId(2L)).thenReturn(0L);

        List<DocumentResponse> result = projectDocumentService.getProjectDocuments(projectId, userId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(1L);
        assertThat(result.get(0).chunkCount()).isEqualTo(5L);
        assertThat(result.get(1).id()).isEqualTo(2L);
        assertThat(result.get(1).chunkCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Verify get document detail returns document if present")
    void testGetDocumentDetailSuccess() {
        Long projectId = 100L;
        Long documentId = 1L;
        Long userId = 42L;

        DocumentEntity doc = DocumentEntity.builder()
                .id(documentId).projectId(projectId).originalFilename("doc.pdf").status(DocumentStatus.READY).build();

        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        when(documentRepository.findByIdAndProjectId(documentId, projectId)).thenReturn(Optional.of(doc));
        when(documentChunkRepository.countByDocumentId(documentId)).thenReturn(3L);

        DocumentResponse response = projectDocumentService.getDocument(projectId, documentId, userId);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(documentId);
        assertThat(response.chunkCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Verify get document detail throws 404 when document not found")
    void testGetDocumentDetailNotFound() {
        Long projectId = 100L;
        Long documentId = 999L;
        Long userId = 42L;

        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        when(documentRepository.findByIdAndProjectId(documentId, projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectDocumentService.getDocument(projectId, documentId, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Document not found");
    }

    @Test
    @DisplayName("Verify delete document delegates to DocumentIngestionService.deleteDocument")
    void testDeleteDocumentSuccess() {
        Long projectId = 100L;
        Long documentId = 1L;
        Long userId = 42L;

        DocumentEntity doc = DocumentEntity.builder()
                .id(documentId).projectId(projectId).originalFilename("doc.pdf").build();

        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        when(projectMemberPort.isProjectManager(projectId, userId)).thenReturn(true);
        when(documentRepository.findByIdAndProjectId(documentId, projectId)).thenReturn(Optional.of(doc));

        projectDocumentService.deleteDocument(projectId, documentId, userId);

        verify(documentIngestionService).deleteDocument(documentId);
    }

    @Test
    @DisplayName("Verify delete document throws 403 Forbidden when user is MEMBER but not MANAGER")
    void testDeleteDocumentForbiddenForNonManagerMember() {
        Long projectId = 100L;
        Long documentId = 1L;
        Long userId = 42L;

        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        when(projectMemberPort.isProjectManager(projectId, userId)).thenReturn(false);

        assertThatThrownBy(() -> projectDocumentService.deleteDocument(projectId, documentId, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only Project Manager can perform this action");

        verifyNoInteractions(documentIngestionService);
    }

    @Test
    @DisplayName("Verify retryIngestion marks document QUEUED and resets error state")
    void testRetryIngestionSuccess() {
        Long projectId = 100L;
        Long documentId = 1L;
        Long userId = 42L;

        DocumentEntity doc = DocumentEntity.builder()
                .id(documentId).projectId(projectId).originalFilename("doc.pdf")
                .status(DocumentStatus.FAILED).errorMessage("timeout").build();

        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        when(projectMemberPort.isProjectManager(projectId, userId)).thenReturn(true);
        when(documentRepository.findByIdAndProjectId(documentId, projectId)).thenReturn(Optional.of(doc));
        when(documentRepository.save(any(DocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DocumentResponse response = projectDocumentService.retryIngestion(projectId, documentId, userId);

        assertThat(response.status()).isEqualTo(DocumentStatus.QUEUED);
        assertThat(response.errorMessage()).isNull();
    }

    @Test
    @DisplayName("Verify retryIngestion throws 403 Forbidden when user is MEMBER but not MANAGER")
    void testRetryIngestionForbiddenForNonManagerMember() {
        Long projectId = 100L;
        Long documentId = 1L;
        Long userId = 42L;

        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        when(projectMemberPort.isProjectManager(projectId, userId)).thenReturn(false);

        assertThatThrownBy(() -> projectDocumentService.retryIngestion(projectId, documentId, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only Project Manager can perform this action");
    }

    @Test
    @DisplayName("Verify searchDocuments delegates to ProjectKnowledgeService")
    void testSearchDocumentsDelegation() {
        Long projectId = 100L;
        Long userId = 42L;

        ScoredChunk chunk = new ScoredChunk(1L, 2L, projectId, 0, "Test chunk content", 0.88);
        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        when(projectKnowledgeService.searchKnowledge(projectId, userId, "query", 5, 0.5))
                .thenReturn(List.of(chunk));

        List<ScoredChunk> results = projectDocumentService.searchDocuments(projectId, "query", 5, 0.5, userId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("Test chunk content");
        verify(projectKnowledgeService).searchKnowledge(projectId, userId, "query", 5, 0.5);
    }
}
