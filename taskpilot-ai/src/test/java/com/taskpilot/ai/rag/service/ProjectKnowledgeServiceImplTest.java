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
    private EmbeddingGateway embeddingGateway;

    private DocumentDiversityContextSelector diversityContextSelector;
    private ProjectKnowledgeServiceImpl service;

    @BeforeEach
    void setUp() {
        diversityContextSelector = new DocumentDiversityContextSelector();
        service = new ProjectKnowledgeServiceImpl(
                projectMemberPort,
                documentChunkRepository,
                embeddingGateway,
                diversityContextSelector
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
        when(embeddingGateway.embedForSearch(query)).thenReturn(mockVector);

        ScoredChunk chunk = new ScoredChunk(1L, 100L, projectId, 0, "OAuth2 flow details", 0.88);
        when(documentChunkRepository.findByProjectAndNearest(projectId, mockVector, 5, 0.50))
                .thenReturn(List.of(chunk));

        List<ScoredChunk> results = service.searchKnowledge(projectId, userId, query, 5, 0.50);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("OAuth2 flow details");
        assertThat(results.get(0).similarity()).isEqualTo(0.88);

        verify(projectMemberPort).isProjectMember(projectId, userId);
        verify(embeddingGateway).embedForSearch(query);
        verify(documentChunkRepository).findByProjectAndNearest(projectId, mockVector, 5, 0.50);
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
        verify(embeddingGateway, never()).embedForSearch(anyString());
        verify(documentChunkRepository, never()).findByProjectAndNearest(any(), any(), anyInt(), anyDouble());
        verify(documentChunkRepository, never()).findByDocumentAndNearest(any(), any(), any(), anyInt(), anyDouble());
    }

    @Test
    @DisplayName("Verify getKnowledgeContext produces clean formatted prompt context using project-wide diversity")
    void testGetKnowledgeContextFormatted() {
        Long projectId = 10L;
        Long userId = 5L;

        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        when(embeddingGateway.embedForSearch("requirements")).thenReturn(new float[768]);

        ScoredChunk chunk = new ScoredChunk(1L, 100L, projectId, 0, "Requirement 1: RAG PGVector", 0.91);
        when(documentChunkRepository.findByProjectAndNearest(eq(projectId), any(), eq(20), anyDouble()))
                .thenReturn(List.of(chunk));

        String context = service.getKnowledgeContext(projectId, userId, "requirements", 3);

        assertThat(context).contains("Relevant project documentation:");
        assertThat(context).contains("Requirement 1: RAG PGVector");
        assertThat(context).contains("0.91");
        verify(documentChunkRepository).findByProjectAndNearest(eq(projectId), any(), eq(20), eq(0.40));
    }

    @Test
    @DisplayName("Verify getKnowledgeContext with specific documentId uses document-focused path")
    void testGetKnowledgeContextDocumentFocused() {
        Long projectId = 10L;
        Long documentId = 42L;
        Long userId = 5L;

        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        when(embeddingGateway.embedForSearch("security")).thenReturn(new float[768]);

        ScoredChunk chunk = new ScoredChunk(2L, documentId, projectId, 1, "Focused doc chunk", 0.95, "Spec.pdf");
        when(documentChunkRepository.findByDocumentAndNearest(eq(projectId), eq(documentId), any(), eq(6), eq(0.40)))
                .thenReturn(List.of(chunk));

        String context = service.getKnowledgeContext(projectId, documentId, userId, "security", 6);

        assertThat(context).contains("Relevant project documentation:");
        assertThat(context).contains("Focused doc chunk");
        assertThat(context).contains("Spec.pdf");
        verify(documentChunkRepository).findByDocumentAndNearest(eq(projectId), eq(documentId), any(), eq(6), eq(0.40));
        verify(documentChunkRepository, never()).findByProjectAndNearest(any(), any(), anyInt(), anyDouble());
    }

    @Test
    @DisplayName("Verify getKnowledgeContext returns fallback message when no documents match")
    void testGetKnowledgeContextEmpty() {
        Long projectId = 10L;
        Long userId = 5L;

        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        when(embeddingGateway.embedForSearch("unmatched query")).thenReturn(new float[768]);
        when(documentChunkRepository.findByProjectAndNearest(eq(projectId), any(), eq(20), anyDouble()))
                .thenReturn(List.of());

        String context = service.getKnowledgeContext(projectId, userId, "unmatched query", 5);

        assertThat(context).isEqualTo("No relevant project documents found for query.");
    }

    @Test
    @DisplayName("Verify getContextChunks uses candidateLimit=20 and rescues Chunk 517 via diversity soft-cap")
    void testGetContextChunksProjectWideDiversityRescuesChunk517() {
        Long projectId = 4L;
        Long userId = 5L;
        String query = "data security and privacy";

        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        when(embeddingGateway.embedForSearch(query)).thenReturn(new float[768]);

        // Mock 6 candidates: 5 from Doc 3 (ranks 1-5), 1 from Doc 9 Chunk 517 (rank 6)
        ScoredChunk d3c1 = new ScoredChunk(101L, 3L, projectId, 75, "Doc 3 chunk 75", 0.72);
        ScoredChunk d3c2 = new ScoredChunk(102L, 3L, projectId, 77, "Doc 3 chunk 77", 0.71);
        ScoredChunk d3c3 = new ScoredChunk(103L, 3L, projectId, 76, "Doc 3 chunk 76", 0.70);
        ScoredChunk d3c4 = new ScoredChunk(104L, 3L, projectId, 74, "Doc 3 chunk 74", 0.68);
        ScoredChunk d3c5 = new ScoredChunk(105L, 3L, projectId, 5, "Doc 3 chunk 5", 0.67);
        ScoredChunk d9c517 = new ScoredChunk(1270L, 9L, projectId, 517, "Doc 9 chunk 517 EMR", 0.66, "OOAD.docx");

        when(documentChunkRepository.findByProjectAndNearest(eq(projectId), any(), eq(20), eq(0.40)))
                .thenReturn(List.of(d3c1, d3c2, d3c3, d3c4, d3c5, d9c517));

        List<ScoredChunk> result = service.getContextChunks(projectId, userId, query, 6);

        // Verification of candidate pool and diversity
        verify(documentChunkRepository).findByProjectAndNearest(eq(projectId), any(), eq(20), eq(0.40));
        assertThat(result).hasSize(6);

        // Pass 1: Doc 3 capped at 2 (d3c1, d3c2), Doc 9 chunk 517 selected at slot 3!
        assertThat(result.get(0).chunkId()).isEqualTo(101L);
        assertThat(result.get(1).chunkId()).isEqualTo(102L);
        assertThat(result.get(2).chunkId()).isEqualTo(1270L); // Rescued!
        assertThat(result.get(2).chunkIndex()).isEqualTo(517);

        // Pass 2 backfill: remaining slots from overflow (d3c3, d3c4, d3c5)
        assertThat(result.get(3).chunkId()).isEqualTo(103L);
        assertThat(result.get(4).chunkId()).isEqualTo(104L);
        assertThat(result.get(5).chunkId()).isEqualTo(105L);
    }

    @Test
    @DisplayName("Verify getContextChunks with documentId uses candidateLimit=6 and bypasses diversity")
    void testGetContextChunksDocumentFocused() {
        Long projectId = 4L;
        Long documentId = 9L;
        Long userId = 5L;
        String query = "data security";

        when(projectMemberPort.isProjectMember(projectId, userId)).thenReturn(true);
        when(embeddingGateway.embedForSearch(query)).thenReturn(new float[768]);

        ScoredChunk d9c517 = new ScoredChunk(1270L, documentId, projectId, 517, "Doc 9 chunk 517", 0.85);
        when(documentChunkRepository.findByDocumentAndNearest(eq(projectId), eq(documentId), any(), eq(6), eq(0.40)))
                .thenReturn(List.of(d9c517));

        List<ScoredChunk> result = service.getContextChunks(projectId, documentId, userId, query, 6);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).chunkIndex()).isEqualTo(517);
        verify(documentChunkRepository).findByDocumentAndNearest(eq(projectId), eq(documentId), any(), eq(6), eq(0.40));
        verify(documentChunkRepository, never()).findByProjectAndNearest(any(), any(), anyInt(), anyDouble());
    }
}
