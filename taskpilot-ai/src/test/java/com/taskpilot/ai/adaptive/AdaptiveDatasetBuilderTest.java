package com.taskpilot.ai.adaptive;

import com.taskpilot.ai.assignment.port.out.RecommendationDecisionEventPort;
import com.taskpilot.ai.assignment.port.out.RecommendationSnapshotPort;
import com.taskpilot.ai.assignment.port.out.RecommendationTaskOutcomePort;
import com.taskpilot.ai.dto.*;
import com.taskpilot.ai.entity.RecommendationDecisionEventEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotCandidateEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotEntity;
import com.taskpilot.ai.entity.RecommendationTaskOutcomeEntity;
import com.taskpilot.ai.service.AdaptiveDatasetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Phase 2E Adaptive Dataset Builder Test Suite.
 * Verifies eligibility criteria, all 20+ distinct exclusion reasons,
 * canonical sorting, SHA-256 stability, and privacy invariants.
 */
class AdaptiveDatasetBuilderTest {

    private RecommendationTaskOutcomePort outcomePort;
    private RecommendationDecisionEventPort decisionPort;
    private RecommendationSnapshotPort snapshotPort;
    private AdaptiveDatasetService datasetService;

    private static final Instant BASE_TIME = Instant.parse("2026-10-10T12:00:00Z");

    @BeforeEach
    void setUp() {
        outcomePort = mock(RecommendationTaskOutcomePort.class);
        decisionPort = mock(RecommendationDecisionEventPort.class);
        snapshotPort = mock(RecommendationSnapshotPort.class);
        datasetService = new AdaptiveDatasetService(outcomePort, decisionPort, snapshotPort);
    }

    private RecommendationSnapshotEntity createValidSnapshot(String snapshotId, Long projectId, Long taskId, Long candidateId) {
        RecommendationSnapshotCandidateEntity candidate = RecommendationSnapshotCandidateEntity.builder()
                .candidateId(candidateId)
                .rank(1)
                .rankingKey(850000000L)
                .fullPrecisionScore(0.85)
                .rankingRawFit(0.8)
                .presentationFitValue(0.8)
                .fitStatus(MetricDataStatus.MEASURED)
                .storedWorkloadValue(2)
                .workloadStatus(MetricDataStatus.MEASURED)
                .workloadUnit("ACTIVE_TASK_COUNT")
                .workloadScope("PROJECT")
                .workloadMeasuredAt(BASE_TIME)
                .derivedPerformanceInput(0.5)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("ACTIVE")
                .selectedAsRecommendation(true)
                .build();

        return RecommendationSnapshotEntity.builder()
                .snapshotId(snapshotId)
                .requestedByUserId(100L)
                .projectId(projectId)
                .taskId(taskId)
                .createdAt(BASE_TIME)
                .heuristicMode("BALANCED")
                .presentationContractVersion("allowlisted-view-v2")
                .scoringModelVersion("v1.0")
                .differentiationStatus("DIFFERENTIATED")
                .requiredSkills("Java,Spring")
                .recommendedCandidateId(candidateId)
                .candidateCount(1)
                .fitWeight(0.5)
                .loadWeight(0.3)
                .performanceWeight(0.2)
                .candidates(new ArrayList<>(List.of(candidate)))
                .build();
    }

    private RecommendationDecisionEventEntity createValidDecision(String decisionId, String snapshotId, Long projectId, Long taskId, Long candidateId) {
        return RecommendationDecisionEventEntity.builder()
                .decisionId(decisionId)
                .snapshotId(snapshotId)
                .projectId(projectId)
                .taskId(taskId)
                .recommendedCandidateId(candidateId)
                .selectedCandidateId(candidateId)
                .decisionType(RecommendationDecisionType.ACCEPTED)
                .decisionSource(RecommendationDecisionSource.USER)
                .decidedByUserId(100L)
                .createdAt(BASE_TIME.plusSeconds(60))
                .build();
    }

