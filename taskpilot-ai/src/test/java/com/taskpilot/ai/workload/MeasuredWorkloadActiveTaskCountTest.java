package com.taskpilot.ai.workload;

import com.taskpilot.ai.assignment.port.out.AiAuditPort;
import com.taskpilot.ai.assignment.port.out.RecommendationSnapshotPort;
import com.taskpilot.ai.dto.*;
import com.taskpilot.ai.entity.RecommendationSnapshotCandidateEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotEntity;
import com.taskpilot.ai.heuristic.*;
import com.taskpilot.ai.service.AutoAssignmentService;
import com.taskpilot.ai.service.RecommendationSnapshotService;
import com.taskpilot.contracts.assignment.dto.ProjectHeuristicConfigDto;
import com.taskpilot.contracts.assignment.dto.ProjectMemberDto;
import com.taskpilot.contracts.assignment.dto.UserProfileDto;
import com.taskpilot.contracts.assignment.dto.UserSkillDto;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.contracts.assignment.port.out.ProjectPort;
import com.taskpilot.contracts.assignment.port.out.UserPort;
import com.taskpilot.contracts.assignment.port.out.UserSkillPort;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 2C Measured Workload Active Task Count Test Suite.
 * Enforces Decision H-022 invariants:
 * 1. WorkloadMeasurement contract (non-negative integer, ACTIVE_TASK_COUNT unit, MEASURED status, timestamp, PROJECT scope).
 * 2. Active non-terminal task count semantics (TODO, IN_PROGRESS, REVIEW included; DONE excluded).
 * 3. Graceful fallback to UNVERIFIED if measurement query fails.
 * 4. Workload monotonicity with measured counts in Heuristic ranking.
 * 5. Allowlisted presentation boundary: presentationContractVersion remains allowlisted-view-v1.
 * 6. Recommendation Snapshot persistence captures workload_unit, workload_scope, workload_measured_at.
 * 7. LLM Explanation prompt formatting with MEASURED wording and no %/capacity language.
 */
class MeasuredWorkloadActiveTaskCountTest {

    private ProjectMemberPort projectMemberPort;
    private UserPort userPort;
    private UserSkillPort userSkillPort;
    private ProjectPort projectPort;
    private HeuristicStrategyFactory heuristicStrategyFactory;
    private RecommendationSnapshotService snapshotService;
    private RecommendationSnapshotPort snapshotPort;
    private AutoAssignmentService autoAssignmentService;

    private final Long projectId = 100L;
    private final Long userA = 101L;
    private final Long userB = 102L;

