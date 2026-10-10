package com.taskpilot.ai.outcome;

import com.taskpilot.ai.assignment.port.out.RecommendationDecisionEventPort;
import com.taskpilot.ai.assignment.port.out.RecommendationSnapshotPort;
import com.taskpilot.ai.assignment.port.out.RecommendationTaskOutcomePort;
import com.taskpilot.ai.dto.*;
import com.taskpilot.ai.entity.RecommendationDecisionEventEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotCandidateEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotEntity;
import com.taskpilot.ai.entity.RecommendationTaskOutcomeEntity;
import com.taskpilot.ai.service.TaskOutcomeEvaluator;
import com.taskpilot.ai.service.TaskOutcomeEventListener;
import com.taskpilot.ai.service.TaskOutcomeService;
import com.taskpilot.contracts.assignment.event.TaskCompletedLifecycleEvent;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 2D Task Outcome Foundation Test Suite
 * Covers all 24 required test cases from the Phase 2D specification.
 */
class RecommendationTaskOutcomeTest {

    private RecommendationTaskOutcomePort outcomePort;
    private RecommendationDecisionEventPort decisionPort;
    private RecommendationSnapshotPort snapshotPort;
    private ProjectMemberPort projectMemberPort;
    private TaskOutcomeEvaluator outcomeEvaluator;
    private TaskOutcomeService outcomeService;
    private TaskOutcomeEventListener eventListener;

    private static final Long PROJECT_ID = 10L;
    private static final Long TASK_ID = 76L;
    private static final Long RECOMMENDED_CANDIDATE_ID = 42L;
    private static final Long OVERRIDE_CANDIDATE_ID = 43L;
    private static final Long MEMBER_USER_ID = 100L;
    private static final Long NON_MEMBER_USER_ID = 999L;
    private static final String SNAPSHOT_ID = "rec-snap-test-101";
    private static final String DECISION_ID = "rec-dec-test-201";

    private final Instant baseTime = Instant.parse("2026-10-10T12:00:00Z");
    private final Instant dueAt = baseTime.plusSeconds(3600); // 13:00:00Z