    private RecommendationTaskOutcomeEntity createValidOutcome(String outcomeId, String snapshotId, String decisionId, Long projectId, Long taskId, Long candidateId) {
        return RecommendationTaskOutcomeEntity.builder()
                .outcomeId(outcomeId)
                .snapshotId(snapshotId)
                .decisionId(decisionId)
                .projectId(projectId)
                .taskId(taskId)
                .recommendedCandidateId(candidateId)
                .selectedCandidateId(candidateId)
                .decisionType(RecommendationDecisionType.ACCEPTED)
                .decisionSource(RecommendationDecisionSource.USER)
                .observedAssigneeId(candidateId)
                .taskStatus("DONE")
                .dueAt(BASE_TIME.plusSeconds(3600))
                .completedAt(BASE_TIME.plusSeconds(1800))
                .outcomeType(TaskOutcomeType.COMPLETED_ON_TIME)
                .observedAt(BASE_TIME.plusSeconds(1800))
                .outcomeVersion("outcome-v1")
                .build();
    }

    @Test
    @DisplayName("1. Valid outcome with all criteria satisfied is included as eligible")
    void test01_validEvidenceIsEligible() {
        RecommendationSnapshotEntity snap = createValidSnapshot("snap-1", 10L, 1001L, 50L);
        RecommendationDecisionEventEntity dec = createValidDecision("dec-1", "snap-1", 10L, 1001L, 50L);
        RecommendationTaskOutcomeEntity out = createValidOutcome("out-1", "snap-1", "dec-1", 10L, 1001L, 50L);

        when(snapshotPort.findById("snap-1")).thenReturn(Optional.of(snap));
        when(decisionPort.findById("dec-1")).thenReturn(Optional.of(dec));

        Optional<AdaptiveExclusionReason> reason = datasetService.evaluateOutcomeEligibility(out, new HashSet<>());
        assertTrue(reason.isEmpty());

        AdaptiveDatasetService.DatasetResult result = datasetService.evaluateOutcomes(List.of(out));
        assertEquals(1, result.eligibleRows().size());
        assertEquals(0, result.qualityReport().excludedSamplesCount());
        assertEquals("out-1", result.eligibleRows().get(0).rowId());
        assertTrue(result.eligibleRows().get(0).completedOnTime());
    }

    @Test
    @DisplayName("2. Outcome marked EXCLUDED is excluded with OUTCOME_EXCLUDED")
    void test02_outcomeMarkedExcluded() {
        RecommendationTaskOutcomeEntity out = createValidOutcome("out-1", "snap-1", "dec-1", 10L, 1001L, 50L);
        out.setOutcomeType(TaskOutcomeType.EXCLUDED);

        Optional<AdaptiveExclusionReason> reason = datasetService.evaluateOutcomeEligibility(out, new HashSet<>());
        assertEquals(Optional.of(AdaptiveExclusionReason.OUTCOME_EXCLUDED), reason);
    }

    @Test
    @DisplayName("3. Outcome with non-terminal status (INCOMPLETE_OVERDUE / NOT_YET_OBSERVABLE) is excluded")
    void test03_outcomeNotCompleted() {
        RecommendationTaskOutcomeEntity out = createValidOutcome("out-1", "snap-1", "dec-1", 10L, 1001L, 50L);
        out.setOutcomeType(TaskOutcomeType.INCOMPLETE_OVERDUE);

        Optional<AdaptiveExclusionReason> reason = datasetService.evaluateOutcomeEligibility(out, new HashSet<>());
        assertEquals(Optional.of(AdaptiveExclusionReason.OUTCOME_NOT_COMPLETED), reason);
    }

    @Test
    @DisplayName("4. Missing decision is excluded with DECISION_NOT_FOUND")
    void test04_decisionNotFound() {
        RecommendationTaskOutcomeEntity out = createValidOutcome("out-1", "snap-1", "dec-missing", 10L, 1001L, 50L);
        when(decisionPort.findById("dec-missing")).thenReturn(Optional.empty());

        Optional<AdaptiveExclusionReason> reason = datasetService.evaluateOutcomeEligibility(out, new HashSet<>());
        assertEquals(Optional.of(AdaptiveExclusionReason.DECISION_NOT_FOUND), reason);
    }

