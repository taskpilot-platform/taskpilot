package com.taskpilot.ai.rag;

import com.taskpilot.ai.rag.config.RagEmbeddingProperties;
import com.taskpilot.ai.rag.domain.DocumentChunk;
import com.taskpilot.ai.rag.domain.DocumentStatus;
import com.taskpilot.ai.rag.domain.ScoredChunk;
import com.taskpilot.ai.rag.entity.DocumentEntity;
import com.taskpilot.ai.rag.repository.DocumentChunkRepository;
import com.taskpilot.ai.rag.repository.DocumentRepository;
import com.taskpilot.ai.rag.service.*;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.ai.tools.domain.KnowledgeAiTools;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.infrastructure.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.access.AccessDeniedException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagEndToEndIntegrationTest {

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
    private ProjectMemberPort projectMemberPort;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private DocumentIngestionService ingestionService;
    private ProjectKnowledgeService knowledgeService;
    private KnowledgeAiTools knowledgeAiTools;
    private RagEmbeddingProperties properties;

    private static final Long PROJECT_ID = 100L;
    private static final Long MEMBER_USER_ID = 5L;
    private static final Long NON_MEMBER_USER_ID = 999L;

    @BeforeEach
    void setUp() {
        properties = new RagEmbeddingProperties();

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

        knowledgeService = new ProjectKnowledgeServiceImpl(
                projectMemberPort,
                documentChunkRepository,
                embeddingGateway
        );

        knowledgeAiTools = new KnowledgeAiTools(knowledgeService);
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    @Test
    @DisplayName("Verify Full End-to-End Workflow: Ingestion -> Vector Storage -> Query Retrieval -> AI Tool Invocation")
    @SuppressWarnings("unchecked")
    void testCompleteRagLifecycle() throws IOException {
        // --- STEP 1: Ingestion Pipeline ---
        DocumentEntity document = DocumentEntity.builder()
                .id(1L)
                .projectId(PROJECT_ID)
                .storageKey("projects/100/architecture.md")
                .originalFilename("architecture.md")
                .contentType("text/markdown")
                .status(DocumentStatus.PROCESSING)
                .processingVersion(1)
                .build();

        when(documentRepository.findById(1L)).thenReturn(Optional.of(document));
        when(storageService.downloadFile("documents", "projects/100/architecture.md"))
                .thenReturn(new ByteArrayInputStream("Architecture text".getBytes()));
        when(textExtractor.extractText(any(), eq("architecture.md"), eq("text/markdown")))
                .thenReturn("TaskPilot RAG subsystem architecture with PostgreSQL pgvector and LangChain4j");
        when(chunker.chunkText(anyString()))
                .thenReturn(List.of("Chunk 1: RAG PGVector Architecture"));

        float[] sampleEmbedding = new float[768];
        sampleEmbedding[0] = 0.77f;
        when(embeddingGateway.embedForIngestion(anyList())).thenReturn(List.of(sampleEmbedding));

        // Mock JDBC finalization
        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(1));
        when(jdbcTemplate.update(anyString(), eq(1L), eq(1))).thenReturn(1);

        ingestionService.ingestDocument(1L, 1);

        // Verify chunks were saved
        ArgumentCaptor<List<DocumentChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(documentChunkRepository).saveAll(chunksCaptor.capture());
        assertThat(chunksCaptor.getValue()).hasSize(1);
        assertThat(chunksCaptor.getValue().get(0).projectId()).isEqualTo(PROJECT_ID);

        // --- STEP 2: Retrieval via ProjectKnowledgeService ---
        when(projectMemberPort.isProjectMember(PROJECT_ID, MEMBER_USER_ID)).thenReturn(true);
        when(embeddingGateway.embedForSearch("How is RAG architected?")).thenReturn(sampleEmbedding);

        ScoredChunk retrievedChunk = new ScoredChunk(
                10L, 1L, PROJECT_ID, 0,
                "Chunk 1: RAG PGVector Architecture",
                0.88
        );
        when(documentChunkRepository.findNearestChunks(eq(PROJECT_ID), eq(sampleEmbedding), eq(5), anyDouble()))
                .thenReturn(List.of(retrievedChunk));

        List<ScoredChunk> searchResults = knowledgeService.searchKnowledge(
                PROJECT_ID, MEMBER_USER_ID, "How is RAG architected?", 5, 0.40
        );
        assertThat(searchResults).hasSize(1);
        assertThat(searchResults.get(0).content()).contains("RAG PGVector Architecture");
        assertThat(searchResults.get(0).similarity()).isEqualTo(0.88);

        // --- STEP 3: Invocation through AI Tool (TaskPilotAiTools / KnowledgeAiTools) ---
        ToolExecutionContext.set(new ToolExecutionContext.Context(MEMBER_USER_ID, 123L, "Tell me about RAG"));

        Object toolResult = knowledgeAiTools.searchProjectKnowledge(PROJECT_ID, "How is RAG architected?", 5);
        assertThat(toolResult).isInstanceOf(List.class);

        List<Map<String, Object>> toolList = (List<Map<String, Object>>) toolResult;
        assertThat(toolList).hasSize(1);
        assertThat(toolList.get(0).get("content")).isEqualTo("Chunk 1: RAG PGVector Architecture");
        assertThat(toolList.get(0).get("similarity")).isEqualTo(0.88);
    }

    @Test
    @DisplayName("Verify Cross-Tenant Security Isolation: Non-member is rejected at AI Tool level with AccessDeniedException")
    void testCrossTenantRejectionAtToolLevel() {
        ToolExecutionContext.set(new ToolExecutionContext.Context(NON_MEMBER_USER_ID, 456L, "Steal docs"));

        when(projectMemberPort.isProjectMember(PROJECT_ID, NON_MEMBER_USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> knowledgeAiTools.searchProjectKnowledge(PROJECT_ID, "Confidential docs", 5))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not authorized to access knowledge for project " + PROJECT_ID);

        // Verify zero vector search or embedding took place
        verify(embeddingGateway, never()).embedForSearch(anyString());
        verify(documentChunkRepository, never()).findNearestChunks(any(), any(), anyInt(), anyDouble());
    }
}