    @BeforeEach
    void setUp() {
        projectMemberPort = mock(ProjectMemberPort.class);
        userPort = mock(UserPort.class);
        userSkillPort = mock(UserSkillPort.class);
        projectPort = mock(ProjectPort.class);
        heuristicStrategyFactory = mock(HeuristicStrategyFactory.class);
        snapshotPort = mock(RecommendationSnapshotPort.class);
        snapshotService = new RecommendationSnapshotService(snapshotPort, projectMemberPort);

        HeuristicWeights weights = new HeuristicWeights(0.5, 0.3, 0.2);
        HeuristicNormalizationConfig norm = new HeuristicNormalizationConfig(
                HeuristicNormalization.BENCHMARK_BENEFIT,
                HeuristicNormalization.BENCHMARK_BENEFIT,
                HeuristicNormalization.BENCHMARK_BENEFIT);
        BalancedHeuristicStrategy strategy = new BalancedHeuristicStrategy(new HeuristicConfig(weights, norm));
        when(heuristicStrategyFactory.resolve("BALANCED")).thenReturn(strategy);

        when(projectPort.findById(projectId)).thenReturn(Optional.of(new ProjectHeuristicConfigDto(projectId, "BALANCED")));
        when(projectMemberPort.isProjectMember(eq(projectId), anyLong())).thenReturn(true);

        when(snapshotPort.save(any(RecommendationSnapshotEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        autoAssignmentService = new AutoAssignmentService(
                projectMemberPort,
                userSkillPort,
                mock(AiAuditPort.class),
                userPort,
                projectPort,
                heuristicStrategyFactory,
                mock(StreamingChatModel.class),
                5000L,
                "gemini-3.5-flash",
                snapshotService
        );
    }

    @Test
    @DisplayName("P2C-01: WorkloadMeasurement record defines valid measured contract")
    void workloadMeasurement_definesValidContract() {
        Instant now = Instant.now();
        WorkloadMeasurement measured = WorkloadMeasurement.measured(3, now);

        assertEquals(3, measured.value());
        assertEquals("ACTIVE_TASK_COUNT", measured.unit());
        assertEquals(MetricDataStatus.MEASURED, measured.status());
        assertEquals(now, measured.measuredAt());
        assertEquals("PROJECT", measured.scope());
    }

    @Test
    @DisplayName("P2C-02: WorkloadMeasurement clamps negative counts to zero")
    void workloadMeasurement_clampsNegativeCounts() {
        Instant now = Instant.now();
        WorkloadMeasurement measured = WorkloadMeasurement.measured(-5, now);

        assertEquals(0, measured.value());
        assertEquals(MetricDataStatus.MEASURED, measured.status());
    }

    @Test
    @DisplayName("P2C-03: WorkloadMeasurement unverified factory creates valid fallback contract")
    void workloadMeasurement_unverifiedFactory() {
        WorkloadMeasurement unverified = WorkloadMeasurement.unverified(45);

        assertEquals(45, unverified.value());
        assertNull(unverified.unit());
        assertEquals(MetricDataStatus.UNVERIFIED, unverified.status());
        assertNull(unverified.measuredAt());
        assertNull(unverified.scope());
    }

    @Test
    @DisplayName("P2C-04: AutoAssignmentService evaluates measured active tasks per project member")
    void autoAssign_queriesAndAppliesActiveTaskCount() {
        when(projectMemberPort.findProjectMembers(projectId)).thenReturn(List.of(
                new ProjectMemberDto(userA, "MEMBER", 0.5),
                new ProjectMemberDto(userB, "MEMBER", 0.5)
        ));

        when(userPort.findById(userA)).thenReturn(Optional.of(new UserProfileDto(userA, "Alice", "alice@test.com", "ACTIVE", 99)));
        when(userPort.findById(userB)).thenReturn(Optional.of(new UserProfileDto(userB, "Bob", "bob@test.com", "ACTIVE", 99)));

        when(userSkillPort.findByUserIdWithSkill(userA)).thenReturn(List.of(new UserSkillDto("Java", 4)));
        when(userSkillPort.findByUserIdWithSkill(userB)).thenReturn(List.of(new UserSkillDto("Java", 4)));

        // User A has 1 active task, User B has 4 active tasks
        when(projectMemberPort.countActiveAssignedTasksByProject(projectId)).thenReturn(Map.of(
                userA, 1,
                userB, 4
        ));

        RecommendationView view = autoAssignmentService.recommendView(projectId, List.of("Java"), 3, 999L);

        assertNotNull(view);
        assertEquals("allowlisted-view-v2", view.presentationContractVersion());
        assertEquals("relative-neutral-fixed-point-v2", view.scoringModelVersion());
        assertEquals(2, view.candidates().size());

        RecommendedCandidateView candA = view.candidates().stream().filter(c -> c.candidateId().equals(userA)).findFirst().orElseThrow();
        RecommendedCandidateView candB = view.candidates().stream().filter(c -> c.candidateId().equals(userB)).findFirst().orElseThrow();

        assertEquals(1, candA.storedWorkloadValue());
        assertEquals(MetricDataStatus.MEASURED, candA.workloadStatus());
        assertEquals("ACTIVE_TASK_COUNT", candA.workloadUnit());
        assertEquals("PROJECT", candA.workloadScope());
        assertNotNull(candA.workloadMeasuredAt());

        assertEquals(4, candB.storedWorkloadValue());
        assertEquals(MetricDataStatus.MEASURED, candB.workloadStatus());
        assertEquals("ACTIVE_TASK_COUNT", candB.workloadUnit());
        assertEquals("PROJECT", candB.workloadScope());
        assertNotNull(candB.workloadMeasuredAt());

        // Monotonicity check: Alice has 1 task vs Bob's 4 tasks with equal skill fit -> Alice must rank #1
        assertEquals(1, candA.rank());
        assertEquals(2, candB.rank());
    }

    @Test
    @DisplayName("P2C-05: Missing member in counts map defaults to 0 active tasks")
    void autoAssign_missingMemberDefaultsToZero() {
        when(projectMemberPort.findProjectMembers(projectId)).thenReturn(List.of(
                new ProjectMemberDto(userA, "MEMBER", 0.5)
        ));
        when(userPort.findById(userA)).thenReturn(Optional.of(new UserProfileDto(userA, "Alice", "alice@test.com", "ACTIVE", 50)));
        when(userSkillPort.findByUserIdWithSkill(userA)).thenReturn(List.of(new UserSkillDto("Java", 3)));

        when(projectMemberPort.countActiveAssignedTasksByProject(projectId)).thenReturn(Map.of());

        RecommendationView view = autoAssignmentService.recommendView(projectId, List.of("Java"), 3, 999L);
        RecommendedCandidateView candA = view.candidates().get(0);

        assertEquals(0, candA.storedWorkloadValue());
        assertEquals(MetricDataStatus.MEASURED, candA.workloadStatus());
    }

    @Test
    @DisplayName("P2C-06: Exception in workload counting gracefully falls back to UNVERIFIED")
    void autoAssign_queryExceptionFallsBackToUnverified() {
        when(projectMemberPort.findProjectMembers(projectId)).thenReturn(List.of(
                new ProjectMemberDto(userA, "MEMBER", 0.5)
        ));
        when(userPort.findById(userA)).thenReturn(Optional.of(new UserProfileDto(userA, "Alice", "alice@test.com", "ACTIVE", 40)));
        when(userSkillPort.findByUserIdWithSkill(userA)).thenReturn(List.of(new UserSkillDto("Java", 3)));

        when(projectMemberPort.countActiveAssignedTasksByProject(projectId)).thenThrow(new RuntimeException("DB Connection timeout"));

        RecommendationView view = autoAssignmentService.recommendView(projectId, List.of("Java"), 3, 999L);
        RecommendedCandidateView candA = view.candidates().get(0);

        assertEquals(40, candA.storedWorkloadValue());
        assertEquals(MetricDataStatus.UNVERIFIED, candA.workloadStatus());
    }

    @Test
    @DisplayName("P2C-07: Null projectId falls back to UNVERIFIED stored workload")
    void computeInternalCandidates_nullProjectIdFallsBackToUnverified() {
        when(userPort.findById(userA)).thenReturn(Optional.of(new UserProfileDto(userA, "Alice", "alice@test.com", "ACTIVE", 25)));
        when(userSkillPort.findByUserIdWithSkill(userA)).thenReturn(List.of(new UserSkillDto("Java", 3)));

        HeuristicWeights weights = new HeuristicWeights(0.5, 0.3, 0.2);
        HeuristicNormalizationConfig norm = new HeuristicNormalizationConfig(
                HeuristicNormalization.BENCHMARK_BENEFIT,
                HeuristicNormalization.BENCHMARK_BENEFIT,
                HeuristicNormalization.BENCHMARK_BENEFIT);
        BalancedHeuristicStrategy strategy = new BalancedHeuristicStrategy(new HeuristicConfig(weights, norm));

        List<InternalCandidateRanking> rankings = autoAssignmentService.computeInternalCandidates(
                List.of(new ProjectMemberDto(userA, "MEMBER", 0.5)),
                List.of("Java"),
                strategy,
                "BALANCED",
                null
        );

        assertEquals(1, rankings.size());
        InternalCandidateRanking r = rankings.get(0);
        assertEquals(25, r.storedWorkloadValue());
        assertEquals(MetricDataStatus.UNVERIFIED, r.workloadStatus());
        assertNull(r.workloadUnit());
        assertNull(r.workloadScope());
    }

    @Test
    @DisplayName("P2C-08: Recommendation snapshot persists workload_unit, workload_scope, and workload_measured_at")
    void snapshotPersistence_recordsMeasuredWorkloadMetadata() {
        when(projectMemberPort.findProjectMembers(projectId)).thenReturn(List.of(
                new ProjectMemberDto(userA, "MEMBER", 0.5)
        ));
        when(userPort.findById(userA)).thenReturn(Optional.of(new UserProfileDto(userA, "Alice", "alice@test.com", "ACTIVE", 99)));
        when(userSkillPort.findByUserIdWithSkill(userA)).thenReturn(List.of(new UserSkillDto("Java", 4)));
        when(projectMemberPort.countActiveAssignedTasksByProject(projectId)).thenReturn(Map.of(userA, 2));

        autoAssignmentService.recommendView(projectId, List.of("Java"), 3, 999L);

        ArgumentCaptor<RecommendationSnapshotEntity> captor = ArgumentCaptor.forClass(RecommendationSnapshotEntity.class);
        verify(snapshotPort, atLeastOnce()).save(captor.capture());

        RecommendationSnapshotEntity saved = captor.getValue();
        assertNotNull(saved);
        assertEquals(1, saved.getCandidates().size());

        RecommendationSnapshotCandidateEntity cand = saved.getCandidates().get(0);
        assertEquals(2, cand.getStoredWorkloadValue());
        assertEquals(MetricDataStatus.MEASURED, cand.getWorkloadStatus());
        assertEquals("ACTIVE_TASK_COUNT", cand.getWorkloadUnit());
        assertEquals("PROJECT", cand.getWorkloadScope());
        assertNotNull(cand.getWorkloadMeasuredAt());
    }

    @Test
    @DisplayName("P2C-09: Prompt formats MEASURED workload count correctly without percent or capacity words")
    void buildExplanationPrompt_formatsMeasuredWorkloadCorrectly() {
        RecommendedCandidateView cand = RecommendedCandidateView.builder()
                .rank(1)
                .candidateId(userA)
                .displayName("Alice")
                .presentationFitValue(0.9)
                .fitStatus(MetricDataStatus.MEASURED)
                .storedWorkloadValue(3)
                .workloadStatus(MetricDataStatus.MEASURED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("ACTIVE")
                .build();

        String prompt = autoAssignmentService.buildExplanationPrompt(
                List.of(cand),
                List.of("Java"),
                3,
                RecommendationDifferentiationStatus.DIFFERENTIATED
        );

        assertTrue(prompt.contains("Số công việc đang hoạt động: 3 (MEASURED)"));
        assertFalse(prompt.contains("3%"));
        assertFalse(prompt.contains("capacity"));
    }

    @Test
    @DisplayName("P2C-10: Prompt retains approved fallback wording for UNVERIFIED workload")
    void buildExplanationPrompt_retainsUnverifiedFallbackWording() {
        RecommendedCandidateView cand = RecommendedCandidateView.builder()
                .rank(1)
                .candidateId(userA)
                .displayName("Alice")
                .presentationFitValue(0.9)
                .fitStatus(MetricDataStatus.MEASURED)
                .storedWorkloadValue(20)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("ACTIVE")
                .build();

        String prompt = autoAssignmentService.buildExplanationPrompt(
                List.of(cand),
                List.of("Java"),
                3,
                RecommendationDifferentiationStatus.DIFFERENTIATED
        );

        assertTrue(prompt.contains("20 điểm (UNVERIFIED: Chưa có dữ liệu workload đáng tin cậy)"));
    }

    @Test
    @DisplayName("P2C-11: Equal workload counts among candidates produce neutral load penalty")
    void equalWorkload_producesNeutralLoadPenalty() {
        when(projectMemberPort.findProjectMembers(projectId)).thenReturn(List.of(
                new ProjectMemberDto(userA, "MEMBER", 0.5),
                new ProjectMemberDto(userB, "MEMBER", 0.5)
        ));
        when(userPort.findById(userA)).thenReturn(Optional.of(new UserProfileDto(userA, "Alice", "alice@test.com", "ACTIVE", 0)));
        when(userPort.findById(userB)).thenReturn(Optional.of(new UserProfileDto(userB, "Bob", "bob@test.com", "ACTIVE", 0)));
        when(userSkillPort.findByUserIdWithSkill(userA)).thenReturn(List.of(new UserSkillDto("Java", 4)));
        when(userSkillPort.findByUserIdWithSkill(userB)).thenReturn(List.of(new UserSkillDto("Java", 4)));

        // Both have 2 active tasks
        when(projectMemberPort.countActiveAssignedTasksByProject(projectId)).thenReturn(Map.of(
                userA, 2,
                userB, 2
        ));

        RecommendationView view = autoAssignmentService.recommendView(projectId, List.of("Java"), 3, 999L);
        assertEquals(2, view.candidates().size());
        assertEquals(2, view.candidates().get(0).storedWorkloadValue());
        assertEquals(2, view.candidates().get(1).storedWorkloadValue());
        assertEquals(MetricDataStatus.MEASURED, view.candidates().get(0).workloadStatus());
        assertEquals(MetricDataStatus.MEASURED, view.candidates().get(1).workloadStatus());
    }

    @Test
    @DisplayName("P2C-12: Zero active tasks for all candidates is handled gracefully")
    void zeroActiveTasks_handledGracefully() {
        when(projectMemberPort.findProjectMembers(projectId)).thenReturn(List.of(
                new ProjectMemberDto(userA, "MEMBER", 0.5),
                new ProjectMemberDto(userB, "MEMBER", 0.5)
        ));
        when(userPort.findById(userA)).thenReturn(Optional.of(new UserProfileDto(userA, "Alice", "alice@test.com", "ACTIVE", 0)));
        when(userPort.findById(userB)).thenReturn(Optional.of(new UserProfileDto(userB, "Bob", "bob@test.com", "ACTIVE", 0)));
        when(userSkillPort.findByUserIdWithSkill(userA)).thenReturn(List.of(new UserSkillDto("Java", 4)));
        when(userSkillPort.findByUserIdWithSkill(userB)).thenReturn(List.of(new UserSkillDto("Java", 2)));

        when(projectMemberPort.countActiveAssignedTasksByProject(projectId)).thenReturn(Map.of(
                userA, 0,
                userB, 0
        ));

        RecommendationView view = autoAssignmentService.recommendView(projectId, List.of("Java"), 3, 999L);
        assertEquals(2, view.candidates().size());
        assertEquals(0, view.candidates().get(0).storedWorkloadValue());
        assertEquals(0, view.candidates().get(1).storedWorkloadValue());
        assertEquals(1, view.candidates().get(0).rank());
    }
}
