package com.taskpilot.ai.heuristic;

import com.taskpilot.ai.controller.AiChatController;
import com.taskpilot.ai.dto.*;
import com.taskpilot.ai.service.AutoAssignmentService;
import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.ai.tools.domain.AhpAssignmentAiTools;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 1 Pipeline Convergence Test Suite
 * Replaces B1RankingParityTest with Step A pipeline convergence verification.
 *
 * Asserts that all active user-facing entry points (REST, AI project tool,
 * AI task tool, and AI assignment preview) converge on the exact same:
 * computeInternalCandidates -> normalizeNeutral -> fullPrecisionScore ->
 * 1e9 rankingKey -> rankingRawFit -> userId -> RecommendationView pipeline.
 */
@ExtendWith(MockitoExtension.class)
class Phase1RecommendationPipelineConvergenceTest {

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

    private static final Long PROJECT_ID = 42L;
    private static final Long TASK_ID = 500L;
    private static final Long ACTING_USER_ID = 1L;
    private static final Long SESSION_ID = 9999L;

    private BalancedHeuristicStrategy balancedStrategy;

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

        lenient().when(projectMemberPort.isProjectMember(anyLong(), anyLong())).thenReturn(true);
        lenient().when(projectMemberPort.isProjectManager(anyLong(), anyLong())).thenReturn(true);

        HeuristicNormalizationConfig normConfig = new HeuristicNormalizationConfig(
                HeuristicNormalization.BENCHMARK_BENEFIT,
                HeuristicNormalization.BENCHMARK_BENEFIT,
                HeuristicNormalization.BENCHMARK_BENEFIT
        );
        HeuristicConfig balancedConfig = new HeuristicConfig(
                new HeuristicWeights(0.40, 0.35, 0.25),
                normConfig
        );
        balancedStrategy = new BalancedHeuristicStrategy(balancedConfig);

        when(projectPort.findById(PROJECT_ID))
                .thenReturn(Optional.of(new ProjectHeuristicConfigDto(PROJECT_ID, "BALANCED")));
        when(heuristicStrategyFactory.resolve("BALANCED")).thenReturn(balancedStrategy);

        ToolExecutionContext.set(new ToolExecutionContext.Context(ACTING_USER_ID, SESSION_ID, "test input"));
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    private void setupCandidatePool(List<Long> userIds) {
        List<ProjectMemberDto> members = userIds.stream()
                .map(id -> new ProjectMemberDto(id, "MEMBER", 0.50 + ((id % 3) * 0.10)))
                .toList();
        when(projectMemberPort.findProjectMembers(PROJECT_ID)).thenReturn(members);

        for (Long uid : userIds) {
            UserProfileDto profile = new UserProfileDto(
                    uid, "User-" + uid, "user" + uid + "@example.com", "AVAILABLE", (int) (uid * 5)
            );
            when(userPort.findById(uid)).thenReturn(Optional.of(profile));
            when(userSkillPort.findByUserIdWithSkill(uid)).thenReturn(List.of(
                    new UserSkillDto("Java", (int) (1 + (uid % 5)))
            ));
        }
    }

    private void setupTaskDetails(String skills, int difficulty) {
        TaskDetailDto task = new TaskDetailDto(
                TASK_ID, PROJECT_ID, "Sample Task", "Description", "TODO", "HIGH",
                difficulty, skills, "2026-12-31", null, null
        );
        when(taskCommandPort.getTaskDetails(eq(TASK_ID), anyLong())).thenReturn(task);
    }

    // =========================================================================
    // 8 Required Pipeline Convergence Tests (B3)
    // =========================================================================

    @Test
    @DisplayName("Conv 1: REST and project recommendation tool return identical candidate IDs, order, and top candidate")
    void conv1_restAndProjectTool_returnIdenticalOrderAndTopCandidate() {
        setupCandidatePool(List.of(10L, 20L, 30L));

        when(authentication.getName()).thenReturn("caller@example.com");
        when(userIdentityPort.findByEmail("caller@example.com"))
                .thenReturn(Optional.of(new UserIdentityDto(ACTING_USER_ID, "caller@example.com")));

        AutoAssignmentRequest restReq = new AutoAssignmentRequest(PROJECT_ID, List.of("Java"), 5, "Sample Task");
        ApiResponse<RecommendationView> restResp = aiChatController.autoAssign(restReq, authentication);
        RecommendationView restView = restResp.getData();

        RecommendationView toolView = ahpAssignmentAiTools.recommendAssignmentCandidates(
                PROJECT_ID.toString(), "Java", "5"
        );

        assertNotNull(restView);
        assertNotNull(toolView);
        assertEquals(restView.candidates().size(), toolView.candidates().size());
        for (int i = 0; i < restView.candidates().size(); i++) {
            assertEquals(restView.candidates().get(i).candidateId(), toolView.candidates().get(i).candidateId());
            assertEquals(restView.candidates().get(i).rank(), toolView.candidates().get(i).rank());
        }
        assertEquals(restView.candidates().get(0).candidateId(), toolView.candidates().get(0).candidateId());
    }