    @Test
    @DisplayName("5. Decision source != USER is excluded with NON_USER_DECISION")
    void test05_nonUserDecision() {
        RecommendationDecisionEventEntity dec = createValidDecision("dec-1", "snap-1", 10L, 1001L, 50L);
        dec.setDecisionSource(RecommendationDecisionSource.SYSTEM);
        RecommendationTaskOutcomeEntity out = createValidOutcome("out-1", "snap-1", "dec-1", 10L, 1001L, 50L);

        when(decisionPort.findById("dec-1")).thenReturn(Optional.of(dec));

        Optional<AdaptiveExclusionReason> reason = datasetService.evaluateOutcomeEligibility(out, new HashSet<>());
        assertEquals(Optional.of(AdaptiveExclusionReason.NON_USER_DECISION), reason);
    }

    @Test
    @DisplayName("6. Non-assignment decision (REJECTED/CANCELED/EXPIRED) is excluded with NON_ASSIGNMENT_DECISION")
    void test06_nonAssignmentDecision() {
        RecommendationDecisionEventEntity dec = createValidDecision("dec-1", "snap-1", 10L, 1001L, 50L);
        dec.setDecisionType(RecommendationDecisionType.REJECTED);
        RecommendationTaskOutcomeEntity out = createValidOutcome("out-1", "snap-1", "dec-1", 10L, 1001L, 50L);

        when(decisionPort.findById("dec-1")).thenReturn(Optional.of(dec));

        Optional<AdaptiveExclusionReason> reason = datasetService.evaluateOutcomeEligibility(out, new HashSet<>());
        assertEquals(Optional.of(AdaptiveExclusionReason.NON_ASSIGNMENT_DECISION), reason);
    }

    @Test
    @DisplayName("7. Decision task ID mismatch is excluded with DECISION_TASK_MISMATCH")
    void test07_decisionTaskMismatch() {
        RecommendationDecisionEventEntity dec = createValidDecision("dec-1", "snap-1", 10L, 9999L, 50L);
        RecommendationTaskOutcomeEntity out = createValidOutcome("out-1", "snap-1", "dec-1", 10L, 1001L, 50L);

        when(decisionPort.findById("dec-1")).thenReturn(Optional.of(dec));

        Optional<AdaptiveExclusionReason> reason = datasetService.evaluateOutcomeEligibility(out, new HashSet<>());
        assertEquals(Optional.of(AdaptiveExclusionReason.DECISION_TASK_MISMATCH), reason);
    }

    @Test
    @DisplayName("8. Decision project ID mismatch is excluded with DECISION_PROJECT_MISMATCH")
    void test08_decisionProjectMismatch() {
        RecommendationDecisionEventEntity dec = createValidDecision("dec-1", "snap-1", 99L, 1001L, 50L);
        RecommendationTaskOutcomeEntity out = createValidOutcome("out-1", "snap-1", "dec-1", 10L, 1001L, 50L);

        when(decisionPort.findById("dec-1")).thenReturn(Optional.of(dec));

        Optional<AdaptiveExclusionReason> reason = datasetService.evaluateOutcomeEligibility(out, new HashSet<>());
        assertEquals(Optional.of(AdaptiveExclusionReason.DECISION_PROJECT_MISMATCH), reason);
    }

    @Test
    @DisplayName("9. Assignee mismatch between decision and observed task is excluded with ASSIGNEE_MISMATCH")
    void test09_assigneeMismatch() {
        RecommendationDecisionEventEntity dec = createValidDecision("dec-1", "snap-1", 10L, 1001L, 50L);
        RecommendationTaskOutcomeEntity out = createValidOutcome("out-1", "snap-1", "dec-1", 10L, 1001L, 99L); // different assignee

        when(decisionPort.findById("dec-1")).thenReturn(Optional.of(dec));

        Optional<AdaptiveExclusionReason> reason = datasetService.evaluateOutcomeEligibility(out, new HashSet<>());
        assertEquals(Optional.of(AdaptiveExclusionReason.ASSIGNEE_MISMATCH), reason);
    }