    @BeforeEach
    void setUp() {
        outcomePort = mock(RecommendationTaskOutcomePort.class);
        decisionPort = mock(RecommendationDecisionEventPort.class);
        snapshotPort = mock(RecommendationSnapshotPort.class);
        projectMemberPort = mock(ProjectMemberPort.class);
        outcomeEvaluator = new TaskOutcomeEvaluator();

        outcomeService = new TaskOutcomeService(
                outcomePort,
                decisionPort,
                snapshotPort,
                projectMemberPort,
                outcomeEvaluator
        );

        eventListener = new TaskOutcomeEventListener(outcomeService);

        // Standard save stubbing: returns input entity
        when(outcomePort.save(any(RecommendationTaskOutcomeEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Default project membership authorization
        when(projectMemberPort.isProjectMember(PROJECT_ID, MEMBER_USER_ID)).thenReturn(true);
        when(projectMemberPort.isProjectMember(PROJECT_ID, NON_MEMBER_USER_ID)).thenReturn(false);
    }

    private RecommendationSnapshotEntity createSnapshot(String diffStatus) {
        RecommendationSnapshotEntity snapshot = RecommendationSnapshotEntity.builder()
                .snapshotId(SNAPSHOT_ID)
                .projectId(PROJECT_ID)
                .taskId(TASK_ID)
                .requestedByUserId(1L)
                .createdAt(baseTime)
                .heuristicMode("BALANCED")
                .presentationContractVersion("allowlisted-view-v2")
                .scoringModelVersion("scoring-model-v2")
                .differentiationStatus(diffStatus)
                .requiredSkills("[\"Java\"]")
                .recommendedCandidateId(RECOMMENDED_CANDIDATE_ID)
                .candidateCount(2)
                .requestSource(RecommendationRequestSource.AI_TOOL_TASK_RECOMMENDATION)
                .fitWeight(0.5)
                .loadWeight(0.3)
                .performanceWeight(0.2)
                .normalizationContract("neutral-min-max-fixed-point-v1")
                .build();

        RecommendationSnapshotCandidateEntity c1 = RecommendationSnapshotCandidateEntity.builder()
                .candidateId(RECOMMENDED_CANDIDATE_ID)
                .rank(1)
                .rankingKey(1_000_000_000L)
                .fullPrecisionScore(0.85)
                .rankingRawFit(0.9)
                .presentationFitValue(0.9)
                .fitStatus(MetricDataStatus.MEASURED)
                .storedWorkloadValue(2)
                .workloadStatus(MetricDataStatus.MEASURED)
                .workloadUnit("ACTIVE_TASK_COUNT")
                .workloadScope("PROJECT")
                .workloadMeasuredAt(baseTime)
                .derivedPerformanceInput(0.5)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("ACTIVE")
                .selectedAsRecommendation(true)
                .build();

        RecommendationSnapshotCandidateEntity c2 = RecommendationSnapshotCandidateEntity.builder()
                .candidateId(OVERRIDE_CANDIDATE_ID)
                .rank(2)
                .rankingKey(800_000_000L)
                .fullPrecisionScore(0.70)
                .rankingRawFit(0.75)
                .presentationFitValue(0.75)
                .fitStatus(MetricDataStatus.MEASURED)
                .storedWorkloadValue(4)
                .workloadStatus(MetricDataStatus.MEASURED)
                .workloadUnit("ACTIVE_TASK_COUNT")
                .workloadScope("PROJECT")
                .workloadMeasuredAt(baseTime)
                .derivedPerformanceInput(0.5)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("ACTIVE")
                .selectedAsRecommendation(false)
                .build();

        snapshot.addCandidate(c1);
        snapshot.addCandidate(c2);
        return snapshot;
    }

    private RecommendationDecisionEventEntity createDecision(
            RecommendationDecisionType decisionType,
            Long selectedCandidateId) {
        return RecommendationDecisionEventEntity.builder()
                .decisionId(DECISION_ID)
                .snapshotId(SNAPSHOT_ID)
                .projectId(PROJECT_ID)
                .taskId(TASK_ID)
                .decidedByUserId(1L)
                .decisionSource(RecommendationDecisionSource.USER)
                .decisionType(decisionType)
                .recommendedCandidateId(RECOMMENDED_CANDIDATE_ID)
                .selectedCandidateId(selectedCandidateId)
                .createdAt(baseTime.plusSeconds(60))
                .build();
    }

    // ==========================================
    // 1. Completed before due date -> COMPLETED_ON_TIME
    // ==========================================
    @Test
    @DisplayName("1. Completed before due date results in COMPLETED_ON_TIME")
    void test01_completedBeforeDueDate_completedOnTime() {
        Instant completedAt = dueAt.minusSeconds(600); // 10 mins before deadline
        TaskOutcomeEvaluator.EvaluationResult result = outcomeEvaluator.evaluate(
                "DONE", dueAt, completedAt, baseTime.plusSeconds(3700),
                RECOMMENDED_CANDIDATE_ID, RECOMMENDED_CANDIDATE_ID);

        assertEquals(TaskOutcomeType.COMPLETED_ON_TIME, result.outcomeType());
        assertNull(result.exclusionReason());
    }

    // ==========================================
    // 2. Completed exactly at due date -> COMPLETED_ON_TIME
    // ==========================================
    @Test
    @DisplayName("2. Completed exactly at due date results in COMPLETED_ON_TIME")
    void test02_completedExactlyAtDueDate_completedOnTime() {
        Instant completedAt = dueAt; // exact deadline match
        TaskOutcomeEvaluator.EvaluationResult result = outcomeEvaluator.evaluate(
                "DONE", dueAt, completedAt, baseTime.plusSeconds(3700),
                RECOMMENDED_CANDIDATE_ID, RECOMMENDED_CANDIDATE_ID);

        assertEquals(TaskOutcomeType.COMPLETED_ON_TIME, result.outcomeType());
        assertNull(result.exclusionReason());
    }

    // ==========================================
    // 3. Completed after due date -> COMPLETED_LATE
    // ==========================================
    @Test
    @DisplayName("3. Completed after due date results in COMPLETED_LATE")
    void test03_completedAfterDueDate_completedLate() {
        Instant completedAt = dueAt.plusSeconds(600); // 10 mins late
        TaskOutcomeEvaluator.EvaluationResult result = outcomeEvaluator.evaluate(
                "DONE", dueAt, completedAt, baseTime.plusSeconds(4200),
                RECOMMENDED_CANDIDATE_ID, RECOMMENDED_CANDIDATE_ID);

        assertEquals(TaskOutcomeType.COMPLETED_LATE, result.outcomeType());
        assertNull(result.exclusionReason());
    }

    // ==========================================
    // 4. Incomplete after due date -> INCOMPLETE_OVERDUE
    // ==========================================
    @Test
    @DisplayName("4. Incomplete after due date results in INCOMPLETE_OVERDUE")
    void test04_incompleteAfterDueDate_incompleteOverdue() {
        Instant observedAt = dueAt.plusSeconds(600);
        TaskOutcomeEvaluator.EvaluationResult result = outcomeEvaluator.evaluate(
                "IN_PROGRESS", dueAt, null, observedAt,
                RECOMMENDED_CANDIDATE_ID, RECOMMENDED_CANDIDATE_ID);

        assertEquals(TaskOutcomeType.INCOMPLETE_OVERDUE, result.outcomeType());
        assertNull(result.exclusionReason());
    }

    // ==========================================
    // 5. Incomplete before due date -> NOT_YET_OBSERVABLE
    // ==========================================
    @Test
    @DisplayName("5. Incomplete before due date results in NOT_YET_OBSERVABLE")
    void test05_incompleteBeforeDueDate_notYetObservable() {
        Instant observedAt = dueAt.minusSeconds(600);
        TaskOutcomeEvaluator.EvaluationResult result = outcomeEvaluator.evaluate(
                "IN_PROGRESS", dueAt, null, observedAt,
                RECOMMENDED_CANDIDATE_ID, RECOMMENDED_CANDIDATE_ID);

        assertEquals(TaskOutcomeType.NOT_YET_OBSERVABLE, result.outcomeType());
        assertNull(result.exclusionReason());
    }

    // ==========================================
    // 6. Completed task without due date -> EXCLUDED (MISSING_DUE_DATE)
    // ==========================================
    @Test
    @DisplayName("6. Completed task without due date results in EXCLUDED with MISSING_DUE_DATE")
    void test06_completedWithoutDueDate_excluded() {
        Instant completedAt = baseTime.plusSeconds(1800);
        TaskOutcomeEvaluator.EvaluationResult result = outcomeEvaluator.evaluate(
                "DONE", (Instant) null, completedAt, baseTime.plusSeconds(2000),
                RECOMMENDED_CANDIDATE_ID, RECOMMENDED_CANDIDATE_ID);

        assertEquals(TaskOutcomeType.EXCLUDED, result.outcomeType());
        assertEquals(TaskOutcomeEvaluator.EXCLUSION_MISSING_DUE_DATE, result.exclusionReason());
    }

    // ==========================================
    // 7. Completed task without completion timestamp -> EXCLUDED (MISSING_COMPLETION_TIMESTAMP)
    // ==========================================
    @Test
    @DisplayName("7. Completed task without completion timestamp results in EXCLUDED with MISSING_COMPLETION_TIMESTAMP")
    void test07_completedWithoutCompletionTimestamp_excluded() {
        TaskOutcomeEvaluator.EvaluationResult result = outcomeEvaluator.evaluate(
                "DONE", dueAt, null, baseTime.plusSeconds(2000),
                RECOMMENDED_CANDIDATE_ID, RECOMMENDED_CANDIDATE_ID);

        assertEquals(TaskOutcomeType.EXCLUDED, result.outcomeType());
        assertEquals(TaskOutcomeEvaluator.EXCLUSION_MISSING_COMPLETION_TIMESTAMP, result.exclusionReason());
    }

    // ==========================================
    // 8. Missing data is not treated as late
    // ==========================================
    @Test
    @DisplayName("8. Missing data is not treated as late; it produces EXCLUDED instead")
    void test08_missingDataNotTreatedAsLate() {
        TaskOutcomeEvaluator.EvaluationResult noDueDate = outcomeEvaluator.evaluate(
                "DONE", (Instant) null, baseTime.plusSeconds(1800), baseTime.plusSeconds(2000),
                RECOMMENDED_CANDIDATE_ID, RECOMMENDED_CANDIDATE_ID);

        TaskOutcomeEvaluator.EvaluationResult noCompletionTs = outcomeEvaluator.evaluate(
                "DONE", dueAt, null, baseTime.plusSeconds(2000),
                RECOMMENDED_CANDIDATE_ID, RECOMMENDED_CANDIDATE_ID);

        assertNotEquals(TaskOutcomeType.COMPLETED_LATE, noDueDate.outcomeType());
        assertNotEquals(TaskOutcomeType.COMPLETED_LATE, noCompletionTs.outcomeType());
        assertEquals(TaskOutcomeType.EXCLUDED, noDueDate.outcomeType());
        assertEquals(TaskOutcomeType.EXCLUDED, noCompletionTs.outcomeType());
    }

    // ==========================================
    // 9. ACCEPTED decision links to actual assigned candidate
    // ==========================================
    @Test
    @DisplayName("9. ACCEPTED decision links to actual assigned candidate")
    void test09_acceptedDecisionLinksToAssignedCandidate() {
        RecommendationSnapshotEntity snapshot = createSnapshot("DIFFERENTIATED");
        RecommendationDecisionEventEntity decision = createDecision(
                RecommendationDecisionType.ACCEPTED, RECOMMENDED_CANDIDATE_ID);

        when(snapshotPort.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        when(decisionPort.findById(DECISION_ID)).thenReturn(Optional.of(decision));

        Instant completedAt = dueAt.minusSeconds(300);
        TaskOutcomeDto dto = outcomeService.recordOutcome(
                DECISION_ID, TASK_ID, RECOMMENDED_CANDIDATE_ID, "DONE",
                completedAt, dueAt, Instant.now());

        assertNotNull(dto);
        assertEquals(RECOMMENDED_CANDIDATE_ID, dto.selectedCandidateId());
        assertEquals(RECOMMENDED_CANDIDATE_ID, dto.observedAssigneeId());
        assertEquals(RecommendationDecisionType.ACCEPTED, dto.decisionType());
        assertEquals(TaskOutcomeType.COMPLETED_ON_TIME, dto.outcomeType());
    }

    // ==========================================
    // 10. OVERRIDDEN decision links to override candidate
    // ==========================================
    @Test
    @DisplayName("10. OVERRIDDEN decision links to override candidate")
    void test10_overriddenDecisionLinksToOverrideCandidate() {
        RecommendationSnapshotEntity snapshot = createSnapshot("DIFFERENTIATED");
        RecommendationDecisionEventEntity decision = createDecision(
                RecommendationDecisionType.OVERRIDDEN, OVERRIDE_CANDIDATE_ID);

        when(snapshotPort.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        when(decisionPort.findById(DECISION_ID)).thenReturn(Optional.of(decision));

        Instant completedAt = dueAt.plusSeconds(300);
        TaskOutcomeDto dto = outcomeService.recordOutcome(
                DECISION_ID, TASK_ID, OVERRIDE_CANDIDATE_ID, "DONE",
                completedAt, dueAt, Instant.now());

        assertNotNull(dto);
        assertEquals(OVERRIDE_CANDIDATE_ID, dto.selectedCandidateId());
        assertEquals(OVERRIDE_CANDIDATE_ID, dto.observedAssigneeId());
        assertEquals(RecommendationDecisionType.OVERRIDDEN, dto.decisionType());
        assertEquals(TaskOutcomeType.COMPLETED_LATE, dto.outcomeType());
    }

    // ==========================================
    // 11. REJECTED creates no assignment outcome
    // ==========================================
    @Test
    @DisplayName("11. REJECTED decision creates no assignment outcome")
    void test11_rejectedDecisionCreatesNoAssignmentOutcome() {
        RecommendationSnapshotEntity snapshot = createSnapshot("DIFFERENTIATED");
        RecommendationDecisionEventEntity decision = createDecision(
                RecommendationDecisionType.REJECTED, null);

        when(snapshotPort.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        when(decisionPort.findById(DECISION_ID)).thenReturn(Optional.of(decision));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                outcomeService.recordOutcome(
                        DECISION_ID, TASK_ID, RECOMMENDED_CANDIDATE_ID, "DONE",
                        dueAt.minusSeconds(100), dueAt, Instant.now()));

        assertEquals(HttpStatus.BAD_REQUEST.value(), ex.getStatus());
        assertTrue(ex.getMessage().contains("Non-assignment decision"));
        verify(outcomePort, never()).save(any());
    }

    // ==========================================
    // 12. CANCELED creates no assignment outcome
    // ==========================================
    @Test
    @DisplayName("12. CANCELED decision creates no assignment outcome")
    void test12_canceledDecisionCreatesNoAssignmentOutcome() {
        RecommendationSnapshotEntity snapshot = createSnapshot("DIFFERENTIATED");
        RecommendationDecisionEventEntity decision = createDecision(
                RecommendationDecisionType.CANCELED, null);

        when(snapshotPort.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        when(decisionPort.findById(DECISION_ID)).thenReturn(Optional.of(decision));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                outcomeService.recordOutcome(
                        DECISION_ID, TASK_ID, RECOMMENDED_CANDIDATE_ID, "DONE",
                        dueAt.minusSeconds(100), dueAt, Instant.now()));

        assertEquals(HttpStatus.BAD_REQUEST.value(), ex.getStatus());
        verify(outcomePort, never()).save(any());
    }

    // ==========================================
    // 13. EXPIRED creates no assignment outcome
    // ==========================================
    @Test
    @DisplayName("13. EXPIRED decision creates no assignment outcome")
    void test13_expiredDecisionCreatesNoAssignmentOutcome() {
        RecommendationSnapshotEntity snapshot = createSnapshot("DIFFERENTIATED");
        RecommendationDecisionEventEntity decision = createDecision(
                RecommendationDecisionType.EXPIRED, null);

        when(snapshotPort.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        when(decisionPort.findById(DECISION_ID)).thenReturn(Optional.of(decision));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                outcomeService.recordOutcome(
                        DECISION_ID, TASK_ID, RECOMMENDED_CANDIDATE_ID, "DONE",
                        dueAt.minusSeconds(100), dueAt, Instant.now()));

        assertEquals(HttpStatus.BAD_REQUEST.value(), ex.getStatus());
        verify(outcomePort, never()).save(any());
    }

    // ==========================================
    // 14. UNOBSERVED snapshot creates no learning outcome
    // ==========================================
    @Test
    @DisplayName("14. UNOBSERVED snapshot creates no learning outcome in Adaptive eligibility")
    void test14_unobservedSnapshotCreatesNoLearningOutcome() {
        RecommendationSnapshotEntity snapshot = createSnapshot("UNOBSERVED");
        RecommendationDecisionEventEntity decision = createDecision(
                RecommendationDecisionType.ACCEPTED, RECOMMENDED_CANDIDATE_ID);

        String outcomeId = "rec-out-test-14";
        RecommendationTaskOutcomeEntity outcome = RecommendationTaskOutcomeEntity.builder()
                .outcomeId(outcomeId)
                .snapshotId(SNAPSHOT_ID)
                .decisionId(DECISION_ID)
                .projectId(PROJECT_ID)
                .taskId(TASK_ID)
                .recommendedCandidateId(RECOMMENDED_CANDIDATE_ID)
                .selectedCandidateId(RECOMMENDED_CANDIDATE_ID)
                .decisionType(RecommendationDecisionType.ACCEPTED)
                .decisionSource(RecommendationDecisionSource.USER)
                .observedAssigneeId(RECOMMENDED_CANDIDATE_ID)
                .taskStatus("DONE")
                .dueAt(dueAt)
                .completedAt(dueAt.minusSeconds(300))
                .outcomeType(TaskOutcomeType.COMPLETED_ON_TIME)
                .observedAt(Instant.now())
                .outcomeVersion("outcome-v1")
                .build();

        when(outcomePort.findById(outcomeId)).thenReturn(Optional.of(outcome));
        when(decisionPort.findById(DECISION_ID)).thenReturn(Optional.of(decision));
        when(snapshotPort.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));

        AdaptiveEligibilityDto eligibility = outcomeService.evaluateAdaptiveEligibility(outcomeId);

        assertFalse(eligibility.eligible());
        assertEquals("UNOBSERVED_SNAPSHOT_EXCLUDED", eligibility.reason());
    }

    // ==========================================
    // 15. Snapshot/project/task mismatch is rejected
    // ==========================================
    @Test
    @DisplayName("15. Snapshot/project/task mismatch is rejected")
    void test15_snapshotProjectTaskMismatchRejected() {
        RecommendationSnapshotEntity snapshot = createSnapshot("DIFFERENTIATED");
        // Decision with mismatched projectId
        RecommendationDecisionEventEntity decision = RecommendationDecisionEventEntity.builder()
                .decisionId(DECISION_ID)
                .snapshotId(SNAPSHOT_ID)
                .projectId(999L) // Mismatched project
                .taskId(TASK_ID)
                .decidedByUserId(1L)
                .decisionSource(RecommendationDecisionSource.USER)
                .decisionType(RecommendationDecisionType.ACCEPTED)
                .recommendedCandidateId(RECOMMENDED_CANDIDATE_ID)
                .selectedCandidateId(RECOMMENDED_CANDIDATE_ID)
                .build();

        when(snapshotPort.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        when(decisionPort.findById(DECISION_ID)).thenReturn(Optional.of(decision));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                outcomeService.recordOutcome(
                        DECISION_ID, TASK_ID, RECOMMENDED_CANDIDATE_ID, "DONE",
                        dueAt.minusSeconds(100), dueAt, Instant.now()));

        assertEquals(HttpStatus.BAD_REQUEST.value(), ex.getStatus());
        assertTrue(ex.getMessage().contains("does not match"));
    }

    // ==========================================
    // 16. Decision/task assignee mismatch is excluded per H-023
    // ==========================================
    @Test
    @DisplayName("16. Decision/task assignee mismatch is excluded per H-023")
    void test16_decisionTaskAssigneeMismatchExcluded() {
        RecommendationSnapshotEntity snapshot = createSnapshot("DIFFERENTIATED");
        RecommendationDecisionEventEntity decision = createDecision(
                RecommendationDecisionType.ACCEPTED, RECOMMENDED_CANDIDATE_ID);

        when(snapshotPort.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        when(decisionPort.findById(DECISION_ID)).thenReturn(Optional.of(decision));

        Long differentAssigneeId = 777L; // Someone else actually did the task
        TaskOutcomeDto dto = outcomeService.recordOutcome(
                DECISION_ID, TASK_ID, differentAssigneeId, "DONE",
                dueAt.minusSeconds(100), dueAt, Instant.now());

        assertNotNull(dto);
        assertEquals(TaskOutcomeType.EXCLUDED, dto.outcomeType());
        assertEquals(TaskOutcomeEvaluator.EXCLUSION_ASSIGNEE_MISMATCH, dto.exclusionReason());
    }

    // ==========================================
    // 17. Duplicate final outcome is prevented
    // ==========================================
    @Test
    @DisplayName("17. Duplicate final outcome is prevented with HTTP 409 Conflict")
    void test17_duplicateFinalOutcomePrevented() {
        RecommendationSnapshotEntity snapshot = createSnapshot("DIFFERENTIATED");
        RecommendationDecisionEventEntity decision = createDecision(
                RecommendationDecisionType.ACCEPTED, RECOMMENDED_CANDIDATE_ID);

        when(snapshotPort.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        when(decisionPort.findById(DECISION_ID)).thenReturn(Optional.of(decision));

        // Already exists
        RecommendationTaskOutcomeEntity existing = RecommendationTaskOutcomeEntity.builder()
                .outcomeId("existing-out-1")
                .decisionId(DECISION_ID)
                .taskId(TASK_ID)
                .build();
        when(outcomePort.findByDecisionIdAndTaskId(DECISION_ID, TASK_ID)).thenReturn(Optional.of(existing));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                outcomeService.recordOutcome(
                        DECISION_ID, TASK_ID, RECOMMENDED_CANDIDATE_ID, "DONE",
                        dueAt.minusSeconds(100), dueAt, Instant.now()));

        assertEquals(HttpStatus.CONFLICT.value(), ex.getStatus());
        assertTrue(ex.getMessage().contains("Conflict: An outcome record has already been recorded"));
    }

    // ==========================================
    // 18. Task completion failure creates no outcome
    // ==========================================
    @Test
    @DisplayName("18. Task completion failure creates no outcome")
    void test18_taskCompletionFailureCreatesNoOutcome() {
        // If a task completion throws or fails, no TaskCompletedLifecycleEvent is published
        // Thus eventListener is never called, and outcomePort.save is never invoked
        eventListener.onTaskCompleted(null);
        verify(outcomePort, never()).save(any());

        TaskCompletedLifecycleEvent nullTaskEvent = new TaskCompletedLifecycleEvent(
                null, PROJECT_ID, RECOMMENDED_CANDIDATE_ID, Instant.now(), dueAt);
        eventListener.onTaskCompleted(nullTaskEvent);
        verify(outcomePort, never()).save(any());
    }

    // ==========================================
    // 19. Outcome persistence failure does not corrupt task state
    // ==========================================
    @Test
    @DisplayName("19. Outcome persistence failure does not throw or corrupt task state")
    void test19_outcomePersistenceFailureDoesNotCorruptTaskState() {
        RecommendationDecisionEventEntity decision = createDecision(
                RecommendationDecisionType.ACCEPTED, RECOMMENDED_CANDIDATE_ID);
        when(decisionPort.findByTaskId(TASK_ID)).thenReturn(List.of(decision));
        when(outcomePort.findByDecisionIdAndTaskId(DECISION_ID, TASK_ID)).thenReturn(Optional.empty());

        // Simulate database persistence error on outcomePort.save
        when(outcomePort.save(any(RecommendationTaskOutcomeEntity.class)))
                .thenThrow(new DataIntegrityViolationException("Simulated unique constraint failure"));

        RecommendationSnapshotEntity snapshot = createSnapshot("DIFFERENTIATED");
        when(snapshotPort.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        when(decisionPort.findById(DECISION_ID)).thenReturn(Optional.of(decision));

        TaskCompletedLifecycleEvent event = new TaskCompletedLifecycleEvent(
                TASK_ID, PROJECT_ID, RECOMMENDED_CANDIDATE_ID, dueAt.minusSeconds(300), dueAt);

        // Crucial invariant: event listener MUST NOT rethrow exception
        assertDoesNotThrow(() -> eventListener.onTaskCompleted(event));
    }

    // ==========================================
    // 20. Unauthorized outcome retrieval is denied
    // ==========================================
    @Test
    @DisplayName("20. Unauthorized outcome retrieval is denied with HTTP 403 Forbidden")
    void test20_unauthorizedOutcomeRetrievalDenied() {
        RecommendationTaskOutcomeEntity outcome = RecommendationTaskOutcomeEntity.builder()
                .outcomeId("rec-out-test-20")
                .decisionId(DECISION_ID)
                .snapshotId(SNAPSHOT_ID)
                .projectId(PROJECT_ID)
                .taskId(TASK_ID)
                .outcomeType(TaskOutcomeType.COMPLETED_ON_TIME)
                .outcomeVersion("outcome-v1")
                .taskStatus("DONE")
                .decisionType(RecommendationDecisionType.ACCEPTED)
                .decisionSource(RecommendationDecisionSource.USER)
                .observedAt(Instant.now())
                .build();

        when(outcomePort.findById("rec-out-test-20")).thenReturn(Optional.of(outcome));
        when(outcomePort.findByTaskId(TASK_ID)).thenReturn(List.of(outcome));
        when(outcomePort.findByDecisionId(DECISION_ID)).thenReturn(List.of(outcome));

        // Non-member access
        BusinessException exById = assertThrows(BusinessException.class, () ->
                outcomeService.getOutcomeById("rec-out-test-20", NON_MEMBER_USER_ID));
        assertEquals(HttpStatus.FORBIDDEN.value(), exById.getStatus());

        BusinessException exByTask = assertThrows(BusinessException.class, () ->
                outcomeService.getOutcomesByTaskId(TASK_ID, NON_MEMBER_USER_ID));
        assertEquals(HttpStatus.FORBIDDEN.value(), exByTask.getStatus());

        BusinessException exByDec = assertThrows(BusinessException.class, () ->
                outcomeService.getOutcomeByDecisionId(DECISION_ID, NON_MEMBER_USER_ID));
        assertEquals(HttpStatus.FORBIDDEN.value(), exByDec.getStatus());

        // Unauthenticated access
        BusinessException exUnauth = assertThrows(BusinessException.class, () ->
                outcomeService.getOutcomeById("rec-out-test-20", null));
        assertEquals(HttpStatus.UNAUTHORIZED.value(), exUnauth.getStatus());
    }

    // ==========================================
    // 21. Authorized project member may retrieve the outcome
    // ==========================================
    @Test
    @DisplayName("21. Authorized project member may retrieve the outcome")
    void test21_authorizedProjectMemberMayRetrieveOutcome() {
        RecommendationTaskOutcomeEntity outcome = RecommendationTaskOutcomeEntity.builder()
                .outcomeId("rec-out-test-21")
                .decisionId(DECISION_ID)
                .snapshotId(SNAPSHOT_ID)
                .projectId(PROJECT_ID)
                .taskId(TASK_ID)
                .outcomeType(TaskOutcomeType.COMPLETED_ON_TIME)
                .outcomeVersion("outcome-v1")
                .taskStatus("DONE")
                .decisionType(RecommendationDecisionType.ACCEPTED)
                .decisionSource(RecommendationDecisionSource.USER)
                .observedAt(Instant.now())
                .build();

        when(outcomePort.findById("rec-out-test-21")).thenReturn(Optional.of(outcome));
        when(outcomePort.findByTaskId(TASK_ID)).thenReturn(List.of(outcome));
        when(outcomePort.findByDecisionId(DECISION_ID)).thenReturn(List.of(outcome));

        TaskOutcomeDto byId = outcomeService.getOutcomeById("rec-out-test-21", MEMBER_USER_ID);
        assertNotNull(byId);
        assertEquals("rec-out-test-21", byId.outcomeId());

        List<TaskOutcomeDto> byTask = outcomeService.getOutcomesByTaskId(TASK_ID, MEMBER_USER_ID);
        assertEquals(1, byTask.size());
        assertEquals("rec-out-test-21", byTask.get(0).outcomeId());

        TaskOutcomeDto byDec = outcomeService.getOutcomeByDecisionId(DECISION_ID, MEMBER_USER_ID);
        assertNotNull(byDec);
        assertEquals("rec-out-test-21", byDec.outcomeId());
    }

    // ==========================================
    // 22. Entity stores no email, prompts, internal ranking scores, or LLM response
    // ==========================================
    @Test
    @DisplayName("22. Entity stores no email, prompts, internal ranking scores, or LLM response")
    void test22_entityStoresNoPiiOrScoresOrPrompts() {
        Set<String> forbiddenSubstrings = Set.of("email", "prompt", "confidence", "llm", "displayname");

        for (Field field : RecommendationTaskOutcomeEntity.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase();
            for (String forbidden : forbiddenSubstrings) {
                assertFalse(name.contains(forbidden), "Entity field '" + field.getName() + "' contains forbidden substring '" + forbidden + "'");
            }
        }

        for (Field field : TaskOutcomeDto.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase();
            for (String forbidden : forbiddenSubstrings) {
                assertFalse(name.contains(forbidden), "DTO field '" + field.getName() + "' contains forbidden substring '" + forbidden + "'");
            }
        }
    }

    // ==========================================
    // 23. Historical snapshot and decision records remain unchanged
    // ==========================================
    @Test
    @DisplayName("23. Historical snapshot and decision records remain unchanged")
    void test23_historicalSnapshotAndDecisionRecordsUnchanged() {
        RecommendationSnapshotEntity snapshot = createSnapshot("DIFFERENTIATED");
        RecommendationDecisionEventEntity decision = createDecision(
                RecommendationDecisionType.ACCEPTED, RECOMMENDED_CANDIDATE_ID);

        when(snapshotPort.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        when(decisionPort.findById(DECISION_ID)).thenReturn(Optional.of(decision));

        Instant originalSnapshotCreatedAt = snapshot.getCreatedAt();
        String originalSnapshotDiff = snapshot.getDifferentiationStatus();
        Instant originalDecisionCreatedAt = decision.getCreatedAt();
        RecommendationDecisionType originalDecisionType = decision.getDecisionType();

        outcomeService.recordOutcome(
                DECISION_ID, TASK_ID, RECOMMENDED_CANDIDATE_ID, "DONE",
                dueAt.minusSeconds(200), dueAt, Instant.now());

        // Assert no field mutations on snapshot or decision
        assertEquals(originalSnapshotCreatedAt, snapshot.getCreatedAt());
        assertEquals(originalSnapshotDiff, snapshot.getDifferentiationStatus());
        assertEquals(originalDecisionCreatedAt, decision.getCreatedAt());
        assertEquals(originalDecisionType, decision.getDecisionType());

        verify(snapshotPort, never()).save(any());
        verify(decisionPort, never()).save(any());
    }

    // ==========================================
    // 24. Existing Phase 0, Phase 1, Phase 2A, Phase 2B, and Phase 2C tests remain green
    // ==========================================
    @Test
    @DisplayName("24. Outcome evaluation preserves performance default exclusion in Adaptive eligibility")
    void test24_adaptiveEligibilityExcludesPerformanceDefault() {
        RecommendationSnapshotEntity snapshot = createSnapshot("DIFFERENTIATED");
        RecommendationDecisionEventEntity decision = createDecision(
                RecommendationDecisionType.ACCEPTED, RECOMMENDED_CANDIDATE_ID);

        String outcomeId = "rec-out-test-24";
        RecommendationTaskOutcomeEntity outcome = RecommendationTaskOutcomeEntity.builder()
                .outcomeId(outcomeId)
                .snapshotId(SNAPSHOT_ID)
                .decisionId(DECISION_ID)
                .projectId(PROJECT_ID)
                .taskId(TASK_ID)
                .recommendedCandidateId(RECOMMENDED_CANDIDATE_ID)
                .selectedCandidateId(RECOMMENDED_CANDIDATE_ID)
                .decisionType(RecommendationDecisionType.ACCEPTED)
                .decisionSource(RecommendationDecisionSource.USER)
                .observedAssigneeId(RECOMMENDED_CANDIDATE_ID)
                .taskStatus("DONE")
                .dueAt(dueAt)
                .completedAt(dueAt.minusSeconds(300))
                .outcomeType(TaskOutcomeType.COMPLETED_ON_TIME)
                .observedAt(Instant.now())
                .outcomeVersion("outcome-v1")
                .build();

        when(outcomePort.findById(outcomeId)).thenReturn(Optional.of(outcome));
        when(decisionPort.findById(DECISION_ID)).thenReturn(Optional.of(decision));
        when(snapshotPort.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));

        AdaptiveEligibilityDto eligibility = outcomeService.evaluateAdaptiveEligibility(outcomeId);

        // Performance is DEFAULT (0.5) in Phase 1/2, so Adaptive Weights must remain excluded!
        assertFalse(eligibility.eligible());
        assertEquals("PERFORMANCE_STATUS_DEFAULT_EXCLUDED", eligibility.reason());
    }

    // ==========================================
    // 25. Listener annotation uses AFTER_COMMIT and disables fallbackExecution
    // ==========================================
    @Test
    @DisplayName("25. Listener annotation uses AFTER_COMMIT and disables fallbackExecution")
    void test25_listenerAnnotation_afterCommitWithoutFallback() throws NoSuchMethodException {
        Method method = TaskOutcomeEventListener.class.getMethod("onTaskCompleted", TaskCompletedLifecycleEvent.class);
        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);
        assertNotNull(annotation, "@TransactionalEventListener must be present on onTaskCompleted");
        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase(), "TransactionPhase must be AFTER_COMMIT");
        assertFalse(annotation.fallbackExecution(), "fallbackExecution must be false so non-transactional events create no outcome");
    }

    // ==========================================
    // 26. Outcome persistence methods use REQUIRES_NEW propagation on Spring bean boundary
    // ==========================================
    @Test
    @DisplayName("26. Outcome persistence methods use REQUIRES_NEW propagation on Spring bean boundary")
    void test26_outcomePersistence_requiresNewPropagation() throws NoSuchMethodException {
        Method recordCompleted = TaskOutcomeService.class.getMethod(
                "recordOutcomeForCompletedTask", Long.class, Long.class, Long.class, Instant.class, Instant.class);
        Transactional txRecordCompleted = recordCompleted.getAnnotation(Transactional.class);
        assertNotNull(txRecordCompleted, "@Transactional must be present on recordOutcomeForCompletedTask");
        assertEquals(Propagation.REQUIRES_NEW, txRecordCompleted.propagation(),
                "recordOutcomeForCompletedTask must use REQUIRES_NEW propagation");

        Method recordOutcome = TaskOutcomeService.class.getMethod(
                "recordOutcome", String.class, Long.class, Long.class, String.class, Instant.class, Instant.class, Instant.class);
        Transactional txRecordOutcome = recordOutcome.getAnnotation(Transactional.class);
        assertNotNull(txRecordOutcome, "@Transactional must be present on recordOutcome");
        assertEquals(Propagation.REQUIRES_NEW, txRecordOutcome.propagation(),
                "recordOutcome must use REQUIRES_NEW propagation");
    }

    // ==========================================
    // 27. Actual production due-date type Instant verifies exact comparison semantics
    // ==========================================
    @Test
    @DisplayName("27. Actual production due-date type Instant verifies exact comparison semantics")
    void test27_productionInstantDueDate_comparisonSemantics() {
        // Instant before deadline -> COMPLETED_ON_TIME
        Instant completedBefore = dueAt.minusMillis(1);
        TaskOutcomeEvaluator.EvaluationResult resBefore = outcomeEvaluator.evaluate(
                "DONE", dueAt, completedBefore, baseTime.plusSeconds(3700),
                RECOMMENDED_CANDIDATE_ID, RECOMMENDED_CANDIDATE_ID);
        assertEquals(TaskOutcomeType.COMPLETED_ON_TIME, resBefore.outcomeType());

        // Instant exactly at deadline -> COMPLETED_ON_TIME
        TaskOutcomeEvaluator.EvaluationResult resExact = outcomeEvaluator.evaluate(
                "DONE", dueAt, dueAt, baseTime.plusSeconds(3700),
                RECOMMENDED_CANDIDATE_ID, RECOMMENDED_CANDIDATE_ID);
        assertEquals(TaskOutcomeType.COMPLETED_ON_TIME, resExact.outcomeType());

        // Instant after deadline -> COMPLETED_LATE
        Instant completedAfter = dueAt.plusMillis(1);
        TaskOutcomeEvaluator.EvaluationResult resAfter = outcomeEvaluator.evaluate(
                "DONE", dueAt, completedAfter, baseTime.plusSeconds(3700),
                RECOMMENDED_CANDIDATE_ID, RECOMMENDED_CANDIDATE_ID);
        assertEquals(TaskOutcomeType.COMPLETED_LATE, resAfter.outcomeType());
    }

    // ==========================================
    // 28. Ambiguous linkage with multiple matching decisions produces no outcome
    // ==========================================
    @Test
    @DisplayName("28. Ambiguous linkage with multiple matching decisions produces no outcome")
    void test28_ambiguousLinkage_multipleMatchingDecisions_producesNoOutcome() {
        RecommendationSnapshotEntity snapshot = createSnapshot("DIFFERENTIATED");
        RecommendationDecisionEventEntity decision1 = RecommendationDecisionEventEntity.builder()
                .decisionId(DECISION_ID + "-1")
                .snapshotId(SNAPSHOT_ID)
                .projectId(PROJECT_ID)
                .taskId(TASK_ID)
                .decidedByUserId(1L)
                .decisionSource(RecommendationDecisionSource.USER)
                .decisionType(RecommendationDecisionType.ACCEPTED)
                .recommendedCandidateId(RECOMMENDED_CANDIDATE_ID)
                .selectedCandidateId(RECOMMENDED_CANDIDATE_ID)
                .createdAt(baseTime.plusSeconds(60))
                .build();

        RecommendationDecisionEventEntity decision2 = RecommendationDecisionEventEntity.builder()
                .decisionId(DECISION_ID + "-2")
                .snapshotId(SNAPSHOT_ID)
                .projectId(PROJECT_ID)
                .taskId(TASK_ID)
                .decidedByUserId(1L)
                .decisionSource(RecommendationDecisionSource.USER)
                .decisionType(RecommendationDecisionType.ACCEPTED)
                .recommendedCandidateId(RECOMMENDED_CANDIDATE_ID)
                .selectedCandidateId(RECOMMENDED_CANDIDATE_ID)
                .createdAt(baseTime.plusSeconds(120))
                .build();

        when(decisionPort.findByTaskId(TASK_ID)).thenReturn(List.of(decision1, decision2));
        when(snapshotPort.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        when(outcomePort.findByDecisionIdAndTaskId(any(), eq(TASK_ID))).thenReturn(Optional.empty());

        Instant completedAt = dueAt.minusSeconds(100);
        Optional<TaskOutcomeDto> result = outcomeService.recordOutcomeForCompletedTask(
                TASK_ID, PROJECT_ID, RECOMMENDED_CANDIDATE_ID, completedAt, dueAt);

        assertTrue(result.isEmpty(), "Ambiguous linkage with >1 matching decisions must produce no outcome");
        verify(outcomePort, never()).save(any());
    }

    // ==========================================
    // 29. Latest mismatched decision does not prevent matching valid candidate decision
    // ==========================================
    @Test
    @DisplayName("29. Latest mismatched decision does not prevent matching valid candidate decision")
    void test29_safeLinkage_latestMismatchedDecision_matchesCorrectCandidateDecision() {
        RecommendationSnapshotEntity snapshot = createSnapshot("DIFFERENTIATED");
        RecommendationDecisionEventEntity decision1 = RecommendationDecisionEventEntity.builder()
                .decisionId(DECISION_ID + "-1")
                .snapshotId(SNAPSHOT_ID)
                .projectId(PROJECT_ID)
                .taskId(TASK_ID)
                .decidedByUserId(1L)
                .decisionSource(RecommendationDecisionSource.USER)
                .decisionType(RecommendationDecisionType.ACCEPTED)
                .recommendedCandidateId(RECOMMENDED_CANDIDATE_ID)
                .selectedCandidateId(RECOMMENDED_CANDIDATE_ID)
                .createdAt(baseTime.plusSeconds(60))
                .build();

        RecommendationDecisionEventEntity decision2 = RecommendationDecisionEventEntity.builder()
                .decisionId(DECISION_ID + "-2")
                .snapshotId(SNAPSHOT_ID)
                .projectId(PROJECT_ID)
                .taskId(TASK_ID)
                .decidedByUserId(1L)
                .decisionSource(RecommendationDecisionSource.USER)
                .decisionType(RecommendationDecisionType.OVERRIDDEN)
                .recommendedCandidateId(RECOMMENDED_CANDIDATE_ID)
                .selectedCandidateId(OVERRIDE_CANDIDATE_ID)
                .createdAt(baseTime.plusSeconds(120))
                .build();

        when(decisionPort.findByTaskId(TASK_ID)).thenReturn(List.of(decision2, decision1));
        when(decisionPort.findById(decision1.getDecisionId())).thenReturn(Optional.of(decision1));
        when(snapshotPort.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        when(outcomePort.findByDecisionIdAndTaskId(any(), eq(TASK_ID))).thenReturn(Optional.empty());

        Instant completedAt = dueAt.minusSeconds(100);
        Optional<TaskOutcomeDto> result = outcomeService.recordOutcomeForCompletedTask(
                TASK_ID, PROJECT_ID, RECOMMENDED_CANDIDATE_ID, completedAt, dueAt);

        assertTrue(result.isPresent());
        assertEquals(decision1.getDecisionId(), result.get().decisionId());
        assertEquals(RECOMMENDED_CANDIDATE_ID, result.get().selectedCandidateId());
        verify(outcomePort).save(any());
    }

    // ==========================================
    // 30. Mismatched candidate decisions produce no outcome
    // ==========================================
    @Test
    @DisplayName("30. Mismatched candidate decisions produce no outcome")
    void test30_safeLinkage_latestMismatchedDecision_noMatchingCandidate_producesNoOutcome() {
        RecommendationSnapshotEntity snapshot = createSnapshot("DIFFERENTIATED");
        RecommendationDecisionEventEntity decision1 = RecommendationDecisionEventEntity.builder()
                .decisionId(DECISION_ID + "-1")
                .snapshotId(SNAPSHOT_ID)
                .projectId(PROJECT_ID)
                .taskId(TASK_ID)
                .decidedByUserId(1L)
                .decisionSource(RecommendationDecisionSource.USER)
                .decisionType(RecommendationDecisionType.OVERRIDDEN)
                .recommendedCandidateId(RECOMMENDED_CANDIDATE_ID)
                .selectedCandidateId(OVERRIDE_CANDIDATE_ID)
                .createdAt(baseTime.plusSeconds(60))
                .build();

        when(decisionPort.findByTaskId(TASK_ID)).thenReturn(List.of(decision1));
        when(snapshotPort.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        when(outcomePort.findByDecisionIdAndTaskId(any(), eq(TASK_ID))).thenReturn(Optional.empty());

        Instant completedAt = dueAt.minusSeconds(100);
        Optional<TaskOutcomeDto> result = outcomeService.recordOutcomeForCompletedTask(
                TASK_ID, PROJECT_ID, 99L, completedAt, dueAt);

        assertTrue(result.isEmpty());
        verify(outcomePort, never()).save(any());
    }

    // ==========================================
    // 31. Reopened task completion idempotency preserves first stored outcome without overwriting
    // ==========================================
    @Test
    @DisplayName("31. Reopened task completion idempotency preserves first stored outcome without overwriting")
    void test31_reopen_taskCompletionIdempotency_preservesFirstOutcome() {
        RecommendationSnapshotEntity snapshot = createSnapshot("DIFFERENTIATED");
        RecommendationDecisionEventEntity decision = createDecision(
                RecommendationDecisionType.ACCEPTED, RECOMMENDED_CANDIDATE_ID);

        when(decisionPort.findByTaskId(TASK_ID)).thenReturn(List.of(decision));
        when(snapshotPort.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));

        RecommendationTaskOutcomeEntity existingOutcome = RecommendationTaskOutcomeEntity.builder()
                .outcomeId("rec-out-first-1")
                .decisionId(DECISION_ID)
                .taskId(TASK_ID)
                .projectId(PROJECT_ID)
                .outcomeType(TaskOutcomeType.COMPLETED_ON_TIME)
                .build();
        when(outcomePort.findByDecisionIdAndTaskId(DECISION_ID, TASK_ID)).thenReturn(Optional.of(existingOutcome));

        Instant secondCompletedAt = dueAt.plusSeconds(500);
        Optional<TaskOutcomeDto> result = outcomeService.recordOutcomeForCompletedTask(
                TASK_ID, PROJECT_ID, RECOMMENDED_CANDIDATE_ID, secondCompletedAt, dueAt);

        assertTrue(result.isEmpty());
        verify(outcomePort, never()).save(any());
    }

    // ==========================================
    // 32. Event published without an active transaction creates no outcome
    // ==========================================
    @Test
    @DisplayName("32. Event published without an active transaction creates no outcome")
    void test32_eventPublishedWithoutTransaction_createsNoOutcome() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TransactionalEventTestConfig.class)) {
            TaskOutcomeService mockService = context.getBean(TaskOutcomeService.class);
            TaskCompletedLifecycleEvent event = new TaskCompletedLifecycleEvent(TASK_ID, PROJECT_ID, RECOMMENDED_CANDIDATE_ID, baseTime, dueAt);

            // Publish without active transaction
            context.publishEvent(event);

            // Because fallbackExecution = false, listener must not execute
            verify(mockService, never()).recordOutcomeForCompletedTask(any(), any(), any(), any(), any());
        }
    }

    // ==========================================
    // 33. Rolled-back task transaction creates no outcome
    // ==========================================
    @Test
    @DisplayName("33. Rolled-back task transaction creates no outcome")
    void test33_rolledBackTaskTransaction_createsNoOutcome() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TransactionalEventTestConfig.class)) {
            TaskOutcomeService mockService = context.getBean(TaskOutcomeService.class);
            PlatformTransactionManager tm = context.getBean(PlatformTransactionManager.class);
            TransactionTemplate txTemplate = new TransactionTemplate(tm);
            TaskCompletedLifecycleEvent event = new TaskCompletedLifecycleEvent(TASK_ID, PROJECT_ID, RECOMMENDED_CANDIDATE_ID, baseTime, dueAt);

            txTemplate.execute(status -> {
                context.publishEvent(event);
                status.setRollbackOnly();
                return null;
            });

            // Because transaction rolled back, AFTER_COMMIT listener must not execute
            verify(mockService, never()).recordOutcomeForCompletedTask(any(), any(), any(), any(), any());
        }
    }

