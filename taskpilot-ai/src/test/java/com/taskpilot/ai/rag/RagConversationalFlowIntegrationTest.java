package com.taskpilot.ai.rag;

import com.taskpilot.ai.rag.domain.ScoredChunk;
import com.taskpilot.ai.rag.repository.DocumentChunkRepository;
import com.taskpilot.ai.rag.service.EmbeddingGateway;
import com.taskpilot.ai.rag.service.ProjectKnowledgeService;
import com.taskpilot.ai.rag.service.ProjectKnowledgeServiceImpl;
import com.taskpilot.ai.service.SmartRoutingService;
import com.taskpilot.ai.service.ToolCallingRegistryService;
import com.taskpilot.ai.tools.TaskPilotAiTools;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.ai.tools.domain.KnowledgeAiTools;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagConversationalFlowIntegrationTest {

    @Mock
    private ProjectMemberPort projectMemberPort;
    @Mock
    private DocumentChunkRepository documentChunkRepository;
    @Mock
    private EmbeddingGateway embeddingGateway;
    @Mock
    private SmartRoutingService smartRoutingService;

    private ProjectKnowledgeService projectKnowledgeService;
    private KnowledgeAiTools knowledgeAiTools;
    private TaskPilotAiTools taskPilotAiTools;
    private ToolCallingRegistryService registryService;

    private static final Long PROJECT_ID = 100L;
    private static final Long AUTHORIZED_USER_ID = 5L;
    private static final Long UNAUTHORIZED_USER_ID = 999L;

    @BeforeEach
    void setUp() {
        projectKnowledgeService = new ProjectKnowledgeServiceImpl(
                projectMemberPort,
                documentChunkRepository,
                embeddingGateway
        );

        knowledgeAiTools = new KnowledgeAiTools(projectKnowledgeService);

        // Instantiate TaskPilotAiTools with knowledge tools (9 domain tool arguments)
        taskPilotAiTools = new TaskPilotAiTools(
                null, null, null, null, null, null, null, null, knowledgeAiTools
        );

        registryService = new ToolCallingRegistryService(taskPilotAiTools, smartRoutingService);
        // Initialize LangChain4j ToolService mapping
        registryService.init();

    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    @Test
    @DisplayName("Verify full RAG conversation tool-call flow: query -> tool dispatch -> vector retrieval -> grounded tool output")
    void testRagConversationalFlowSuccess() {
        // GIVEN: Active user session in project context
        ToolExecutionContext.set(new ToolExecutionContext.Context(
                AUTHORIZED_USER_ID, 42L, "Trong tài liệu dự án này deadline bàn giao là ngày nào?"
        ));

        when(projectMemberPort.isProjectMember(PROJECT_ID, AUTHORIZED_USER_ID)).thenReturn(true);

        float[] mockVector = new float[768];
        mockVector[0] = 0.85f;
        when(embeddingGateway.embedForSearch("deadline bàn giao")).thenReturn(mockVector);

        ScoredChunk deadlineChunk = new ScoredChunk(
                1L, 10L, PROJECT_ID, 0,
                "Kế hoạch dự án TaskPilot: Deadline bàn giao Phase 1 là ngày 15/10/2026.",
                0.89
        );
        when(documentChunkRepository.findNearestChunks(eq(PROJECT_ID), eq(mockVector), eq(5), anyDouble()))
                .thenReturn(List.of(deadlineChunk));

        // WHEN: LangChain4j LLM requests tool execution for searchProjectKnowledge
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call_rag_01")
                .name("searchProjectKnowledge")
                .arguments("{\"projectId\": 100, \"query\": \"deadline bàn giao\"}")
                .build();

        String toolResult = registryService.execute(request);

        // THEN: Verify tool result contains project knowledge chunk
        assertThat(toolResult).isNotNull();
        assertThat(toolResult).contains("Kế hoạch dự án TaskPilot: Deadline bàn giao Phase 1 là ngày 15/10/2026.");
        assertThat(toolResult).contains("0.89");

        verify(projectMemberPort).isProjectMember(PROJECT_ID, AUTHORIZED_USER_ID);
        verify(embeddingGateway).embedForSearch("deadline bàn giao");
        verify(documentChunkRepository).findNearestChunks(eq(PROJECT_ID), eq(mockVector), eq(5), anyDouble());
    }

    @Test
    @DisplayName("Verify tenant security gate: unauthorized user attempting RAG query receives 403 Forbidden before embedding")
    void testRagConversationalFlowUnauthorizedUser() {
        // GIVEN: User attempting cross-project access
        ToolExecutionContext.set(new ToolExecutionContext.Context(
                UNAUTHORIZED_USER_ID, 99L, "Tài liệu mật của dự án 100"
        ));

        when(projectMemberPort.isProjectMember(PROJECT_ID, UNAUTHORIZED_USER_ID)).thenReturn(false);

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call_rag_attack")
                .name("searchProjectKnowledge")
                .arguments("{\"projectId\": 100, \"query\": \"tài liệu mật\"}")
                .build();

        // WHEN: Unauthorized tool execution is dispatched
        String toolResult = registryService.execute(request);

        // THEN: Result indicates access denial
        assertThat(toolResult).contains("is not authorized to access knowledge for project 100");

        // Security Invariant: Zero external embedding API calls and zero vector DB queries

        verifyNoInteractions(embeddingGateway);
        verifyNoInteractions(documentChunkRepository);
    }

    @Test
    @DisplayName("Verify irrelevant query returns empty result list without fabricating chunks")
    void testRagConversationalFlowIrrelevantQuery() {
        ToolExecutionContext.set(new ToolExecutionContext.Context(
                AUTHORIZED_USER_ID, 42L, "Công thức nướng bánh pizza"
        ));

        when(projectMemberPort.isProjectMember(PROJECT_ID, AUTHORIZED_USER_ID)).thenReturn(true);

        float[] mockVector = new float[768];
        mockVector[10] = 0.1f;
        when(embeddingGateway.embedForSearch("công thức nướng bánh pizza")).thenReturn(mockVector);

        // Vector repository returns empty list because all scores are below threshold minScore
        when(documentChunkRepository.findNearestChunks(eq(PROJECT_ID), eq(mockVector), eq(5), anyDouble()))
                .thenReturn(List.of());

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call_rag_pizza")
                .name("searchProjectKnowledge")
                .arguments("{\"projectId\": 100, \"query\": \"công thức nướng bánh pizza\"}")
                .build();

        String toolResult = registryService.execute(request);

        assertThat(toolResult).contains("No relevant project documents found for query");
        verify(projectMemberPort).isProjectMember(PROJECT_ID, AUTHORIZED_USER_ID);
        verify(documentChunkRepository).findNearestChunks(eq(PROJECT_ID), eq(mockVector), eq(5), anyDouble());
    }


    @Test
    @DisplayName("Verify searchProjectKnowledge is discoverable in ToolSpecification registry with expected parameters")
    void testSearchProjectKnowledgeSpecification() {
        List<ToolSpecification> specs = registryService.toolSpecifications();
        Optional<ToolSpecification> ragToolSpec = specs.stream()
                .filter(s -> "searchProjectKnowledge".equals(s.name()))
                .findFirst();


        assertThat(ragToolSpec).isPresent();
        ToolSpecification spec = ragToolSpec.get();
        assertThat(spec.description()).contains("project");
        assertThat(spec.parameters()).isNotNull();
    }
}
