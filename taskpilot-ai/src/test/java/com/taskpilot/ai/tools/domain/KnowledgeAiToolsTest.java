package com.taskpilot.ai.tools.domain;

import com.taskpilot.ai.rag.domain.ScoredChunk;
import com.taskpilot.ai.rag.service.ProjectKnowledgeService;
import com.taskpilot.ai.tools.TaskPilotAiTools;
import com.taskpilot.ai.tools.ToolExecutionContext;
import dev.langchain4j.service.tool.ToolService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeAiToolsTest {

    @Mock
    private ProjectKnowledgeService projectKnowledgeService;

    private KnowledgeAiTools knowledgeAiTools;

    private static final Long USER_ID = 42L;
    private static final Long SESSION_ID = 100L;

    @BeforeEach
    void setUp() {
        knowledgeAiTools = new KnowledgeAiTools(projectKnowledgeService);
        ToolExecutionContext.set(new ToolExecutionContext.Context(USER_ID, SESSION_ID, "find project specs"));
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    @Test
    @DisplayName("Verify searchProjectKnowledge calls ProjectKnowledgeService.getContextChunks with context userId")
    @SuppressWarnings("unchecked")
    void testSearchProjectKnowledgeSuccess() {
        ScoredChunk chunk = new ScoredChunk(1L, 10L, 5L, 0, "System specifications: pgvector RAG", 0.89);
        when(projectKnowledgeService.getContextChunks(5L, USER_ID, "specs", 3))
                .thenReturn(List.of(chunk));

        Object result = knowledgeAiTools.searchProjectKnowledge(5L, "specs", 3);

        assertThat(result).isInstanceOf(List.class);
        List<Map<String, Object>> list = (List<Map<String, Object>>) result;
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("chunkIndex")).isEqualTo(0);
        assertThat(list.get(0).get("similarity")).isEqualTo(0.89);
        assertThat(list.get(0).get("content")).isEqualTo("System specifications: pgvector RAG");

        verify(projectKnowledgeService).getContextChunks(5L, USER_ID, "specs", 3);
    }

    @Test
    @DisplayName("Verify searchProjectKnowledge returns informative message when no chunks found")
    void testSearchProjectKnowledgeEmpty() {
        when(projectKnowledgeService.getContextChunks(5L, USER_ID, "nonexistent topic", 6))
                .thenReturn(List.of());

        Object result = knowledgeAiTools.searchProjectKnowledge(5L, "nonexistent topic", null);

        assertThat(result).isInstanceOf(String.class);
        assertThat((String) result).contains("No relevant project documents found for query: nonexistent topic");
        verify(projectKnowledgeService).getContextChunks(5L, USER_ID, "nonexistent topic", 6);
    }

    @Test
    @DisplayName("Verify document-focused searchProjectKnowledge calls getContextChunks with documentId")
    @SuppressWarnings("unchecked")
    void testSearchProjectKnowledgeWithDocumentId() {
        Long projectId = 5L;
        Long documentId = 42L;
        ScoredChunk chunk = new ScoredChunk(2L, documentId, projectId, 1, "Focused doc chunk", 0.95, "Spec.pdf");
        when(projectKnowledgeService.getContextChunks(projectId, documentId, USER_ID, "security", 6))
                .thenReturn(List.of(chunk));

        Object result = knowledgeAiTools.searchProjectKnowledge(projectId, documentId, "security", null);

        assertThat(result).isInstanceOf(List.class);
        List<Map<String, Object>> list = (List<Map<String, Object>>) result;
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("documentName")).isEqualTo("Spec.pdf");
        assertThat(list.get(0).get("content")).isEqualTo("Focused doc chunk");
        verify(projectKnowledgeService).getContextChunks(projectId, documentId, USER_ID, "security", 6);
    }

    @Test
    @DisplayName("Verify Chunk 517 Crowding Regression: searchProjectKnowledge receives and preserves Chunk 517 in tool result")
    @SuppressWarnings("unchecked")
    void testSearchProjectKnowledgePreservesChunk517UnderCrowding() {
        Long projectId = 4L;
        String query = "data security and privacy";

        // Diversified context: Doc 3 chunks capped at 2, Doc 9 Chunk 517 rescued into context
        ScoredChunk doc3Chunk75 = new ScoredChunk(107L, 3L, projectId, 75, "Maintenance DSS chunk 75", 0.72);
        ScoredChunk doc3Chunk77 = new ScoredChunk(109L, 3L, projectId, 77, "Maintenance DSS chunk 77", 0.71);
        ScoredChunk targetChunk517 = new ScoredChunk(1270L, 9L, projectId, 517, "EMR Data Security and Privacy RBAC", 0.66, "OOAD Report.docx");

        when(projectKnowledgeService.getContextChunks(projectId, USER_ID, query, 6))
                .thenReturn(List.of(doc3Chunk75, doc3Chunk77, targetChunk517));

        Object result = knowledgeAiTools.searchProjectKnowledge(projectId, query, null);

        assertThat(result).isInstanceOf(List.class);
        List<Map<String, Object>> list = (List<Map<String, Object>>) result;
        assertThat(list).hasSize(3);

        boolean chunk517Present = list.stream().anyMatch(m ->
                Integer.valueOf(517).equals(m.get("chunkIndex")) && "OOAD Report.docx".equals(m.get("documentName")));
        assertThat(chunk517Present)
                .as("Document 9 Chunk 517 must be preserved in final AI/RAG context")
                .isTrue();

        verify(projectKnowledgeService).getContextChunks(projectId, USER_ID, query, 6);
    }

    @Test
    @DisplayName("Verify searchProjectKnowledge validates required arguments")
    void testSearchProjectKnowledgeValidation() {
        Object noProject = knowledgeAiTools.searchProjectKnowledge(null, "specs", 5);
        assertThat(noProject).isEqualTo("Project ID is required to search project knowledge.");

        Object blankQuery = knowledgeAiTools.searchProjectKnowledge(5L, "   ", 5);
        assertThat(blankQuery).isEqualTo("Search query must not be empty.");
    }

    @Test
    @DisplayName("Verify TaskPilotAiTools exposes searchProjectKnowledge as a discoverable LangChain4j @Tool")
    void testTaskPilotAiToolsRegistration() {
        TaskPilotAiTools aiTools = new TaskPilotAiTools(
                mock(ProjectAiTools.class),
                mock(TaskAiTools.class),
                mock(SprintAiTools.class),
                mock(CommentAiTools.class),
                mock(NotificationAiTools.class),
                mock(SkillAiTools.class),
                mock(AhpAssignmentAiTools.class),
                mock(SystemAiTools.class),
                knowledgeAiTools
        );

        ToolService toolService = new ToolService();
        toolService.tools(List.of(aiTools));

        boolean hasKnowledgeTool = toolService.toolSpecifications().stream()
                .anyMatch(spec -> "searchProjectKnowledge".equals(spec.name()));

        assertThat(hasKnowledgeTool)
                .as("searchProjectKnowledge must be discovered by LangChain4j ToolService")
                .isTrue();
    }
}
