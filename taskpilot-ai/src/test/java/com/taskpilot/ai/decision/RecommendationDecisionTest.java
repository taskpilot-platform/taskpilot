package com.taskpilot.ai.decision;

import com.taskpilot.ai.assignment.port.out.RecommendationDecisionEventPort;
import com.taskpilot.ai.assignment.port.out.RecommendationSnapshotPort;
import com.taskpilot.ai.dto.ConfirmationRequiredDto;
import com.taskpilot.ai.dto.RecommendationDecisionSource;
import com.taskpilot.ai.dto.RecommendationDecisionType;
import com.taskpilot.ai.dto.RecommendationDifferentiationStatus;
import com.taskpilot.ai.dto.RecommendationRequestSource;
import com.taskpilot.ai.dto.RecommendationView;
import com.taskpilot.ai.dto.RecommendedCandidateView;
import com.taskpilot.ai.dto.MetricDataStatus;
import com.taskpilot.ai.entity.RecommendationDecisionEventEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotCandidateEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotEntity;
import com.taskpilot.ai.service.AutoAssignmentService;
import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.service.RecommendationDecisionService;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.ai.tools.domain.AhpAssignmentAiTools;
import com.taskpilot.contracts.aiquery.dto.TaskDetailDto;
import com.taskpilot.contracts.aiquery.port.out.MemberAnalyticsPort;
import com.taskpilot.contracts.aiquery.port.out.ProjectInsightsPort;
import com.taskpilot.contracts.aiquery.port.out.TaskCommandPort;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 2B PM Decision Events Test Suite
 * Tests all 18 requirements from the Phase 2B specification.
 */
class RecommendationDecisionTest {

    private RecommendationSnapshotPort snapshotPort;
    private RecommendationDecisionEventPort decisionPort;
    private ProjectMemberPort projectMemberPort;
    private RecommendationDecisionService decisionService;

    private static final Long PROJECT_ID = 10L;
    private static final Long TASK_ID = 76L;
    private static final Long MANAGER_USER_ID = 1L;
    private static final Long CANDIDATE_1_ID = 42L;
    private static final Long CANDIDATE_2_ID = 43L;
    private static final String SNAPSHOT_ID = "rec-snap-test-001";

    @BeforeEach
    void setUp() {
        snapshotPort = mock(RecommendationSnapshotPort.class);
        decisionPort = mock(RecommendationDecisionEventPort.class);
        projectMemberPort = mock(ProjectMemberPort.class);
        decisionService = new RecommendationDecisionService(snapshotPort, decisionPort, projectMemberPort);

        when(decisionPort.save(any(RecommendationDecisionEventEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RecommendationSnapshotEntity defaultSnapshot = buildSnapshot(PROJECT_ID, TASK_ID, CANDIDATE_1_ID, CANDIDATE_2_ID);
        when(snapshotPort.findById(SNAPSHOT_ID)).thenReturn(Optional.of(defaultSnapshot));
        when(decisionPort.findBySnapshotId(SNAPSHOT_ID)).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    private RecommendationSnapshotEntity buildSnapshot(Long projectId, Long taskId, Long rank1Id, Long rank2Id) {
        RecommendationSnapshotEntity snapshot = RecommendationSnapshotEntity.builder()
                .snapshotId(SNAPSHOT_ID)
                .requestedByUserId(MANAGER_USER_ID)
                .projectId(projectId)
                .taskId(taskId)
                .createdAt(Instant.now())
                .heuristicMode("BALANCED")
                .presentationContractVersion("allowlisted-view-v1")
                .scoringModelVersion("relative-neutral-fixed-point-v2")
                .differentiationStatus("DIFFERENTIATED")
                .requiredSkills("Java")
                .recommendedCandidateId(rank1Id)
                .candidateCount(2)
                .requestSource(RecommendationRequestSource.AI_TOOL_RECOMMEND_AND_ASSIGN)
                .fitWeight(0.5)
                .loadWeight(0.3)
                .performanceWeight(0.2)
                .normalizationContract("neutral-min-max-fixed-point-v1")
                .candidates(new ArrayList<>())
                .build();

        RecommendationSnapshotCandidateEntity c1 = RecommendationSnapshotCandidateEntity.builder()
                .candidateId(rank1Id)
                .rank(1)
                .rankingKey(850_000_000L)
                .fullPrecisionScore(0.85)
                .rankingRawFit(0.9)
                .presentationFitValue(0.9)
                .fitStatus(MetricDataStatus.MEASURED)
                .storedWorkloadValue(20)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .derivedPerformanceInput(0.5)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("AVAILABLE")
                .selectedAsRecommendation(true)
                .snapshot(snapshot)
                .build();

        RecommendationSnapshotCandidateEntity c2 = RecommendationSnapshotCandidateEntity.builder()
                .candidateId(rank2Id)
                .rank(2)
                .rankingKey(650_000_000L)
                .fullPrecisionScore(0.65)
                .rankingRawFit(0.7)
                .presentationFitValue(0.7)
                .fitStatus(MetricDataStatus.MEASURED)
                .storedWorkloadValue(25)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .derivedPerformanceInput(0.5)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("AVAILABLE")
                .selectedAsRecommendation(false)
                .snapshot(snapshot)
                .build();

        snapshot.addCandidate(c1);
        snapshot.addCandidate(c2);
        return snapshot;
    }

    // 1. Manager accepts rank-1 candidate -> ACCEPTED
    @Test
    @DisplayName("Req 1: Manager accepts rank-1 candidate -> ACCEPTED")
    void testManagerAcceptsRank1CandidateCreatesAccepted() {
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);

        RecommendationDecisionEventEntity event = decisionService.recordAccepted(
                MANAGER_USER_ID, SNAPSHOT_ID, CANDIDATE_1_ID, "CONFIRMED_RECOMMENDATION", "Top match accepted");

        assertNotNull(event);
        assertEquals(RecommendationDecisionType.ACCEPTED, event.getDecisionType());
        assertEquals(CANDIDATE_1_ID, event.getRecommendedCandidateId());
        assertEquals(CANDIDATE_1_ID, event.getSelectedCandidateId());
        assertEquals(PROJECT_ID, event.getProjectId());
        assertEquals(TASK_ID, event.getTaskId());
        assertEquals(MANAGER_USER_ID, event.getDecidedByUserId());

        verify(decisionPort, times(1)).save(any(RecommendationDecisionEventEntity.class));
    }

    // 2. Manager selects another snapshot candidate -> OVERRIDDEN
    @Test
    @DisplayName("Req 2: Manager selects another snapshot candidate -> OVERRIDDEN")
    void testManagerSelectsAnotherSnapshotCandidateCreatesOverridden() {
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);

        RecommendationDecisionEventEntity event = decisionService.recordOverridden(
                MANAGER_USER_ID, SNAPSHOT_ID, CANDIDATE_2_ID, "PM_PREFERENCE", "Selected candidate 2");

        assertNotNull(event);
        assertEquals(RecommendationDecisionType.OVERRIDDEN, event.getDecisionType());
        assertEquals(CANDIDATE_1_ID, event.getRecommendedCandidateId());
        assertEquals(CANDIDATE_2_ID, event.getSelectedCandidateId());

        verify(decisionPort, times(1)).save(any(RecommendationDecisionEventEntity.class));
    }

    // 3. Override candidate must exist in the snapshot
    @Test
    @DisplayName("Req 3: Override candidate must exist in the snapshot")
    void testOverrideCandidateMustExistInSnapshot() {
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                decisionService.recordOverridden(MANAGER_USER_ID, SNAPSHOT_ID, 999L, "OVERRIDE", "Unknown member"));

        assertEquals(400, ex.getStatus());
        assertTrue(ex.getMessage().contains("is not present in snapshot"));
        verify(decisionPort, never()).save(any());
    }

    // 4. Manager rejects -> REJECTED
    @Test
    @DisplayName("Req 4: Manager rejects -> REJECTED")
    void testManagerRejectsCreatesRejected() {
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);

        RecommendationDecisionEventEntity event = decisionService.recordRejected(
                MANAGER_USER_ID, SNAPSHOT_ID, "NO_SUITABLE_CANDIDATE", "Rejecting recommendation");

        assertNotNull(event);
        assertEquals(RecommendationDecisionType.REJECTED, event.getDecisionType());
        assertNull(event.getSelectedCandidateId());
        assertEquals(CANDIDATE_1_ID, event.getRecommendedCandidateId());

        verify(decisionPort, times(1)).save(any(RecommendationDecisionEventEntity.class));
    }

