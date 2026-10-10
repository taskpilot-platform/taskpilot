package com.taskpilot.ai.heuristic;

import com.taskpilot.ai.controller.AiChatController;
import com.taskpilot.ai.dto.AutoAssignmentRequest;
import com.taskpilot.ai.dto.RecommendationView;
import com.taskpilot.ai.service.AutoAssignmentService;
import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.ai.tools.domain.AhpAssignmentAiTools;
import com.taskpilot.contracts.aiquery.dto.TaskAssignmentResultDto;
import com.taskpilot.contracts.aiquery.dto.TaskDetailDto;
import com.taskpilot.contracts.aiquery.port.out.MemberAnalyticsPort;
import com.taskpilot.contracts.aiquery.port.out.ProjectInsightsPort;
import com.taskpilot.contracts.aiquery.port.out.TaskCommandPort;
import com.taskpilot.contracts.assignment.dto.ProjectHeuristicConfigDto;
import com.taskpilot.contracts.assignment.dto.ProjectMemberDto;
import com.taskpilot.contracts.assignment.dto.UserProfileDto;
import com.taskpilot.contracts.assignment.dto.UserSkillDto;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.contracts.assignment.port.out.ProjectPort;
import com.taskpilot.contracts.assignment.port.out.UserPort;
import com.taskpilot.contracts.assignment.port.out.UserSkillPort;
import com.taskpilot.contracts.user.dto.UserIdentityDto;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.infrastructure.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 1 Group 1 & Group 2 Tests: Recommendation Authorization and REST Pipeline Convergence
 * Implements the 13 required authorization policy tests per Phase 1 B2 specification.
 */
@ExtendWith(MockitoExtension.class)
class RecommendationAuthorizationAndRestTest {

    @Mock
    private ProjectMemberPort projectMemberPort;
    @Mock
    private UserSkillPort userSkillPort;
    @Mock
    private UserPort userPort;
    @Mock
    private ProjectPort projectPort;
    @Mock
    private HeuristicStrategyFactory heuristicStrategyFactory;
    @Mock
    private UserIdentityPort userIdentityPort;
    @Mock
    private Authentication authentication;

    @Mock
    private TaskCommandPort taskCommandPort;
    @Mock
    private ProjectInsightsPort projectInsightsPort;
    @Mock
    private MemberAnalyticsPort memberAnalyticsPort;
    @Mock
    private PendingAiActionService pendingAiActionService;

    private AutoAssignmentService autoAssignmentService;
    private AiChatController aiChatController;
    private AhpAssignmentAiTools ahpAssignmentAiTools;

    private static final Long PROJECT_ID = 100L;
    private static final Long TASK_ID = 500L;
    private static final Long MEMBER_USER_ID = 10L;
    private static final Long MANAGER_USER_ID = 20L;
    private static final Long OUTSIDER_USER_ID = 999L;
    private static final Long ADMIN_USER_ID = 888L;
    private static final Long SESSION_ID = 12345L;