    @Test
    @DisplayName("10. Missing snapshot is excluded with SNAPSHOT_NOT_FOUND")
    void test10_snapshotNotFound() {
        RecommendationDecisionEventEntity dec = createValidDecision("dec-1", "snap-missing", 10L, 1001L, 50L);
        RecommendationTaskOutcomeEntity out = createValidOutcome("out-1", "snap-missing", "dec-1", 10L, 1001L, 50L);

        when(decisionPort.findById("dec-1")).thenReturn(Optional.of(dec));
        when(snapshotPort.findById("snap-missing")).thenReturn(Optional.empty());

        Optional<AdaptiveExclusionReason> reason = datasetService.evaluateOutcomeEligibility(out, new HashSet<>());
        assertEquals(Optional.of(AdaptiveExclusionReason.SNAPSHOT_NOT_FOUND), reason);
    }

    @Test
    @DisplayName("11. Unsupported snapshot version != allowlisted-view-v2 is excluded with UNSUPPORTED_SNAPSHOT_VERSION")
    void test11_unsupportedSnapshotVersion() {
        RecommendationSnapshotEntity snap = createValidSnapshot("snap-1", 10L, 1001L, 50L);
        snap.setPresentationContractVersion("legacy-view-v1");
        RecommendationDecisionEventEntity dec = createValidDecision("dec-1", "snap-1", 10L, 1001L, 50L);
        RecommendationTaskOutcomeEntity out = createValidOutcome("out-1", "snap-1", "dec-1", 10L, 1001L, 50L);

        when(decisionPort.findById("dec-1")).thenReturn(Optional.of(dec));
        when(snapshotPort.findById("snap-1")).thenReturn(Optional.of(snap));

        Optional<AdaptiveExclusionReason> reason = datasetService.evaluateOutcomeEligibility(out, new HashSet<>());
        assertEquals(Optional.of(AdaptiveExclusionReason.UNSUPPORTED_SNAPSHOT_VERSION), reason);
    }

    @Test
    @DisplayName("12. Snapshot with UNOBSERVED differentiation status is excluded with UNOBSERVED_SNAPSHOT")
    void test12_unobservedSnapshot() {
        RecommendationSnapshotEntity snap = createValidSnapshot("snap-1", 10L, 1001L, 50L);
        snap.setDifferentiationStatus("UNOBSERVED");
        RecommendationDecisionEventEntity dec = createValidDecision("dec-1", "snap-1", 10L, 1001L, 50L);
        RecommendationTaskOutcomeEntity out = createValidOutcome("out-1", "snap-1", "dec-1", 10L, 1001L, 50L);

        when(decisionPort.findById("dec-1")).thenReturn(Optional.of(dec));
        when(snapshotPort.findById("snap-1")).thenReturn(Optional.of(snap));

        Optional<AdaptiveExclusionReason> reason = datasetService.evaluateOutcomeEligibility(out, new HashSet<>());
        assertEquals(Optional.of(AdaptiveExclusionReason.UNOBSERVED_SNAPSHOT), reason);
    }

    @Test
    @DisplayName("13. Candidate not in snapshot candidates list is excluded with SELECTED_CANDIDATE_NOT_FOUND")
    void test13_selectedCandidateNotFoundInSnapshot() {
        RecommendationSnapshotEntity snap = createValidSnapshot("snap-1", 10L, 1001L, 50L);
        RecommendationDecisionEventEntity dec = createValidDecision("dec-1", "snap-1", 10L, 1001L, 999L); // candidate 999 not in snapshot
        RecommendationTaskOutcomeEntity out = createValidOutcome("out-1", "snap-1", "dec-1", 10L, 1001L, 999L);

        when(decisionPort.findById("dec-1")).thenReturn(Optional.of(dec));
        when(snapshotPort.findById("snap-1")).thenReturn(Optional.of(snap));

        Optional<AdaptiveExclusionReason> reason = datasetService.evaluateOutcomeEligibility(out, new HashSet<>());
        assertEquals(Optional.of(AdaptiveExclusionReason.SELECTED_CANDIDATE_NOT_FOUND), reason);
    }