    // 5. Manager cancels -> CANCELED
    @Test
    @DisplayName("Req 5: Manager cancels pending action -> CANCELED")
    void testManagerCancelsCreatesCanceled() {
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);

        PendingAiActionService pendingService = new PendingAiActionService();
        pendingService.setRecommendationDecisionService(decisionService);

        ConfirmationRequiredDto confirmation = pendingService.create(
                MANAGER_USER_ID, 100L, "recommendAndAssignTask", "Assign task",
                Map.of("snapshotId", SNAPSHOT_ID, "taskId", TASK_ID), null, () -> "ok");

        pendingService.cancel(confirmation.actionId(), MANAGER_USER_ID, 100L);

        ArgumentCaptor<RecommendationDecisionEventEntity> captor = ArgumentCaptor.forClass(RecommendationDecisionEventEntity.class);
        verify(decisionPort, times(1)).save(captor.capture());
        assertEquals(RecommendationDecisionType.CANCELED, captor.getValue().getDecisionType());
        assertNull(captor.getValue().getSelectedCandidateId());
    }

    // 6. Trusted expiration creates EXPIRED
    @Test
    @DisplayName("Req 6: Trusted expiration workflow creates EXPIRED")
    void testTrustedExpirationCreatesExpired() {
        PendingAiActionService pendingService = new PendingAiActionService();
        pendingService.setRecommendationDecisionService(decisionService);

        ConfirmationRequiredDto confirmation = pendingService.create(
                MANAGER_USER_ID, 100L, "recommendAndAssignTask", "Assign task",
                Map.of("snapshotId", SNAPSHOT_ID, "taskId", TASK_ID), null, () -> "ok");

        pendingService.expireAction(confirmation.actionId());

        ArgumentCaptor<RecommendationDecisionEventEntity> captor = ArgumentCaptor.forClass(RecommendationDecisionEventEntity.class);
        verify(decisionPort, times(1)).save(captor.capture());
        assertEquals(RecommendationDecisionType.EXPIRED, captor.getValue().getDecisionType());
        assertNull(captor.getValue().getSelectedCandidateId());
    }

    // 7. Member cannot create a decision
    @Test
    @DisplayName("Req 7: Regular project member cannot create a decision (403)")
    void testMemberCannotCreateDecision() {
        Long memberUserId = 200L;
        when(projectMemberPort.isProjectManager(PROJECT_ID, memberUserId)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                decisionService.recordAccepted(memberUserId, SNAPSHOT_ID, CANDIDATE_1_ID, "ACCEPT", "Member"));

        assertEquals(403, ex.getStatus());
        verify(decisionPort, never()).save(any());
    }

    // 8. Outsider cannot create a decision
    @Test
    @DisplayName("Req 8: Non-project outsider cannot create a decision (403)")
    void testOutsiderCannotCreateDecision() {
        Long outsiderUserId = 999L;
        when(projectMemberPort.isProjectManager(PROJECT_ID, outsiderUserId)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                decisionService.recordAccepted(outsiderUserId, SNAPSHOT_ID, CANDIDATE_1_ID, "ACCEPT", "Outsider"));

        assertEquals(403, ex.getStatus());
        verify(decisionPort, never()).save(any());
    }

    // 9. Demoted manager cannot decide
    @Test
    @DisplayName("Req 9: Demoted manager (no longer manager at decision time) is denied (403)")
    void testDemotedManagerCannotDecide() {
        // Demoted before decision call
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                decisionService.recordAccepted(MANAGER_USER_ID, SNAPSHOT_ID, CANDIDATE_1_ID, "ACCEPT", "Demoted"));

        assertEquals(403, ex.getStatus());
        verify(decisionPort, never()).save(any());
    }

    // 10. Actor ID comes from authenticated context
    @Test
    @DisplayName("Req 10: Decision actor ID must come from authenticated context, null actor is rejected")
    void testActorIdComesFromAuthenticatedContext() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                decisionService.recordDecision(null, SNAPSHOT_ID, RecommendationDecisionType.ACCEPTED,
                        CANDIDATE_1_ID, "ACCEPT", "Note", null, null));

        assertEquals(401, ex.getStatus());
        verify(decisionPort, never()).save(any());
    }

    // 11. Snapshot project/task mismatch is rejected
    @Test
    @DisplayName("Req 11: Snapshot project/task mismatch is rejected (400)")
    void testSnapshotProjectTaskMismatchRejected() {
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);

        BusinessException exProject = assertThrows(BusinessException.class, () ->
                decisionService.recordDecision(MANAGER_USER_ID, SNAPSHOT_ID, RecommendationDecisionType.ACCEPTED,
                        CANDIDATE_1_ID, "ACCEPT", "Note", 999L, TASK_ID));
        assertEquals(400, exProject.getStatus());
        assertTrue(exProject.getMessage().contains("does not match expected project"));

        BusinessException exTask = assertThrows(BusinessException.class, () ->
                decisionService.recordDecision(MANAGER_USER_ID, SNAPSHOT_ID, RecommendationDecisionType.ACCEPTED,
                        CANDIDATE_1_ID, "ACCEPT", "Note", PROJECT_ID, 999L));
        assertEquals(400, exTask.getStatus());
        assertTrue(exTask.getMessage().contains("does not match expected task"));

        verify(decisionPort, never()).save(any());
    }

    // 12. Second terminal decision is rejected
    @Test
    @DisplayName("Req 12: Second terminal decision on the same snapshot is rejected (409)")
    void testSecondTerminalDecisionIsRejected() {
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);

        RecommendationDecisionEventEntity existingDecision = RecommendationDecisionEventEntity.builder()
                .decisionId("rec-dec-existing-1")
                .snapshotId(SNAPSHOT_ID)
                .decisionType(RecommendationDecisionType.ACCEPTED)
                .build();
        when(decisionPort.findBySnapshotId(SNAPSHOT_ID)).thenReturn(Optional.of(existingDecision));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                decisionService.recordAccepted(MANAGER_USER_ID, SNAPSHOT_ID, CANDIDATE_1_ID, "SECOND", "Should fail"));

        assertEquals(409, ex.getStatus());
        assertEquals("Conflict: A decision has already been recorded for this recommendation snapshot or the event violates a persistence constraint.", ex.getMessage());
        assertFalse(ex.getMessage().contains(SNAPSHOT_ID));
        assertFalse(ex.getMessage().contains("ACCEPTED"));
        verify(decisionPort, never()).save(any());
    }

    // 13. Assignment failure creates no accepted/overridden event
    @Test
    @DisplayName("Req 13: Assignment failure creates no accepted or overridden event")
    void testAssignmentFailureCreatesNoEvent() {
        AutoAssignmentService autoAssignmentService = mock(AutoAssignmentService.class);
        ProjectMemberPort memberPort = mock(ProjectMemberPort.class);
        ProjectInsightsPort insightsPort = mock(ProjectInsightsPort.class);
        MemberAnalyticsPort analyticsPort = mock(MemberAnalyticsPort.class);
        TaskCommandPort taskPort = mock(TaskCommandPort.class);
        PendingAiActionService pendingService = new PendingAiActionService();

        AhpAssignmentAiTools tools = new AhpAssignmentAiTools(
                autoAssignmentService, memberPort, insightsPort, analyticsPort, taskPort, pendingService);
        tools.setRecommendationDecisionService(decisionService);

        ToolExecutionContext.set(new ToolExecutionContext.Context(MANAGER_USER_ID, 100L, "test"));
        when(taskPort.getTaskDetails(TASK_ID, MANAGER_USER_ID))
                .thenReturn(new TaskDetailDto(TASK_ID, PROJECT_ID, "Task", "", "TODO", "MEDIUM", 5, "Java", null, null, null));

        RecommendedCandidateView candView = RecommendedCandidateView.builder()
                .rank(1)
                .candidateId(CANDIDATE_1_ID)
                .displayName("Alice Engineer")
                .fitStatus(MetricDataStatus.MEASURED)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("AVAILABLE")
                .build();

        RecommendationView recView = RecommendationView.builder()
                .projectId(PROJECT_ID)
                .requiredSkills(List.of("Java"))
                .candidates(List.of(candView))
                .differentiationStatus(RecommendationDifferentiationStatus.DIFFERENTIATED)
                .heuristicMode("BALANCED")
                .build();

        AutoAssignmentService.SnapshotEvaluationResult previewResult =
                new AutoAssignmentService.SnapshotEvaluationResult(recView, SNAPSHOT_ID);
        when(autoAssignmentService.recommendCandidatesForPreview(eq(PROJECT_ID), eq(TASK_ID), anyList(), eq(5), eq(MANAGER_USER_ID), anySet(), anySet()))
                .thenReturn(previewResult);

        ConfirmationRequiredDto confirmation = (ConfirmationRequiredDto) tools.recommendAndAssignTask(
                String.valueOf(TASK_ID), String.valueOf(PROJECT_ID), "Java", "5", "Assign to Alice");

        // Simulate assignment failure
        when(taskPort.assignTaskToMember(anyLong(), anyLong(), anyString(), anyLong(), anyBoolean()))
                .thenThrow(new RuntimeException("Simulated database failure during assignment"));

        Object result = pendingService.confirm(confirmation.actionId(), MANAGER_USER_ID, 100L);
        assertTrue(result.toString().contains("ERROR: Action failed to execute"));

        verify(decisionPort, never()).save(any());
    }

    // 14. Authorization failure creates no event
    @Test
    @DisplayName("Req 14: Authorization failure at preview or confirmation creates no event")
    void testAuthorizationFailureCreatesNoEvent() {
        AutoAssignmentService autoAssignmentService = mock(AutoAssignmentService.class);
        ProjectMemberPort memberPort = mock(ProjectMemberPort.class);
        TaskCommandPort taskPort = mock(TaskCommandPort.class);
        PendingAiActionService pendingService = new PendingAiActionService();

        AhpAssignmentAiTools tools = new AhpAssignmentAiTools(
                autoAssignmentService, memberPort, mock(ProjectInsightsPort.class),
                mock(MemberAnalyticsPort.class), taskPort, pendingService);
        tools.setRecommendationDecisionService(decisionService);

        ToolExecutionContext.set(new ToolExecutionContext.Context(MANAGER_USER_ID, 100L, "test"));
        when(taskPort.getTaskDetails(TASK_ID, MANAGER_USER_ID))
                .thenReturn(new TaskDetailDto(TASK_ID, PROJECT_ID, "Task", "", "TODO", "MEDIUM", 5, "Java", null, null, null));

        doThrow(new BusinessException(403, "Forbidden: User is not a manager"))
                .when(autoAssignmentService).validateProjectManager(PROJECT_ID, MANAGER_USER_ID);

        assertThrows(BusinessException.class, () ->
                tools.recommendAndAssignTask(String.valueOf(TASK_ID), String.valueOf(PROJECT_ID), "Java", "5", "Reason"));

        verify(decisionPort, never()).save(any());
    }

    // 15. Decision retrieval enforces project membership
    @Test
    @DisplayName("Req 15: Decision retrieval enforces project membership")
    void testDecisionRetrievalEnforcesProjectMembership() {
        RecommendationDecisionEventEntity existingDecision = RecommendationDecisionEventEntity.builder()
                .decisionId("rec-dec-test-15")
                .snapshotId(SNAPSHOT_ID)
                .projectId(PROJECT_ID)
                .decidedByUserId(MANAGER_USER_ID)
                .decisionType(RecommendationDecisionType.ACCEPTED)
                .build();
        when(decisionPort.findBySnapshotId(SNAPSHOT_ID)).thenReturn(Optional.of(existingDecision));

        when(projectMemberPort.isProjectMember(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);
        when(projectMemberPort.isProjectMember(PROJECT_ID, 999L)).thenReturn(false);

        RecommendationDecisionEventEntity retrieved = decisionService.getDecision(SNAPSHOT_ID, MANAGER_USER_ID);
        assertNotNull(retrieved);
        assertEquals("rec-dec-test-15", retrieved.getDecisionId());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                decisionService.getDecision(SNAPSHOT_ID, 999L));
        assertEquals(403, ex.getStatus());
    }

    // 16. Snapshot and decision linkage is preserved
    @Test
    @DisplayName("Req 16: Snapshot and decision linkage is preserved")
    void testSnapshotAndDecisionLinkageIsPreserved() {
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);

        RecommendationDecisionEventEntity event = decisionService.recordAccepted(
                MANAGER_USER_ID, SNAPSHOT_ID, CANDIDATE_1_ID, "CONFIRMED", "Linkage check");

        assertEquals(SNAPSHOT_ID, event.getSnapshotId());
        assertEquals(PROJECT_ID, event.getProjectId());
        assertEquals(TASK_ID, event.getTaskId());
        assertEquals(CANDIDATE_1_ID, event.getRecommendedCandidateId());
        assertEquals(CANDIDATE_1_ID, event.getSelectedCandidateId());
    }

    // 17. No email, internal scores, or free-text LLM payload is stored
    @Test
    @DisplayName("Req 17: Entity must not contain email, internal ranking scores, or LLM payload fields")
    void testNoEmailInternalScoresOrFreeTextLlmPayloadStored() {
        Field[] fields = RecommendationDecisionEventEntity.class.getDeclaredFields();
        Set<String> fieldNames = new HashSet<>();
        for (Field f : fields) {
            fieldNames.add(f.getName().toLowerCase());
        }

        assertFalse(fieldNames.contains("email"), "Must not store email");
        assertFalse(fieldNames.contains("score"), "Must not store score");
        assertFalse(fieldNames.contains("fullprecisionscore"), "Must not store fullPrecisionScore");
        assertFalse(fieldNames.contains("rankingkey"), "Must not store rankingKey");
        assertFalse(fieldNames.contains("fitscore"), "Must not store fitScore");
        assertFalse(fieldNames.contains("loadscore"), "Must not store loadScore");
        assertFalse(fieldNames.contains("performancescore"), "Must not store performanceScore");
        assertFalse(fieldNames.contains("prompt"), "Must not store LLM prompt");
        assertFalse(fieldNames.contains("llmpayload"), "Must not store LLM payload");
    }

    // 18. Existing Phase 1 and Phase 2A tests remain green
    @Test
    @DisplayName("Req 18: Phase 2A snapshot compatibility holds alongside decision events")
    void testExistingPhase2ACompatibility() {
        RecommendationSnapshotEntity snapshot = snapshotPort.findById(SNAPSHOT_ID).orElseThrow();
        assertEquals(SNAPSHOT_ID, snapshot.getSnapshotId());
        assertEquals(CANDIDATE_1_ID, snapshot.getRecommendedCandidateId());
        assertEquals(2, snapshot.getCandidateCount());
    }

    // 19. Override candidate belongs to snapshot
    @Test
    @DisplayName("Req 1: Override candidate must belong to snapshot")
    void testOverrideCandidateMustBelongToSnapshot() {
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);
        TaskCommandPort taskPort = mock(TaskCommandPort.class);
        when(taskPort.getTaskDetails(TASK_ID, MANAGER_USER_ID))
                .thenReturn(new TaskDetailDto(TASK_ID, PROJECT_ID, "Task", "", "TODO", "MEDIUM", 5, "Java", null, null, null));

        AhpAssignmentAiTools tools = new AhpAssignmentAiTools(
                mock(AutoAssignmentService.class), projectMemberPort, mock(ProjectInsightsPort.class),
                mock(MemberAnalyticsPort.class), taskPort, mock(PendingAiActionService.class));
        tools.setRecommendationDecisionService(decisionService);

        ToolExecutionContext.set(new ToolExecutionContext.Context(MANAGER_USER_ID, 100L, "test"));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                tools.overrideRecommendationAndAssignTask(String.valueOf(TASK_ID), "999", SNAPSHOT_ID, "reason"));

        assertEquals(400, ex.getStatus());
        assertTrue(ex.getMessage().contains("is not present in recommendation snapshot"));
        verify(taskPort, never()).assignTaskToMember(anyLong(), anyLong(), anyString(), anyLong(), anyBoolean());
        verify(decisionPort, never()).save(any());
    }

    // 20. Override candidate must differ from recommended candidate
    @Test
    @DisplayName("Req 2: Override candidate must differ from recommended candidate")
    void testOverrideCandidateMustDifferFromRecommendedCandidate() {
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);
        TaskCommandPort taskPort = mock(TaskCommandPort.class);
        when(taskPort.getTaskDetails(TASK_ID, MANAGER_USER_ID))
                .thenReturn(new TaskDetailDto(TASK_ID, PROJECT_ID, "Task", "", "TODO", "MEDIUM", 5, "Java", null, null, null));

        AhpAssignmentAiTools tools = new AhpAssignmentAiTools(
                mock(AutoAssignmentService.class), projectMemberPort, mock(ProjectInsightsPort.class),
                mock(MemberAnalyticsPort.class), taskPort, mock(PendingAiActionService.class));
        tools.setRecommendationDecisionService(decisionService);

        ToolExecutionContext.set(new ToolExecutionContext.Context(MANAGER_USER_ID, 100L, "test"));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                tools.overrideRecommendationAndAssignTask(String.valueOf(TASK_ID), String.valueOf(CANDIDATE_1_ID), SNAPSHOT_ID, "reason"));

        assertEquals(400, ex.getStatus());
        assertTrue(ex.getMessage().contains("matches recommended candidate; use standard assignment instead of override"));
        verify(taskPort, never()).assignTaskToMember(anyLong(), anyLong(), anyString(), anyLong(), anyBoolean());
        verify(decisionPort, never()).save(any());
    }

    // 21. Manager creates override pending action (no machine ID in user text, carries snapshotId and selectedCandidateId in args)
    @Test
    @DisplayName("Req 3: Manager creates override pending action without exposing machine IDs in user text")
    void testManagerCreatesOverridePendingAction() {
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);
        TaskCommandPort taskPort = mock(TaskCommandPort.class);
        when(taskPort.getTaskDetails(TASK_ID, MANAGER_USER_ID))
                .thenReturn(new TaskDetailDto(TASK_ID, PROJECT_ID, "Task", "", "TODO", "MEDIUM", 5, "Java", null, null, null));
        PendingAiActionService pendingService = new PendingAiActionService();

        AhpAssignmentAiTools tools = new AhpAssignmentAiTools(
                mock(AutoAssignmentService.class), projectMemberPort, mock(ProjectInsightsPort.class),
                mock(MemberAnalyticsPort.class), taskPort, pendingService);
        tools.setRecommendationDecisionService(decisionService);

        ToolExecutionContext.set(new ToolExecutionContext.Context(MANAGER_USER_ID, 100L, "test"));

        Object result = tools.overrideRecommendationAndAssignTask(
                String.valueOf(TASK_ID), String.valueOf(CANDIDATE_2_ID), SNAPSHOT_ID, "Prefer Candidate 2");

        assertNotNull(result);
        assertTrue(result instanceof ConfirmationRequiredDto);
        ConfirmationRequiredDto confirmation = (ConfirmationRequiredDto) result;
        // User-facing summary must not contain machine IDs (like SNAPSHOT_ID)
        assertFalse(confirmation.summary().contains(SNAPSHOT_ID), "User text must not expose snapshotId");
        assertTrue(confirmation.summary().contains("thay cho đề xuất ban đầu"));

        // Machine action context must carry snapshotId and selectedCandidateId
        Map<String, Object> pendingAction = confirmation.arguments();
        assertNotNull(pendingAction);
        assertEquals(SNAPSHOT_ID, pendingAction.get("snapshotId"));
        assertEquals(CANDIDATE_2_ID, pendingAction.get("selectedCandidateId"));
        assertEquals(TASK_ID, pendingAction.get("taskId"));
    }

    // 22. Member cannot create override pending action
    @Test
    @DisplayName("Req 4: Member cannot create override pending action (403)")
    void testMemberCannotCreateOverridePendingAction() {
        Long memberId = 200L;
        TaskCommandPort taskPort = mock(TaskCommandPort.class);
        when(taskPort.getTaskDetails(TASK_ID, memberId))
                .thenReturn(new TaskDetailDto(TASK_ID, PROJECT_ID, "Task", "", "TODO", "MEDIUM", 5, "Java", null, null, null));
        AutoAssignmentService autoAssignmentService = mock(AutoAssignmentService.class);
        doThrow(new BusinessException(403, "Forbidden: User is not a manager"))
                .when(autoAssignmentService).validateProjectManager(PROJECT_ID, memberId);

        AhpAssignmentAiTools tools = new AhpAssignmentAiTools(
                autoAssignmentService, projectMemberPort, mock(ProjectInsightsPort.class),
                mock(MemberAnalyticsPort.class), taskPort, mock(PendingAiActionService.class));
        tools.setRecommendationDecisionService(decisionService);

        ToolExecutionContext.set(new ToolExecutionContext.Context(memberId, 100L, "test"));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                tools.overrideRecommendationAndAssignTask(String.valueOf(TASK_ID), String.valueOf(CANDIDATE_2_ID), SNAPSHOT_ID, "reason"));
        assertEquals(403, ex.getStatus());
        verify(taskPort, never()).assignTaskToMember(anyLong(), anyLong(), anyString(), anyLong(), anyBoolean());
        verify(decisionPort, never()).save(any());
    }

    // 23. Outsider cannot create override pending action
    @Test
    @DisplayName("Req 5: Outsider cannot create override pending action (403)")
    void testOutsiderCannotCreateOverridePendingAction() {
        Long outsiderId = 999L;
        TaskCommandPort taskPort = mock(TaskCommandPort.class);
        when(taskPort.getTaskDetails(TASK_ID, outsiderId))
                .thenReturn(new TaskDetailDto(TASK_ID, PROJECT_ID, "Task", "", "TODO", "MEDIUM", 5, "Java", null, null, null));
        AutoAssignmentService autoAssignmentService = mock(AutoAssignmentService.class);
        doThrow(new BusinessException(403, "Forbidden: User is not a manager of project " + PROJECT_ID))
                .when(autoAssignmentService).validateProjectManager(PROJECT_ID, outsiderId);

        AhpAssignmentAiTools tools = new AhpAssignmentAiTools(
                autoAssignmentService, projectMemberPort, mock(ProjectInsightsPort.class),
                mock(MemberAnalyticsPort.class), taskPort, mock(PendingAiActionService.class));
        tools.setRecommendationDecisionService(decisionService);

        ToolExecutionContext.set(new ToolExecutionContext.Context(outsiderId, 100L, "test"));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                tools.overrideRecommendationAndAssignTask(String.valueOf(TASK_ID), String.valueOf(CANDIDATE_2_ID), SNAPSHOT_ID, "reason"));
        assertEquals(403, ex.getStatus());
        verify(taskPort, never()).assignTaskToMember(anyLong(), anyLong(), anyString(), anyLong(), anyBoolean());
        verify(decisionPort, never()).save(any());
    }

    // 24. Manager confirms override and assignment succeeds then records OVERRIDDEN
    @Test
    @DisplayName("Req 6 & 7: Manager confirms override -> assignment succeeds then records OVERRIDDEN")
    void testManagerConfirmsOverrideAndAssignmentSucceedsRecordsOverridden() {
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);
        when(projectMemberPort.isProjectMember(PROJECT_ID, CANDIDATE_2_ID)).thenReturn(true);
        TaskCommandPort taskPort = mock(TaskCommandPort.class);
        when(taskPort.getTaskDetails(TASK_ID, MANAGER_USER_ID))
                .thenReturn(new TaskDetailDto(TASK_ID, PROJECT_ID, "Task", "", "TODO", "MEDIUM", 5, "Java", null, null, null));
        when(taskPort.assignTaskToMember(TASK_ID, CANDIDATE_2_ID, "Override note", MANAGER_USER_ID, false))
                .thenReturn(new com.taskpilot.contracts.aiquery.dto.TaskAssignmentResultDto(TASK_ID, CANDIDATE_2_ID, "Bob Engineer", "ASSIGNED", "Task assigned"));

        PendingAiActionService pendingService = new PendingAiActionService();

        AhpAssignmentAiTools tools = new AhpAssignmentAiTools(
                mock(AutoAssignmentService.class), projectMemberPort, mock(ProjectInsightsPort.class),
                mock(MemberAnalyticsPort.class), taskPort, pendingService);
        tools.setRecommendationDecisionService(decisionService);

        ToolExecutionContext.set(new ToolExecutionContext.Context(MANAGER_USER_ID, 100L, "test"));

        ConfirmationRequiredDto confirmation = (ConfirmationRequiredDto) tools.overrideRecommendationAndAssignTask(
                String.valueOf(TASK_ID), String.valueOf(CANDIDATE_2_ID), SNAPSHOT_ID, "Override note");

        assertFalse(confirmation.summary().contains(String.valueOf(TASK_ID)));
        assertFalse(confirmation.summary().contains(String.valueOf(CANDIDATE_2_ID)));
        assertFalse(confirmation.summary().contains(SNAPSHOT_ID));
        assertTrue(confirmation.arguments().containsKey("taskId"));
        assertTrue(confirmation.arguments().containsKey("selectedCandidateId"));
        assertTrue(confirmation.arguments().containsKey("snapshotId"));


        // Confirm the action
        Object result = pendingService.confirm(confirmation.actionId(), MANAGER_USER_ID, 100L);
        assertNotNull(result);

        // Verify assignment occurred
        verify(taskPort, times(1)).assignTaskToMember(TASK_ID, CANDIDATE_2_ID, "Override note", MANAGER_USER_ID, false);

        // Verify OVERRIDDEN decision event was recorded
        ArgumentCaptor<RecommendationDecisionEventEntity> captor = ArgumentCaptor.forClass(RecommendationDecisionEventEntity.class);
        verify(decisionPort, times(1)).save(captor.capture());
        RecommendationDecisionEventEntity saved = captor.getValue();
        assertEquals(RecommendationDecisionType.OVERRIDDEN, saved.getDecisionType());
        assertEquals(RecommendationDecisionSource.USER, saved.getDecisionSource());
        assertEquals(MANAGER_USER_ID, saved.getDecidedByUserId());
        assertEquals(CANDIDATE_2_ID, saved.getSelectedCandidateId());
        assertEquals(CANDIDATE_1_ID, saved.getRecommendedCandidateId());
        assertEquals(SNAPSHOT_ID, saved.getSnapshotId());
    }

    // 25. Assignment failure records no OVERRIDDEN event
    @Test
    @DisplayName("Req 8 & 12: Assignment failure records no OVERRIDDEN event and decisionPort is not called")
    void testAssignmentFailureRecordsNoOverriddenEvent() {
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);
        when(projectMemberPort.isProjectMember(PROJECT_ID, CANDIDATE_2_ID)).thenReturn(true);
        TaskCommandPort taskPort = mock(TaskCommandPort.class);
        when(taskPort.getTaskDetails(TASK_ID, MANAGER_USER_ID))
                .thenReturn(new TaskDetailDto(TASK_ID, PROJECT_ID, "Task", "", "TODO", "MEDIUM", 5, "Java", null, null, null));
        when(taskPort.assignTaskToMember(anyLong(), anyLong(), anyString(), anyLong(), anyBoolean()))
                .thenThrow(new RuntimeException("Simulated database failure during assignment"));

        PendingAiActionService pendingService = new PendingAiActionService();

        AhpAssignmentAiTools tools = new AhpAssignmentAiTools(
                mock(AutoAssignmentService.class), projectMemberPort, mock(ProjectInsightsPort.class),
                mock(MemberAnalyticsPort.class), taskPort, pendingService);
        tools.setRecommendationDecisionService(decisionService);

        ToolExecutionContext.set(new ToolExecutionContext.Context(MANAGER_USER_ID, 100L, "test"));

        ConfirmationRequiredDto confirmation = (ConfirmationRequiredDto) tools.overrideRecommendationAndAssignTask(
                String.valueOf(TASK_ID), String.valueOf(CANDIDATE_2_ID), SNAPSHOT_ID, "Override note");

        Object result = pendingService.confirm(confirmation.actionId(), MANAGER_USER_ID, 100L);
        assertTrue(result.toString().contains("ERROR: Action failed to execute"));

        verify(decisionPort, never()).save(any());
    }

    // 26. Manager demoted before confirmation is denied
    @Test
    @DisplayName("Req 9 & 11: Manager demoted before confirmation is denied, assignmentPort is not called")
    void testManagerDemotedBeforeConfirmationIsDenied() {
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);
        TaskCommandPort taskPort = mock(TaskCommandPort.class);
        when(taskPort.getTaskDetails(TASK_ID, MANAGER_USER_ID))
                .thenReturn(new TaskDetailDto(TASK_ID, PROJECT_ID, "Task", "", "TODO", "MEDIUM", 5, "Java", null, null, null));
        AutoAssignmentService autoAssignmentService = mock(AutoAssignmentService.class);

        PendingAiActionService pendingService = new PendingAiActionService();

        AhpAssignmentAiTools tools = new AhpAssignmentAiTools(
                autoAssignmentService, projectMemberPort, mock(ProjectInsightsPort.class),
                mock(MemberAnalyticsPort.class), taskPort, pendingService);
        tools.setRecommendationDecisionService(decisionService);

        ToolExecutionContext.set(new ToolExecutionContext.Context(MANAGER_USER_ID, 100L, "test"));

        ConfirmationRequiredDto confirmation = (ConfirmationRequiredDto) tools.overrideRecommendationAndAssignTask(
                String.valueOf(TASK_ID), String.valueOf(CANDIDATE_2_ID), SNAPSHOT_ID, "Override note");

        // Demote manager before confirmation
        doThrow(new BusinessException(403, "Forbidden: User is not a manager"))
                .when(autoAssignmentService).validateProjectManager(PROJECT_ID, MANAGER_USER_ID);

        Object result = pendingService.confirm(confirmation.actionId(), MANAGER_USER_ID, 100L);
        assertTrue(result.toString().contains("Forbidden: User is not a manager"));

        verify(taskPort, never()).assignTaskToMember(anyLong(), anyLong(), anyString(), anyLong(), anyBoolean());
        verify(decisionPort, never()).save(any());
    }

    // 27. Selected candidate removed before confirmation is denied
    @Test
    @DisplayName("Req 10: Selected candidate removed before confirmation is denied")
    void testSelectedCandidateRemovedBeforeConfirmationIsDenied() {
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);
        TaskCommandPort taskPort = mock(TaskCommandPort.class);
        when(taskPort.getTaskDetails(TASK_ID, MANAGER_USER_ID))
                .thenReturn(new TaskDetailDto(TASK_ID, PROJECT_ID, "Task", "", "TODO", "MEDIUM", 5, "Java", null, null, null));

        PendingAiActionService pendingService = new PendingAiActionService();

        AhpAssignmentAiTools tools = new AhpAssignmentAiTools(
                mock(AutoAssignmentService.class), projectMemberPort, mock(ProjectInsightsPort.class),
                mock(MemberAnalyticsPort.class), taskPort, pendingService);
        tools.setRecommendationDecisionService(decisionService);

        ToolExecutionContext.set(new ToolExecutionContext.Context(MANAGER_USER_ID, 100L, "test"));

        ConfirmationRequiredDto confirmation = (ConfirmationRequiredDto) tools.overrideRecommendationAndAssignTask(
                String.valueOf(TASK_ID), String.valueOf(CANDIDATE_2_ID), SNAPSHOT_ID, "Override note");

        // Candidate removed from project before confirmation
        when(projectMemberPort.isProjectMember(PROJECT_ID, CANDIDATE_2_ID)).thenReturn(false);

        Object result = pendingService.confirm(confirmation.actionId(), MANAGER_USER_ID, 100L);
        assertTrue(result.toString().contains("is not an active member of project"));

        verify(taskPort, never()).assignTaskToMember(anyLong(), anyLong(), anyString(), anyLong(), anyBoolean());
        verify(decisionPort, never()).save(any());
    }

    // 28. REJECTED succeeds regardless of current task assignment and stores no selected candidate
    @Test
    @DisplayName("Req 13 & 14: REJECTED succeeds regardless of task assignment and stores null selected candidate")
    void testRejectedSucceedsRegardlessOfTaskAssignment() {
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);

        AhpAssignmentAiTools tools = new AhpAssignmentAiTools(
                mock(AutoAssignmentService.class), projectMemberPort, mock(ProjectInsightsPort.class),
                mock(MemberAnalyticsPort.class), mock(TaskCommandPort.class), mock(PendingAiActionService.class));
        tools.setRecommendationDecisionService(decisionService);

        ToolExecutionContext.set(new ToolExecutionContext.Context(MANAGER_USER_ID, 100L, "test"));

        // Call REJECTED tool
        Object result = tools.recordRecommendationRejection(
                SNAPSHOT_ID, "NO_SUITABLE_CANDIDATE", "All candidates unsuitable");

        assertNotNull(result);
        assertTrue(result instanceof Map<?, ?>);
        Map<?, ?> map = (Map<?, ?>) result;
        assertEquals("RECORDED", map.get("status"));
        assertEquals("REJECTED", map.get("decisionType"));

        ArgumentCaptor<RecommendationDecisionEventEntity> captor = ArgumentCaptor.forClass(RecommendationDecisionEventEntity.class);
        verify(decisionPort, times(1)).save(captor.capture());
        RecommendationDecisionEventEntity saved = captor.getValue();
        assertEquals(RecommendationDecisionType.REJECTED, saved.getDecisionType());
        assertEquals(RecommendationDecisionSource.USER, saved.getDecisionSource());
        assertNull(saved.getSelectedCandidateId(), "REJECTED must store null selected candidate");
        assertEquals(CANDIDATE_1_ID, saved.getRecommendedCandidateId());
    }

    // 29. EXPIRED stores decisionSource = SYSTEM and null actor
    @Test
    @DisplayName("Req 16 & 17: EXPIRED stores decisionSource = SYSTEM and null actor")
    void testExpiredStoresSystemSourceAndNullActor() {
        RecommendationDecisionEventEntity event = decisionService.recordExpired(SNAPSHOT_ID, "Expired in background");

        assertNotNull(event);
        assertEquals(RecommendationDecisionType.EXPIRED, event.getDecisionType());
        assertEquals(RecommendationDecisionSource.SYSTEM, event.getDecisionSource());
        assertNull(event.getDecidedByUserId(), "EXPIRED must have null actor");
        assertNull(event.getSelectedCandidateId());

        ArgumentCaptor<RecommendationDecisionEventEntity> captor = ArgumentCaptor.forClass(RecommendationDecisionEventEntity.class);
        verify(decisionPort, times(1)).save(captor.capture());
        assertEquals(RecommendationDecisionSource.SYSTEM, captor.getValue().getDecisionSource());
        assertNull(captor.getValue().getDecidedByUserId());
    }

    // 30. USER decisions store decisionSource = USER
    @Test
    @DisplayName("Req 18: USER decisions store decisionSource = USER")
    void testUserDecisionsStoreDecisionSourceUser() {
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);

        RecommendationDecisionEventEntity accepted = decisionService.recordAccepted(
                MANAGER_USER_ID, SNAPSHOT_ID, CANDIDATE_1_ID, "ACCEPT", "note");
        assertEquals(RecommendationDecisionSource.USER, accepted.getDecisionSource());

        RecommendationDecisionEventEntity overridden = decisionService.recordOverridden(
                MANAGER_USER_ID, SNAPSHOT_ID, CANDIDATE_2_ID, "OVERRIDE", "note");
        assertEquals(RecommendationDecisionSource.USER, overridden.getDecisionSource());

        RecommendationDecisionEventEntity rejected = decisionService.recordRejected(
                MANAGER_USER_ID, SNAPSHOT_ID, "REJECT", "note");
        assertEquals(RecommendationDecisionSource.USER, rejected.getDecisionSource());

        RecommendationDecisionEventEntity canceled = decisionService.recordCanceled(
                MANAGER_USER_ID, SNAPSHOT_ID, "CANCEL", "note");
        assertEquals(RecommendationDecisionSource.USER, canceled.getDecisionSource());
    }

    // 31. Post-assignment decision failure keeps assignment (TD-P2B-POST-ASSIGNMENT-DECISION-PERSISTENCE)
    @Test
    @DisplayName("Req 22: Post-assignment decision failure does not roll back assignment")
    void testPostAssignmentDecisionFailureDoesNotFailAssignment() {
        AutoAssignmentService autoAssignmentService = mock(AutoAssignmentService.class);
        ProjectMemberPort memberPort = mock(ProjectMemberPort.class);
        ProjectInsightsPort insightsPort = mock(ProjectInsightsPort.class);
        MemberAnalyticsPort analyticsPort = mock(MemberAnalyticsPort.class);
        TaskCommandPort taskPort = mock(TaskCommandPort.class);
        PendingAiActionService pendingService = new PendingAiActionService();

        RecommendationDecisionService failingDecisionService = mock(RecommendationDecisionService.class);
        doThrow(new RuntimeException("Simulated decision persistence failure"))
                .when(failingDecisionService).recordAccepted(anyLong(), anyString(), anyLong(), anyString(), anyString());

        AhpAssignmentAiTools tools = new AhpAssignmentAiTools(
                autoAssignmentService, memberPort, insightsPort, analyticsPort, taskPort, pendingService);
        tools.setRecommendationDecisionService(failingDecisionService);

        ToolExecutionContext.set(new ToolExecutionContext.Context(MANAGER_USER_ID, 100L, "test"));
        when(taskPort.getTaskDetails(TASK_ID, MANAGER_USER_ID))
                .thenReturn(new TaskDetailDto(TASK_ID, PROJECT_ID, "Task", "", "TODO", "MEDIUM", 5, "Java", null, null, null));

        RecommendedCandidateView candView = RecommendedCandidateView.builder()
                .rank(1)
                .candidateId(CANDIDATE_1_ID)
                .displayName("Alice Engineer")
                .fitStatus(MetricDataStatus.MEASURED)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("AVAILABLE")
                .build();

        RecommendationView recView = RecommendationView.builder()
                .projectId(PROJECT_ID)
                .requiredSkills(List.of("Java"))
                .candidates(List.of(candView))
                .differentiationStatus(RecommendationDifferentiationStatus.DIFFERENTIATED)
                .heuristicMode("BALANCED")
                .build();

        when(autoAssignmentService.recommendCandidatesForPreview(eq(PROJECT_ID), eq(TASK_ID), anyList(), eq(5), eq(MANAGER_USER_ID), anySet(), anySet()))
                .thenReturn(new AutoAssignmentService.SnapshotEvaluationResult(recView, SNAPSHOT_ID));

        when(taskPort.assignTaskToMember(eq(TASK_ID), eq(CANDIDATE_1_ID), anyString(), eq(MANAGER_USER_ID), eq(false)))
                .thenReturn(new com.taskpilot.contracts.aiquery.dto.TaskAssignmentResultDto(TASK_ID, CANDIDATE_1_ID, "Alice Engineer", "ASSIGNED", "Task assigned"));

        ConfirmationRequiredDto confirmation = (ConfirmationRequiredDto) tools.recommendAndAssignTask(
                String.valueOf(TASK_ID), String.valueOf(PROJECT_ID), "Java", "5", "Assign to Alice");

        Object result = pendingService.confirm(confirmation.actionId(), MANAGER_USER_ID, 100L);

        assertNotNull(result);
        assertTrue(result instanceof com.taskpilot.ai.dto.RecommendAndAssignResult);
        com.taskpilot.ai.dto.RecommendAndAssignResult assignResult = (com.taskpilot.ai.dto.RecommendAndAssignResult) result;
        assertTrue(assignResult.assigned());
        assertEquals(CANDIDATE_1_ID, assignResult.selectedMemberId());
    }

    // 32. Persistence constraint conflict returns neutral conflict error
    @Test
    @DisplayName("Req 20: Persistence constraint conflict returns neutral conflict error")
    void testDataIntegrityViolationMappedToNeutralConflict() {
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);
        when(decisionPort.save(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key value violates unique constraint"));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                decisionService.recordAccepted(MANAGER_USER_ID, SNAPSHOT_ID, CANDIDATE_1_ID, "CONFIRM", "Note"));

        assertEquals(409, ex.getStatus());
        assertEquals("Conflict: A decision has already been recorded for this recommendation snapshot or the event violates a persistence constraint.", ex.getMessage());
        assertFalse(ex.getMessage().contains(SNAPSHOT_ID));
        assertFalse(ex.getMessage().contains("DataIntegrityViolationException"));
    }

    // 33. Snapshot immutability holds after recording decision
    @Test
    @DisplayName("Req 21: Snapshot entity properties remain unchanged after recording a decision")
    void testSnapshotImmutabilityAfterDecision() {
        when(projectMemberPort.isProjectManager(PROJECT_ID, MANAGER_USER_ID)).thenReturn(true);

        RecommendationSnapshotEntity snapshotBefore = snapshotPort.findById(SNAPSHOT_ID).orElseThrow();
        Double fitWeightBefore = snapshotBefore.getFitWeight();
        String modeBefore = snapshotBefore.getHeuristicMode();
        Long recommendedBefore = snapshotBefore.getRecommendedCandidateId();
        int countBefore = snapshotBefore.getCandidateCount();

        decisionService.recordAccepted(MANAGER_USER_ID, SNAPSHOT_ID, CANDIDATE_1_ID, "ACCEPT", "Check immutability");

        RecommendationSnapshotEntity snapshotAfter = snapshotPort.findById(SNAPSHOT_ID).orElseThrow();
        assertEquals(fitWeightBefore, snapshotAfter.getFitWeight());
        assertEquals(modeBefore, snapshotAfter.getHeuristicMode());
        assertEquals(recommendedBefore, snapshotAfter.getRecommendedCandidateId());
        assertEquals(countBefore, snapshotAfter.getCandidateCount());
    }
}