    @BeforeEach
    void setUp() {
        autoAssignmentService = new AutoAssignmentService(
                projectMemberPort,
                userSkillPort,
                null,
                userPort,
                projectPort,
                heuristicStrategyFactory,
                null,
                100L,
                "gemini-3.5-flash"
        );

        aiChatController = new AiChatController(
                null,
                null,
                null,
                null,
                null,
                autoAssignmentService,
                userIdentityPort,
                null
        );

        ahpAssignmentAiTools = new AhpAssignmentAiTools(
                autoAssignmentService,
                projectMemberPort,
                projectInsightsPort,
                memberAnalyticsPort,
                taskCommandPort,
                pendingAiActionService
        );
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    private void setupValidProjectAndCandidates() {
        when(projectPort.findById(PROJECT_ID))
                .thenReturn(Optional.of(new ProjectHeuristicConfigDto(PROJECT_ID, "BALANCED")));

        HeuristicConfig config = new HeuristicConfig(
                new HeuristicWeights(0.5, 0.3, 0.2),
                new HeuristicNormalizationConfig(
                        HeuristicNormalization.BENCHMARK_BENEFIT,
                        HeuristicNormalization.BENCHMARK_BENEFIT,
                        HeuristicNormalization.BENCHMARK_BENEFIT
                )
        );
        when(heuristicStrategyFactory.resolve("BALANCED")).thenReturn(new BalancedHeuristicStrategy(config));

        ProjectMemberDto member = new ProjectMemberDto(MEMBER_USER_ID, "MEMBER", 0.5);
        when(projectMemberPort.findProjectMembers(PROJECT_ID)).thenReturn(List.of(member));

        UserProfileDto profile = new UserProfileDto(MEMBER_USER_ID, "Alice", "alice@example.com", "AVAILABLE", 10);
        when(userPort.findById(MEMBER_USER_ID)).thenReturn(Optional.of(profile));
        when(userSkillPort.findByUserIdWithSkill(MEMBER_USER_ID)).thenReturn(List.of(new UserSkillDto("Java", 4)));
    }

    private void setupValidTaskDetails() {
        TaskDetailDto task = new TaskDetailDto(
                TASK_ID, PROJECT_ID, "Implement Auth Feature", "Detailed description",
                "TODO", "HIGH", 5, "Java", "2026-12-31", null, null);
        when(taskCommandPort.getTaskDetails(eq(TASK_ID), anyLong())).thenReturn(task);
    }

    // =========================================================================
    // Group 1: 13 Required Authorization Policy Tests (B2)
    // =========================================================================

    @Test
    @DisplayName("Req 1: MEMBER can view a recommendation")
    void req1_member_canViewRecommendations() {
        when(projectMemberPort.isProjectMember(PROJECT_ID, MEMBER_USER_ID)).thenReturn(true);
        setupValidProjectAndCandidates();

        RecommendationView view = autoAssignmentService.recommendCandidatesView(
                PROJECT_ID, List.of("Java"), 5, MEMBER_USER_ID, Set.of(), Set.of());

        assertNotNull(view);
        assertFalse(view.candidates().isEmpty());
        verify(projectMemberPort).isProjectMember(PROJECT_ID, MEMBER_USER_ID);
        verify(projectMemberPort).findProjectMembers(PROJECT_ID);
    }

    @Test
    @DisplayName("Req 2: MANAGER can view a recommendation")
    void req2_manager_canViewRecommendations() {
        when(projectMemberPort.isProjectMember(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);
        setupValidProjectAndCandidates();

        RecommendationView view = autoAssignmentService.recommendCandidatesView(
                PROJECT_ID, List.of("Java"), 5, MANAGER_USER_ID, Set.of(), Set.of());

        assertNotNull(view);
        assertFalse(view.candidates().isEmpty());
        verify(projectMemberPort).isProjectMember(PROJECT_ID, MANAGER_USER_ID);
        verify(projectMemberPort).findProjectMembers(PROJECT_ID);
    }

    @Test
    @DisplayName("Req 3: Outsider cannot view; no candidate port call")
    void req3_outsider_cannotView_noCandidatePortCall() {
        when(projectMemberPort.isProjectMember(PROJECT_ID, OUTSIDER_USER_ID)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                autoAssignmentService.recommendCandidatesView(
                        PROJECT_ID, List.of("Java"), 5, OUTSIDER_USER_ID, Set.of(), Set.of()));

        assertEquals(HttpStatus.FORBIDDEN.value(), ex.getStatus());
        assertTrue(ex.getMessage().contains("not authorized"));

        verify(projectMemberPort).isProjectMember(PROJECT_ID, OUTSIDER_USER_ID);
        verify(projectMemberPort, never()).findProjectMembers(anyLong());
        verify(userPort, never()).findById(anyLong());
        verify(userSkillPort, never()).findByUserIdWithSkill(anyLong());
    }

    @Test
    @DisplayName("Req 4: MEMBER cannot create an assignment preview; no pending action created")
    void req4_member_cannotCreateAssignmentPreview_noPendingAction() {
        ToolExecutionContext.set(new ToolExecutionContext.Context(MEMBER_USER_ID, SESSION_ID, "assign"));
        setupValidTaskDetails();
        when(projectMemberPort.isProjectMember(PROJECT_ID, MEMBER_USER_ID)).thenReturn(true);
        when(projectMemberPort.isProjectManager(PROJECT_ID, MEMBER_USER_ID)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                ahpAssignmentAiTools.recommendAndAssignTask(
                        TASK_ID.toString(), PROJECT_ID.toString(), "Java", "5", "test reason"));

        assertEquals(HttpStatus.FORBIDDEN.value(), ex.getStatus());
        assertTrue(ex.getMessage().contains("MANAGER"));

        verify(pendingAiActionService, never()).create(anyLong(), anyLong(), anyString(), anyString(), anyMap(), any(), any());
        verify(projectMemberPort, never()).findProjectMembers(anyLong());
    }

    @Test
    @DisplayName("Req 5: MANAGER can create an assignment preview")
    void req5_manager_canCreateAssignmentPreview() {
        ToolExecutionContext.set(new ToolExecutionContext.Context(MANAGER_USER_ID, SESSION_ID, "assign"));
        setupValidTaskDetails();
        setupValidProjectAndCandidates();
        when(projectMemberPort.isProjectMember(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);

        ahpAssignmentAiTools.recommendAndAssignTask(
                TASK_ID.toString(), PROJECT_ID.toString(), "Java", "5", "test reason");

        verify(pendingAiActionService).create(
                eq(MANAGER_USER_ID),
                eq(SESSION_ID),
                eq("recommendAndAssignTask"),
                anyString(),
                anyMap(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("Req 6: Outsider cannot create an assignment preview")
    void req6_outsider_cannotCreateAssignmentPreview() {
        ToolExecutionContext.set(new ToolExecutionContext.Context(OUTSIDER_USER_ID, SESSION_ID, "assign"));
        setupValidTaskDetails();
        when(projectMemberPort.isProjectMember(PROJECT_ID, OUTSIDER_USER_ID)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                ahpAssignmentAiTools.recommendAndAssignTask(
                        TASK_ID.toString(), PROJECT_ID.toString(), "Java", "5", "test reason"));

        assertEquals(HttpStatus.FORBIDDEN.value(), ex.getStatus());
        verify(pendingAiActionService, never()).create(anyLong(), anyLong(), anyString(), anyString(), anyMap(), any(), any());
        verify(projectMemberPort, never()).findProjectMembers(anyLong());
    }

    @Test
    @DisplayName("Req 7: MEMBER cannot execute an assignment")
    void req7_member_cannotExecuteAssignment() {
        when(projectMemberPort.isProjectMember(PROJECT_ID, MEMBER_USER_ID)).thenReturn(true);
        when(projectMemberPort.isProjectManager(PROJECT_ID, MEMBER_USER_ID)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                autoAssignmentService.validateProjectManager(PROJECT_ID, MEMBER_USER_ID));

        assertEquals(HttpStatus.FORBIDDEN.value(), ex.getStatus());
        assertTrue(ex.getMessage().contains("MANAGER"));
    }

    @Test
    @DisplayName("Req 8: MANAGER can execute an assignment")
    void req8_manager_canExecuteAssignment() {
        when(projectMemberPort.isProjectMember(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);

        assertDoesNotThrow(() ->
                autoAssignmentService.validateProjectManager(PROJECT_ID, MANAGER_USER_ID));
    }

    @Test
    @DisplayName("Req 9: Execution-time authorization: MANAGER role is revalidated immediately before assignment")
    void req9_executionTimeAuthorization_managerRevalidatedBeforeAssignment() {
        ToolExecutionContext.set(new ToolExecutionContext.Context(MANAGER_USER_ID, SESSION_ID, "assign"));
        setupValidTaskDetails();
        setupValidProjectAndCandidates();
        when(projectMemberPort.isProjectMember(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);

        ahpAssignmentAiTools.recommendAndAssignTask(
                TASK_ID.toString(), PROJECT_ID.toString(), "Java", "5", "test reason");

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.function.Supplier<Object>> executorCaptor =
                org.mockito.ArgumentCaptor.forClass(java.util.function.Supplier.class);
        verify(pendingAiActionService).create(
                eq(MANAGER_USER_ID),
                eq(SESSION_ID),
                eq("recommendAndAssignTask"),
                anyString(),
                anyMap(),
                any(),
                executorCaptor.capture()
        );

        java.util.function.Supplier<Object> executor = executorCaptor.getValue();

        // 1. If user remains MANAGER at confirm time: execution proceeds
        when(taskCommandPort.assignTaskToMember(eq(TASK_ID), anyLong(), anyString(), eq(MANAGER_USER_ID), eq(false)))
                .thenReturn(TaskAssignmentResultDto.success(TASK_ID, 1L, "assigned"));
        assertDoesNotThrow(executor::get);
        verify(taskCommandPort).assignTaskToMember(eq(TASK_ID), anyLong(), anyString(), eq(MANAGER_USER_ID), eq(false));

        // 2. If user is demoted to MEMBER before confirmation: execution throws 403 and assignment is not called again
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(false);
        BusinessException ex = assertThrows(BusinessException.class, executor::get);
        assertEquals(HttpStatus.FORBIDDEN.value(), ex.getStatus());
        assertTrue(ex.getMessage().contains("MANAGER"));
    }

    @Test
    @DisplayName("Req 10: ADMIN behavior is explicit (member-or-manager rules apply when no override exists)")
    void req10_adminBehavior_isExplicit() {
        // Case 1: Global ADMIN who is not a project member is rejected on recommendation view
        when(projectMemberPort.isProjectMember(PROJECT_ID, ADMIN_USER_ID)).thenReturn(false);
        BusinessException exView = assertThrows(BusinessException.class, () ->
                autoAssignmentService.recommendCandidatesView(
                        PROJECT_ID, List.of("Java"), 5, ADMIN_USER_ID, Set.of(), Set.of()));
        assertEquals(HttpStatus.FORBIDDEN.value(), exView.getStatus());
        verify(projectMemberPort, never()).findProjectMembers(anyLong());

        // Case 2: Global ADMIN who is a member but not a manager is rejected on assignment
        when(projectMemberPort.isProjectMember(PROJECT_ID, ADMIN_USER_ID)).thenReturn(true);
        when(projectMemberPort.isProjectManager(PROJECT_ID, ADMIN_USER_ID)).thenReturn(false);
        BusinessException exAssign = assertThrows(BusinessException.class, () ->
                autoAssignmentService.validateProjectManager(PROJECT_ID, ADMIN_USER_ID));
        assertEquals(HttpStatus.FORBIDDEN.value(), exAssign.getStatus());
    }

    @Test
    @DisplayName("Req 11: Invalid or null project id exposes no candidate data")
    void req11_nullProjectId_failsFast_noCandidateDataExposed() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                autoAssignmentService.recommendCandidatesView(
                        null, List.of("Java"), 5, MEMBER_USER_ID, Set.of(), Set.of()));

        assertEquals(HttpStatus.BAD_REQUEST.value(), ex.getStatus());
        verify(projectMemberPort, never()).findProjectMembers(any());
        verify(userPort, never()).findById(any());
    }

    @Test
    @DisplayName("Req 12: REST uses authenticated identity, not request value")
    void req12_restUsesAuthenticatedIdentity() {
        when(authentication.getName()).thenReturn("alice@example.com");
        when(userIdentityPort.findByEmail("alice@example.com"))
                .thenReturn(Optional.of(new UserIdentityDto(MEMBER_USER_ID, "alice@example.com")));
        when(projectMemberPort.isProjectMember(PROJECT_ID, MEMBER_USER_ID)).thenReturn(true);
        setupValidProjectAndCandidates();

        AutoAssignmentRequest request = new AutoAssignmentRequest(PROJECT_ID, List.of("Java"), 5, "Test Task");
        ApiResponse<RecommendationView> response = aiChatController.autoAssign(request, authentication);

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        verify(userIdentityPort).findByEmail("alice@example.com");
        verify(projectMemberPort).isProjectMember(PROJECT_ID, MEMBER_USER_ID);
    }

    @Test
    @DisplayName("Req 13: AI tools use existing tool identity mechanism, not LLM argument")
    void req13_aiToolsUseToolExecutionContext_notLlmArgument() {
        // Without ToolExecutionContext, tools fail fast with IllegalStateException
        ToolExecutionContext.clear();
        assertThrows(IllegalStateException.class, () ->
                ahpAssignmentAiTools.recommendAssignmentCandidates(PROJECT_ID.toString(), "Java", "5"));

        // With ToolExecutionContext bound, acting user is securely taken from context
        ToolExecutionContext.set(new ToolExecutionContext.Context(MEMBER_USER_ID, SESSION_ID, "recommend"));
        when(projectMemberPort.isProjectMember(PROJECT_ID, MEMBER_USER_ID)).thenReturn(true);
        setupValidProjectAndCandidates();

        RecommendationView view = ahpAssignmentAiTools.recommendAssignmentCandidates(PROJECT_ID.toString(), "Java", "5");
        assertNotNull(view);
        verify(projectMemberPort).isProjectMember(PROJECT_ID, MEMBER_USER_ID);
    }
}
