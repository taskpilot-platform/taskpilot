package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.domain.ScoredChunk;
import com.taskpilot.ai.rag.repository.DocumentChunkRepository;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectKnowledgeServiceImplTest {

    @Mock
    private ProjectMemberPort projectMemberPort;
    @Mock
    private DocumentChunkRepository documentChunkRepository;
    @Mock
    private EmbeddingService embeddingService;

    private ProjectKnowledgeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProjectKnowledgeServiceImpl(
                projectMemberPort,
                documentChunkRepository,
                embeddingService
        );
    }

    @Test
    @DisplayName("Verify authorized user retrieves scored chunks successfully")
    void testSearchKnowledgeAuthorized() {
        Long projectId = 10L;
        Long userId = 5L;
        String query = "OAuth2 architecture";

        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        float[] mockVector = new float[768];
        when(embeddingService.embedText(query)).thenReturn(mockVector);

        ScoredChunk chunk = new ScoredChunk(1L, 100L, projectId, 0, "OAuth2 flow details", 0.88);
        when(documentChunkRepository.findNearestChunks(projectId, mockVector, 5, 0.50))
                .thenReturn(List.of(chunk));

        List<ScoredChunk> results = service.searchKnowledge(projectId, userId, query, 5, 0.50);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("OAuth2 flow details");
        assertThat(results.get(0).similarity()).isEqualTo(0.88);

        verify(projectMemberPort).isProjectMember(projectId, userId);
        verify(embeddingService).embedText(query);
        verify(documentChunkRepository).findNearestChunks(projectId, mockVector, 5, 0.50);
    }

    @Test
    @DisplayName("Verify unauthorized user is rejected with AccessDeniedException before embedding or vector search")
    void testSearchKnowledgeUnauthorizedThrows403() {
        Long projectId = 10L;
        Long attackerId = 999L;
        String query = "Secret budget numbers";

        when(projectMemberPort.isProjectMember(projectId, attackerId)).thenReturn(false);

        assertThatThrownBy(() -> service.searchKnowledge(projectId, attackerId, query, 5, 0.50))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not authorized to access knowledge for project 10");

        // CRITICAL: Ensure neither external embedding nor DB vector search was invoked!
        verify(embeddingService, never()).embedText(anyString());
        verify(documentChunkRepository, never()).findNearestChunks(any(), any(), anyInt(), anyDouble());
    }

    @Test
    @DisplayName("Verify getKnowledgeContext produces clean formatted prompt context")
    void testGetKnowledgeContextFormatted() {
        Long projectId = 10L;
        Long userId = 5L;

        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        when(embeddingService.embedText("requirements")).thenReturn(new float[768]);

        ScoredChunk chunk = new ScoredChunk(1L, 100L, projectId, 0, "Requirement 1: RAG PGVector", 0.91);
        when(documentChunkRepository.findNearestChunks(eq(projectId), any(), eq(3), anyDouble()))
                .thenReturn(List.of(chunk));

        String context = service.getKnowledgeContext(projectId, userId, "requirements", 3);

        assertThat(context).contains("Relevant project documentation:");
        assertThat(context).contains("Requirement 1: RAG PGVector");
        assertThat(context).contains("0.91");
    }

    @Test
    @DisplayName("Verify getKnowledgeContext returns fallback message when no documents match")
    void testGetKnowledgeContextEmpty() {
        Long projectId = 10L;
        Long userId = 5L;

        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        when(embeddingService.embedText("unmatched query")).thenReturn(new float[768]);
        when(documentChunkRepository.findNearestChunks(eq(projectId), any(), eq(5), anyDouble()))
                .thenReturn(List.of());

        String context = service.getKnowledgeContext(projectId, userId, "unmatched query", 5);

        assertThat(context).isEqualTo("No relevant project documents found for query.");
    }
}