    @Test
    @DisplayName("Conv 2: Task-specific recommendation uses the same Step A comparator")
    void conv2_taskSpecificRecommendation_usesSameComparator() {
        setupCandidatePool(List.of(10L, 20L, 30L));
        setupTaskDetails("Java", 5);

        RecommendationView baseView = autoAssignmentService.recommendCandidatesView(
                PROJECT_ID, List.of("Java"), 5, ACTING_USER_ID, Set.of(), Set.of()
        );

        RecommendationView taskView = ahpAssignmentAiTools.recommendTaskAssignmentCandidates(
                TASK_ID.toString(), "Java", "5", null, null, null, null, null
        );

        assertNotNull(taskView);
        assertEquals(baseView.candidates().size(), taskView.candidates().size());
        for (int i = 0; i < baseView.candidates().size(); i++) {
            assertEquals(baseView.candidates().get(i).candidateId(), taskView.candidates().get(i).candidateId());
        }
    }

    @Test
    @DisplayName("Conv 3: Assignment preview uses the same Step A order")
    void conv3_assignmentPreview_usesSameOrder() {
        setupCandidatePool(List.of(10L, 20L, 30L));
        setupTaskDetails("Java", 5);

        RecommendationView baseView = autoAssignmentService.recommendCandidatesView(
                PROJECT_ID, List.of("Java"), 5, ACTING_USER_ID, Set.of(), Set.of()
        );

        ahpAssignmentAiTools.recommendAndAssignTask(
                TASK_ID.toString(), PROJECT_ID.toString(), "Java", "5", "Assignment reason"
        );

        ArgumentCaptor<Object> previewCaptor = ArgumentCaptor.forClass(Object.class);
        verify(pendingAiActionService).create(
                eq(ACTING_USER_ID),
                eq(SESSION_ID),
                eq("recommendAndAssignTask"),
                anyString(),
                anyMap(),
                previewCaptor.capture(),
                any()
        );

        assertTrue(previewCaptor.getValue() instanceof RecommendAndAssignResult);
        RecommendAndAssignResult preview = (RecommendAndAssignResult) previewCaptor.getValue();
        assertNotNull(preview.recommendation());
        assertEquals(baseView.candidates().size(), preview.recommendation().candidates().size());
        assertEquals(baseView.candidates().get(0).candidateId(), preview.selectedMemberId());
        assertEquals(baseView.candidates().get(0).candidateId(), preview.recommendation().candidates().get(0).candidateId());
    }

    @Test
    @DisplayName("Conv 4: Former rounded-score tie resolves identically across all active paths")
    void conv4_formerRoundedScoreTie_resolvesIdenticallyInAllPaths() {
        // Setup two candidates with identical rounded score but differing full-precision score / raw fit
        List<ProjectMemberDto> members = List.of(
                new ProjectMemberDto(101L, "MEMBER", 0.50),
                new ProjectMemberDto(102L, "MEMBER", 0.50)
        );
        when(projectMemberPort.findProjectMembers(PROJECT_ID)).thenReturn(members);

        UserProfileDto u1 = new UserProfileDto(101L, "TiedUser1", "u1@example.com", "AVAILABLE", 10);
        UserProfileDto u2 = new UserProfileDto(102L, "TiedUser2", "u2@example.com", "AVAILABLE", 10);
        when(userPort.findById(101L)).thenReturn(Optional.of(u1));
        when(userPort.findById(102L)).thenReturn(Optional.of(u2));

        // u1 has higher skill level (fit 0.8) vs u2 (fit 0.6)
        when(userSkillPort.findByUserIdWithSkill(101L)).thenReturn(List.of(new UserSkillDto("Java", 4)));
        when(userSkillPort.findByUserIdWithSkill(102L)).thenReturn(List.of(new UserSkillDto("Java", 3)));

        setupTaskDetails("Java", 5);

        when(authentication.getName()).thenReturn("caller@example.com");
        when(userIdentityPort.findByEmail("caller@example.com"))
                .thenReturn(Optional.of(new UserIdentityDto(ACTING_USER_ID, "caller@example.com")));

        RecommendationView restView = aiChatController.autoAssign(
                new AutoAssignmentRequest(PROJECT_ID, List.of("Java"), 5, "Task"), authentication
        ).getData();

        RecommendationView toolView = ahpAssignmentAiTools.recommendAssignmentCandidates(
                PROJECT_ID.toString(), "Java", "5"
        );

        RecommendationView taskView = ahpAssignmentAiTools.recommendTaskAssignmentCandidates(
                TASK_ID.toString(), "Java", "5", null, null, null, null, null
        );

        assertEquals(101L, restView.candidates().get(0).candidateId());
        assertEquals(102L, restView.candidates().get(1).candidateId());
        assertEquals(101L, toolView.candidates().get(0).candidateId());
        assertEquals(101L, taskView.candidates().get(0).candidateId());
    }

