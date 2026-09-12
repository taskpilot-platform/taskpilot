package com.taskpilot.ai.rag.controller;

import com.taskpilot.ai.rag.domain.DocumentStatus;
import com.taskpilot.ai.rag.domain.ScoredChunk;
import com.taskpilot.ai.rag.dto.DocumentResponse;
import com.taskpilot.ai.rag.service.ProjectDocumentService;
import com.taskpilot.contracts.user.dto.UserIdentityDto;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.infrastructure.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectDocumentControllerTest {

    @Mock
    private ProjectDocumentService projectDocumentService;
    @Mock
    private UserIdentityPort userIdentityPort;
    @Mock
    private Authentication authentication;

    private ProjectDocumentController controller;

    @BeforeEach
    void setUp() {
        controller = new ProjectDocumentController(projectDocumentService, userIdentityPort);
    }

    private void mockUser(Long userId, String email) {
        when(authentication.getName()).thenReturn(email);
        when(userIdentityPort.findByEmail(email)).thenReturn(Optional.of(
                new UserIdentityDto(userId, email)
        ));
    }

    @Test
    @DisplayName("Verify uploadDocument endpoint routes to service and returns 201 Created ApiResponse")
    void testUploadDocument() {
        Long projectId = 10L;
        Long userId = 5L;
        mockUser(userId, "user@example.com");

        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "data".getBytes());
        DocumentResponse responseDto = DocumentResponse.builder()
                .id(100L)
                .projectId(projectId)
                .originalFilename("doc.pdf")
                .status(DocumentStatus.UPLOADING)
                .chunkCount(0L)
                .createdAt(Instant.now())
                .build();

        when(projectDocumentService.uploadDocument(eq(projectId), eq(file), eq(userId)))
                .thenReturn(responseDto);

        ApiResponse<DocumentResponse> response = controller.uploadDocument(projectId, file, authentication);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getData().id()).isEqualTo(100L);
        assertThat(response.getData().originalFilename()).isEqualTo("doc.pdf");
        verify(projectDocumentService).uploadDocument(projectId, file, userId);
    }

    @Test
    @DisplayName("Verify getDocuments endpoint returns list of project documents")
    void testGetDocuments() {
        Long projectId = 10L;
        Long userId = 5L;
        mockUser(userId, "user@example.com");

        DocumentResponse doc = DocumentResponse.builder()
                .id(100L)
                .projectId(projectId)
                .originalFilename("doc.pdf")
                .status(DocumentStatus.READY)
                .chunkCount(4L)
                .build();

        when(projectDocumentService.getProjectDocuments(projectId, userId))
                .thenReturn(List.of(doc));

        ApiResponse<List<DocumentResponse>> response = controller.getDocuments(projectId, authentication);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getData()).hasSize(1);
        assertThat(response.getData().getFirst().chunkCount()).isEqualTo(4L);
    }

    @Test
    @DisplayName("Verify getDocument endpoint returns single document detail")
    void testGetDocument() {
        Long projectId = 10L;
        Long documentId = 100L;
        Long userId = 5L;
        mockUser(userId, "user@example.com");

        DocumentResponse doc = DocumentResponse.builder()
                .id(documentId)
                .projectId(projectId)
                .originalFilename("spec.docx")
                .status(DocumentStatus.READY)
                .chunkCount(10L)
                .build();

        when(projectDocumentService.getDocument(projectId, documentId, userId)).thenReturn(doc);

        ApiResponse<DocumentResponse> response = controller.getDocument(projectId, documentId, authentication);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getData().id()).isEqualTo(documentId);
    }

    @Test
    @DisplayName("Verify deleteDocument endpoint calls service")
    void testDeleteDocument() {
        Long projectId = 10L;
        Long documentId = 100L;
        Long userId = 5L;
        mockUser(userId, "user@example.com");

        ApiResponse<Void> response = controller.deleteDocument(projectId, documentId, authentication);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(projectDocumentService).deleteDocument(projectId, documentId, userId);
    }

    @Test
    @DisplayName("Verify retryIngestion endpoint triggers re-indexing")
    void testRetryIngestion() {
        Long projectId = 10L;
        Long documentId = 100L;
        Long userId = 5L;
        mockUser(userId, "user@example.com");

        DocumentResponse doc = DocumentResponse.builder()
                .id(documentId)
                .projectId(projectId)
                .status(DocumentStatus.PROCESSING)
                .build();

        when(projectDocumentService.retryIngestion(projectId, documentId, userId)).thenReturn(doc);

        ApiResponse<DocumentResponse> response = controller.retryIngestion(projectId, documentId, authentication);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getData().status()).isEqualTo(DocumentStatus.PROCESSING);
    }

    @Test
    @DisplayName("Verify searchKnowledge endpoint delegates to service")
    void testSearchKnowledge() {
        Long projectId = 10L;
        Long userId = 5L;
        mockUser(userId, "user@example.com");

        ScoredChunk chunk = new ScoredChunk(1L, 100L, projectId, 0, "Sprint goal: Launch MVP", 0.92);
        when(projectDocumentService.searchDocuments(projectId, "MVP", 5, 0.4, userId))
                .thenReturn(List.of(chunk));

        ApiResponse<List<ScoredChunk>> response = controller.searchKnowledge(projectId, "MVP", 5, 0.4, authentication);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getData()).hasSize(1);
        assertThat(response.getData().getFirst().similarity()).isEqualTo(0.92);
    }


    @Test
    @DisplayName("Verify endpoint throws 401 BusinessException when user is not found in identity port")
    void testResolveUserNotFound() {
        when(authentication.getName()).thenReturn("ghost@example.com");
        when(userIdentityPort.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getDocuments(10L, authentication))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("User not found");
    }
}