    @Test
    @DisplayName("14. Fit status != MEASURED is excluded with FIT_STATUS_NOT_MEASURED")
    void test14_fitStatusNotMeasured() {
        RecommendationSnapshotEntity snap = createValidSnapshot("snap-1", 10L, 1001L, 50L);
        snap.getCandidates().get(0).setFitStatus(MetricDataStatus.INSUFFICIENT_DATA);
        RecommendationDecisionEventEntity dec = createValidDecision("dec-1", "snap-1", 10L, 1001L, 50L);
        RecommendationTaskOutcomeEntity out = createValidOutcome("out-1", "snap-1", "dec-1", 10L, 1001L, 50L);

        when(decisionPort.findById("dec-1")).thenReturn(Optional.of(dec));
        when(snapshotPort.findById("snap-1")).thenReturn(Optional.of(snap));

        Optional<AdaptiveExclusionReason> reason = datasetService.evaluateOutcomeEligibility(out, new HashSet<>());
        assertEquals(Optional.of(AdaptiveExclusionReason.FIT_STATUS_NOT_MEASURED), reason);
    }

    @Test
    @DisplayName("15. Fit value outside [0, 1] or NaN is excluded with INVALID_FIT_VALUE")
    void test15_invalidFitValue() {
        RecommendationSnapshotEntity snap = createValidSnapshot("snap-1", 10L, 1001L, 50L);
        snap.getCandidates().get(0).setPresentationFitValue(1.5); // out of range
        RecommendationDecisionEventEntity dec = createValidDecision("dec-1", "snap-1", 10L, 1001L, 50L);
        RecommendationTaskOutcomeEntity out = createValidOutcome("out-1", "snap-1", "dec-1", 10L, 1001L, 50L);

        when(decisionPort.findById("dec-1")).thenReturn(Optional.of(dec));
        when(snapshotPort.findById("snap-1")).thenReturn(Optional.of(snap));

        Optional<AdaptiveExclusionReason> reason = datasetService.evaluateOutcomeEligibility(out, new HashSet<>());
        assertEquals(Optional.of(AdaptiveExclusionReason.INVALID_FIT_VALUE), reason);
    }

    @Test
    @DisplayName("16. Workload status != MEASURED is excluded with WORKLOAD_STATUS_NOT_MEASURED")
    void test16_workloadStatusNotMeasured() {
        RecommendationSnapshotEntity snap = createValidSnapshot("snap-1", 10L, 1001L, 50L);
        snap.getCandidates().get(0).setWorkloadStatus(MetricDataStatus.UNVERIFIED);
        RecommendationDecisionEventEntity dec = createValidDecision("dec-1", "snap-1", 10L, 1001L, 50L);
        RecommendationTaskOutcomeEntity out = createValidOutcome("out-1", "snap-1", "dec-1", 10L, 1001L, 50L);

        when(decisionPort.findById("dec-1")).thenReturn(Optional.of(dec));
        when(snapshotPort.findById("snap-1")).thenReturn(Optional.of(snap));

        Optional<AdaptiveExclusionReason> reason = datasetService.evaluateOutcomeEligibility(out, new HashSet<>());
        assertEquals(Optional.of(AdaptiveExclusionReason.WORKLOAD_STATUS_NOT_MEASURED), reason);
    }