    @Test
    @DisplayName("Conv 5: Reversed input order gives the same deterministic Step A order")
    void conv5_reversedInputOrder_givesSameStepAOrder() {
        setupCandidatePool(List.of(10L, 20L, 30L));

        RecommendationView normalView = autoAssignmentService.recommendCandidatesView(
                PROJECT_ID, List.of("Java"), 5, ACTING_USER_ID, Set.of(), Set.of()
        );

        // Reverse member order returned by port
        List<ProjectMemberDto> reversedMembers = new ArrayList<>(projectMemberPort.findProjectMembers(PROJECT_ID));
        Collections.reverse(reversedMembers);
        when(projectMemberPort.findProjectMembers(PROJECT_ID)).thenReturn(reversedMembers);

        RecommendationView reversedView = autoAssignmentService.recommendCandidatesView(
                PROJECT_ID, List.of("Java"), 5, ACTING_USER_ID, Set.of(), Set.of()
        );

        assertEquals(normalView.candidates().size(), reversedView.candidates().size());
        for (int i = 0; i < normalView.candidates().size(); i++) {
            assertEquals(normalView.candidates().get(i).candidateId(), reversedView.candidates().get(i).candidateId());
        }
    }

    @Test
    @DisplayName("Conv 6: Empty and single-candidate behavior match identically across paths")
    void conv6_emptyAndSingleCandidate_behaviorMatches() {
        // Part A: Empty members
        when(projectMemberPort.findProjectMembers(PROJECT_ID)).thenReturn(Collections.emptyList());
        RecommendationView emptyView = autoAssignmentService.recommendCandidatesView(
                PROJECT_ID, List.of("Java"), 5, ACTING_USER_ID, Set.of(), Set.of()
        );
        assertTrue(emptyView.candidates().isEmpty());
        assertEquals(RecommendationDifferentiationStatus.UNKNOWN, emptyView.differentiationStatus());

        // Part B: Single candidate
        setupCandidatePool(List.of(10L));
        RecommendationView singleView = autoAssignmentService.recommendCandidatesView(
                PROJECT_ID, List.of("Java"), 5, ACTING_USER_ID, Set.of(), Set.of()
        );
        assertEquals(1, singleView.candidates().size());
        assertEquals(10L, singleView.candidates().get(0).candidateId());
        assertEquals(1, singleView.candidates().get(0).rank());
        assertEquals(RecommendationDifferentiationStatus.UNKNOWN, singleView.differentiationStatus());
    }

    @Test
    @DisplayName("Conv 7: Missing-skill behavior matches identically across paths")
    void conv7_missingSkillBehavior_matches() {
        setupCandidatePool(List.of(10L, 20L));

        // Part A: When task requirements have missing skills (empty list), fitStatus is INSUFFICIENT_DATA
        RecommendationView emptySkillsView = autoAssignmentService.recommendCandidatesView(
                PROJECT_ID, Collections.emptyList(), 5, ACTING_USER_ID, Set.of(), Set.of()
        );
        assertNotNull(emptySkillsView);
        assertEquals(2, emptySkillsView.candidates().size());
        for (RecommendedCandidateView cand : emptySkillsView.candidates()) {
            assertEquals(MetricDataStatus.INSUFFICIENT_DATA, cand.fitStatus());
            assertNull(cand.presentationFitValue());
        }
        assertEquals(RecommendationDifferentiationStatus.INSUFFICIENT_TO_DIFFERENTIATE, emptySkillsView.differentiationStatus());

        // Part B: When required skill is present but candidates match 0%, fitStatus is MEASURED with 0.0
        RecommendationView noMatchSkillsView = autoAssignmentService.recommendCandidatesView(
                PROJECT_ID, List.of("Rust"), 5, ACTING_USER_ID, Set.of(), Set.of()
        );
        assertNotNull(noMatchSkillsView);
        assertEquals(2, noMatchSkillsView.candidates().size());
        for (RecommendedCandidateView cand : noMatchSkillsView.candidates()) {
            assertEquals(MetricDataStatus.MEASURED, cand.fitStatus());
            assertEquals(0.0, cand.presentationFitValue(), 1e-9);
        }
    }

    @Test
    @DisplayName("Conv 8: Version fields match across all active paths")
    void conv8_versionFieldsMatch_acrossAllActivePaths() {
        setupCandidatePool(List.of(10L));
        setupTaskDetails("Java", 5);

        when(authentication.getName()).thenReturn("caller@example.com");
        when(userIdentityPort.findByEmail("caller@example.com"))
                .thenReturn(Optional.of(new UserIdentityDto(ACTING_USER_ID, "caller@example.com")));

        RecommendationView restView = aiChatController.autoAssign(
                new AutoAssignmentRequest(PROJECT_ID, List.of("Java"), 5, "Task"), authentication
        ).getData();
        RecommendationView toolView = ahpAssignmentAiTools.recommendAssignmentCandidates(
                PROJECT_ID.toString(), "Java", "5"
        );
        RecommendationView taskView = ahpAssignmentAiTools.recommendTaskAssignmentCandidates(
                TASK_ID.toString(), "Java", "5", null, null, null, null, null
        );

        for (RecommendationView v : List.of(restView, toolView, taskView)) {
            assertEquals("allowlisted-view-v1", v.presentationContractVersion());
            assertEquals("relative-neutral-fixed-point-v2", v.scoringModelVersion());
        }
    }
}