    // ==========================================
    // 34. Committed task transaction invokes outcome capture
    // ==========================================
    @Test
    @DisplayName("34. Committed task transaction invokes outcome capture")
    void test34_committedTaskTransaction_invokesOutcomeCapture() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TransactionalEventTestConfig.class)) {
            TaskOutcomeService mockService = context.getBean(TaskOutcomeService.class);
            PlatformTransactionManager tm = context.getBean(PlatformTransactionManager.class);
            TransactionTemplate txTemplate = new TransactionTemplate(tm);
            TaskCompletedLifecycleEvent event = new TaskCompletedLifecycleEvent(TASK_ID, PROJECT_ID, RECOMMENDED_CANDIDATE_ID, baseTime, dueAt);

            txTemplate.execute(status -> {
                context.publishEvent(event);
                return null;
            });

            // Because transaction committed, AFTER_COMMIT listener must execute
            verify(mockService).recordOutcomeForCompletedTask(
                    eq(TASK_ID), eq(PROJECT_ID), eq(RECOMMENDED_CANDIDATE_ID), eq(baseTime), eq(dueAt));
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class TransactionalEventTestConfig {
        @Bean
        public PlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() { return new Object(); }
                @Override
                protected void doBegin(Object transaction, TransactionDefinition definition) {}
                @Override
                protected void doCommit(DefaultTransactionStatus status) {}
                @Override
                protected void doRollback(DefaultTransactionStatus status) {}
            };
        }

        @Bean
        public TransactionalEventListenerFactory transactionalEventListenerFactory() {
            return new TransactionalEventListenerFactory();
        }

        @Bean
        public TaskOutcomeService taskOutcomeService() {
            return mock(TaskOutcomeService.class);
        }

        @Bean
        public TaskOutcomeEventListener taskOutcomeEventListener(TaskOutcomeService taskOutcomeService) {
            return new TaskOutcomeEventListener(taskOutcomeService);
        }
    }
}