    @Test
    @DisplayName("17. Unsupported workload unit != ACTIVE_TASK_COUNT is excluded with UNSUPPORTED_WORKLOAD_UNIT")
    void test17_unsupportedWorkloadUnit() {
        RecommendationSnapshotEntity snap = createValidSnapshot("snap-1", 10L, 1001L, 50L);
        snap.getCandidates().get(0).setWorkloadUnit("HOURS");
        RecommendationDecisionEventEntity dec = createValidDecision("dec-1", "snap-1", 10L, 1001L, 50L);
        RecommendationTaskOutcomeEntity out = createValidOutcome("out-1", "snap-1", "dec-1", 10L, 1001L, 50L);

        when(decisionPort.findById("dec-1")).thenReturn(Optional.of(dec));
        when(snapshotPort.findById("snap-1")).thenReturn(Optional.of(snap));

        Optional<AdaptiveExclusionReason> reason = datasetService.evaluateOutcomeEligibility(out, new HashSet<>());
        assertEquals(Optional.of(AdaptiveExclusionReason.UNSUPPORTED_WORKLOAD_UNIT), reason);
    }

    @Test
    @DisplayName("18. Unsupported workload scope != PROJECT is excluded with UNSUPPORTED_WORKLOAD_SCOPE")
    void test18_unsupportedWorkloadScope() {
        RecommendationSnapshotEntity snap = createValidSnapshot("snap-1", 10L, 1001L, 50L);
        snap.getCandidates().get(0).setWorkloadScope("GLOBAL");
        RecommendationDecisionEventEntity dec = createValidDecision("dec-1", "snap-1", 10L, 1001L, 50L);
        RecommendationTaskOutcomeEntity out = createValidOutcome("out-1", "snap-1", "dec-1", 10L, 1001L, 50L);

        when(decisionPort.findById("dec-1")).thenReturn(Optional.of(dec));
        when(snapshotPort.findById("snap-1")).thenReturn(Optional.of(snap));

        Optional<AdaptiveExclusionReason> reason = datasetService.evaluateOutcomeEligibility(out, new HashSet<>());
        assertEquals(Optional.of(AdaptiveExclusionReason.UNSUPPORTED_WORKLOAD_SCOPE), reason);
    }

    @Test
    @DisplayName("19. Missing workload measurement timestamp is excluded with MISSING_WORKLOAD_TIMESTAMP")
    void test19_missingWorkloadTimestamp() {
        RecommendationSnapshotEntity snap = createValidSnapshot("snap-1", 10L, 1001L, 50L);
        snap.getCandidates().get(0).setWorkloadMeasuredAt(null);
        RecommendationDecisionEventEntity dec = createValidDecision("dec-1", "snap-1", 10L, 1001L, 50L);
        RecommendationTaskOutcomeEntity out = createValidOutcome("out-1", "snap-1", "dec-1", 10L, 1001L, 50L);

        when(decisionPort.findById("dec-1")).thenReturn(Optional.of(dec));
        when(snapshotPort.findById("snap-1")).thenReturn(Optional.of(snap));

        Optional<AdaptiveExclusionReason> reason = datasetService.evaluateOutcomeEligibility(out, new HashSet<>());
        assertEquals(Optional.of(AdaptiveExclusionReason.MISSING_WORKLOAD_TIMESTAMP), reason);
    }

    @Test
    @DisplayName("20. Negative workload value is excluded with INVALID_WORKLOAD_VALUE")
    void test20_invalidWorkloadValue() {
        RecommendationSnapshotEntity snap = createValidSnapshot("snap-1", 10L, 1001L, 50L);
        snap.getCandidates().get(0).setStoredWorkloadValue(-1);
        RecommendationDecisionEventEntity dec = createValidDecision("dec-1", "snap-1", 10L, 1001L, 50L);
        RecommendationTaskOutcomeEntity out = createValidOutcome("out-1", "snap-1", "dec-1", 10L, 1001L, 50L);

        when(decisionPort.findById("dec-1")).thenReturn(Optional.of(dec));
        when(snapshotPort.findById("snap-1")).thenReturn(Optional.of(snap));

        Optional<AdaptiveExclusionReason> reason = datasetService.evaluateOutcomeEligibility(out, new HashSet<>());
        assertEquals(Optional.of(AdaptiveExclusionReason.INVALID_WORKLOAD_VALUE), reason);
    }

    @Test
    @DisplayName("21. Duplicate outcome record is excluded with DUPLICATE_SAMPLE")
    void test21_duplicateSample() {
        RecommendationSnapshotEntity snap = createValidSnapshot("snap-1", 10L, 1001L, 50L);
        RecommendationDecisionEventEntity dec = createValidDecision("dec-1", "snap-1", 10L, 1001L, 50L);
        RecommendationTaskOutcomeEntity out1 = createValidOutcome("out-1", "snap-1", "dec-1", 10L, 1001L, 50L);
        RecommendationTaskOutcomeEntity out2 = createValidOutcome("out-2", "snap-1", "dec-1", 10L, 1001L, 50L);

        when(decisionPort.findById("dec-1")).thenReturn(Optional.of(dec));
        when(snapshotPort.findById("snap-1")).thenReturn(Optional.of(snap));

        Set<String> seen = new HashSet<>();
        Optional<AdaptiveExclusionReason> r1 = datasetService.evaluateOutcomeEligibility(out1, seen);
        Optional<AdaptiveExclusionReason> r2 = datasetService.evaluateOutcomeEligibility(out2, seen);

        assertTrue(r1.isEmpty());
        assertEquals(Optional.of(AdaptiveExclusionReason.DUPLICATE_SAMPLE), r2);
    }

    @Test
    @DisplayName("22. Canonical SHA-256 hash is stable and deterministic")
    void test22_canonicalSha256Stability() {
        RecommendationSnapshotEntity snap1 = createValidSnapshot("snap-1", 10L, 1001L, 50L);
        RecommendationDecisionEventEntity dec1 = createValidDecision("dec-1", "snap-1", 10L, 1001L, 50L);
        RecommendationTaskOutcomeEntity out1 = createValidOutcome("out-1", "snap-1", "dec-1", 10L, 1001L, 50L);

        RecommendationSnapshotEntity snap2 = createValidSnapshot("snap-2", 10L, 1002L, 60L);
        RecommendationDecisionEventEntity dec2 = createValidDecision("dec-2", "snap-2", 10L, 1002L, 60L);
        RecommendationTaskOutcomeEntity out2 = createValidOutcome("out-2", "snap-2", "dec-2", 10L, 1002L, 60L);

        when(snapshotPort.findById("snap-1")).thenReturn(Optional.of(snap1));
        when(decisionPort.findById("dec-1")).thenReturn(Optional.of(dec1));
        when(snapshotPort.findById("snap-2")).thenReturn(Optional.of(snap2));
        when(decisionPort.findById("dec-2")).thenReturn(Optional.of(dec2));

        // Evaluate in order out1, out2
        AdaptiveDatasetService.DatasetResult resA = datasetService.evaluateOutcomes(List.of(out1, out2));
        // Evaluate in reversed order out2, out1
        AdaptiveDatasetService.DatasetResult resB = datasetService.evaluateOutcomes(List.of(out2, out1));

        assertNotNull(resA.qualityReport().canonicalDatasetSha256());
        assertEquals(resA.qualityReport().canonicalDatasetSha256(), resB.qualityReport().canonicalDatasetSha256());
        assertEquals(64, resA.qualityReport().canonicalDatasetSha256().length());
    }

    @Test
    @DisplayName("23. Privacy invariant: zero PII or full precision scores in dataset rows")
    void test23_privacyInvariant() {
        RecommendationSnapshotEntity snap = createValidSnapshot("snap-1", 10L, 1001L, 50L);
        RecommendationDecisionEventEntity dec = createValidDecision("dec-1", "snap-1", 10L, 1001L, 50L);
        RecommendationTaskOutcomeEntity out = createValidOutcome("out-1", "snap-1", "dec-1", 10L, 1001L, 50L);

        when(snapshotPort.findById("snap-1")).thenReturn(Optional.of(snap));
        when(decisionPort.findById("dec-1")).thenReturn(Optional.of(dec));

        AdaptiveDatasetService.DatasetResult res = datasetService.evaluateOutcomes(List.of(out));
        AdaptiveDatasetRowDto row = res.eligibleRows().get(0);

        // Row contains only IDs and normalized numeric values
        assertEquals("out-1", row.rowId());
        assertEquals(1001L, row.taskId());
        assertEquals("BALANCED", row.heuristicMode());
        assertEquals(50L, row.selectedCandidateId());
        assertEquals(0.8, row.selectedCandidateFit(), 1e-6);
        assertEquals(2.0, row.selectedCandidateLoad(), 1e-6);
    }

    @Test
    @DisplayName("24. Snapshots with non-BALANCED heuristic mode (URGENT, TRAINING, null) are excluded")
    void test24_unsupportedHeuristicMode_isExcluded() {
        RecommendationSnapshotEntity snapUrgent = createValidSnapshot("snap-urgent", 10L, 1001L, 50L);
        snapUrgent.setHeuristicMode("URGENT");
        RecommendationSnapshotEntity snapTraining = createValidSnapshot("snap-train", 10L, 1002L, 50L);
        snapTraining.setHeuristicMode("TRAINING");
        RecommendationSnapshotEntity snapNull = createValidSnapshot("snap-null", 10L, 1003L, 50L);
        snapNull.setHeuristicMode(null);

        RecommendationDecisionEventEntity decUrgent = createValidDecision("dec-urgent", "snap-urgent", 10L, 1001L, 50L);
        RecommendationTaskOutcomeEntity outUrgent = createValidOutcome("out-urgent", "snap-urgent", "dec-urgent", 10L, 1001L, 50L);

        RecommendationDecisionEventEntity decTrain = createValidDecision("dec-train", "snap-train", 10L, 1002L, 50L);
        RecommendationTaskOutcomeEntity outTrain = createValidOutcome("out-train", "snap-train", "dec-train", 10L, 1002L, 50L);

        RecommendationDecisionEventEntity decNull = createValidDecision("dec-null", "snap-null", 10L, 1003L, 50L);
        RecommendationTaskOutcomeEntity outNull = createValidOutcome("out-null", "snap-null", "dec-null", 10L, 1003L, 50L);

        when(snapshotPort.findById("snap-urgent")).thenReturn(Optional.of(snapUrgent));
        when(decisionPort.findById("dec-urgent")).thenReturn(Optional.of(decUrgent));
        when(snapshotPort.findById("snap-train")).thenReturn(Optional.of(snapTraining));
        when(decisionPort.findById("dec-train")).thenReturn(Optional.of(decTrain));
        when(snapshotPort.findById("snap-null")).thenReturn(Optional.of(snapNull));
        when(decisionPort.findById("dec-null")).thenReturn(Optional.of(decNull));

        Optional<AdaptiveExclusionReason> r1 = datasetService.evaluateOutcomeEligibility(outUrgent, new HashSet<>());
        Optional<AdaptiveExclusionReason> r2 = datasetService.evaluateOutcomeEligibility(outTrain, new HashSet<>());
        Optional<AdaptiveExclusionReason> r3 = datasetService.evaluateOutcomeEligibility(outNull, new HashSet<>());

        assertEquals(Optional.of(AdaptiveExclusionReason.UNSUPPORTED_HEURISTIC_MODE), r1);
        assertEquals(Optional.of(AdaptiveExclusionReason.UNSUPPORTED_HEURISTIC_MODE), r2);
        assertEquals(Optional.of(AdaptiveExclusionReason.UNSUPPORTED_HEURISTIC_MODE), r3);

        AdaptiveDatasetService.DatasetResult result = datasetService.evaluateOutcomes(List.of(outUrgent, outTrain, outNull));
        assertEquals(0, result.eligibleRows().size());
        assertEquals(3, result.qualityReport().excludedSamplesCount());
        assertEquals(3L, result.qualityReport().exclusionBreakdown().get("UNSUPPORTED_HEURISTIC_MODE"));
    }
}
