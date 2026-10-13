package com.taskpilot.ai.service;

import com.taskpilot.ai.assignment.port.out.RecommendationDecisionEventPort;
import com.taskpilot.ai.assignment.port.out.RecommendationSnapshotPort;
import com.taskpilot.ai.assignment.port.out.RecommendationTaskOutcomePort;
import com.taskpilot.ai.dto.*;
import com.taskpilot.ai.entity.RecommendationDecisionEventEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotCandidateEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotEntity;
import com.taskpilot.ai.entity.RecommendationTaskOutcomeEntity;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskOutcomeService {

    public static final String OUTCOME_VERSION_V1 = "outcome-v1";

    private final RecommendationTaskOutcomePort outcomePort;
    private final RecommendationDecisionEventPort decisionPort;
    private final RecommendationSnapshotPort snapshotPort;
    private final ProjectMemberPort projectMemberPort;
    private final TaskOutcomeEvaluator outcomeEvaluator;

    /**
     * Record outcome for a task that completed (transitioned to DONE).
     * Order of execution: task is already persisted and committed in DONE status.
     * Uses strict safe linkage:
     * - taskId
     * - projectId
     * - ACCEPTED or OVERRIDDEN
     * - USER decision source
     * - selectedCandidateId == assigneeId
     * - valid snapshot
     * - no prior outcome for (decision, task) pair
     * Zero matches -> no outcome.
     * Exactly one match -> record outcome.
     * >1 matches -> no outcome + log 'ambiguous linkage' with IDs only.
     * Runs in an isolated REQUIRES_NEW transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<TaskOutcomeDto> recordOutcomeForCompletedTask(
            Long taskId,
            Long projectId,
            Long assigneeId,
            Instant completedAt,
            Instant dueDate) {

        if (taskId == null) {
            return Optional.empty();
        }

        try {
            List<RecommendationDecisionEventEntity> decisions = decisionPort.findByTaskId(taskId);
            if (decisions == null || decisions.isEmpty()) {
                log.debug("No recommendation decision found for completed task {}", taskId);
                return Optional.empty();
            }

            List<RecommendationDecisionEventEntity> validMatchingDecisions = new ArrayList<>();

            for (RecommendationDecisionEventEntity d : decisions) {
                // 1. Task ID agreement
                if (!Objects.equals(d.getTaskId(), taskId)) {
                    continue;
                }
                // 2. Project ID agreement
                if (projectId != null && !Objects.equals(d.getProjectId(), projectId)) {
                    continue;
                }
                // 3. Terminal assignment decision: ACCEPTED or OVERRIDDEN
                if (d.getDecisionType() != RecommendationDecisionType.ACCEPTED
                        && d.getDecisionType() != RecommendationDecisionType.OVERRIDDEN) {
                    continue;
                }
                // 4. USER source
                if (d.getDecisionSource() != RecommendationDecisionSource.USER) {
                    continue;
                }
                // 5. Selected candidate matches task assignee
                if (assigneeId == null || !Objects.equals(d.getSelectedCandidateId(), assigneeId)) {
                    continue;
                }
                // 6. Valid linked snapshot
                if (d.getSnapshotId() == null || d.getSnapshotId().isBlank()) {
                    continue;
                }
                Optional<RecommendationSnapshotEntity> snapOpt = snapshotPort.findById(d.getSnapshotId());
                if (snapOpt.isEmpty()) {
                    continue;
                }
                RecommendationSnapshotEntity snap = snapOpt.get();
                if (projectId != null && !Objects.equals(snap.getProjectId(), projectId)) {
                    continue;
                }
                if (!Objects.equals(snap.getProjectId(), d.getProjectId())) {
                    continue;
                }
                if (snap.getTaskId() != null && !Objects.equals(snap.getTaskId(), taskId)) {
                    continue;
                }
                // 7. No prior outcome recorded for this (decision, task) pair
                if (outcomePort.findByDecisionIdAndTaskId(d.getDecisionId(), taskId).isPresent()) {
                    continue;
                }

                validMatchingDecisions.add(d);
            }

            // Zero matches -> no outcome
            if (validMatchingDecisions.isEmpty()) {
                log.debug("No valid matching decision found for taskId={}", taskId);
                return Optional.empty();
            }

            // >1 matches -> no outcome + log 'ambiguous linkage' with IDs only
            if (validMatchingDecisions.size() > 1) {
                List<String> ambiguousDecisionIds = validMatchingDecisions.stream()
                        .map(RecommendationDecisionEventEntity::getDecisionId)
                        .toList();
                log.warn("TD-P2D-AMBIGUOUS-LINKAGE: Ambiguous linkage for taskId={} projectId={}. Multiple matching decisions: {}",
                        taskId, projectId, ambiguousDecisionIds);
                return Optional.empty();
            }

            // Exactly one match -> record outcome
            RecommendationDecisionEventEntity decision = validMatchingDecisions.get(0);

            TaskOutcomeDto recorded = recordOutcome(
                    decision.getDecisionId(),
                    taskId,
                    assigneeId,
                    "DONE",
                    completedAt,
                    dueDate,
                    completedAt != null ? completedAt : Instant.now()
            );
            return Optional.of(recorded);

        } catch (BusinessException be) {
            log.error("TD-P2D-OUTCOME-PERSISTENCE-AFTER-TASK-COMPLETION: Business exception recording outcome for task {}: {}",
                    taskId, be.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.error("TD-P2D-OUTCOME-PERSISTENCE-AFTER-TASK-COMPLETION: Unexpected exception recording outcome for task {}: {}",
                    taskId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Records a task outcome explicitly linking a decision, task, and observed state.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TaskOutcomeDto recordOutcome(
            String decisionId,
            Long taskId,
            Long observedAssigneeId,
            String taskStatus,
            Instant completedAt,
            Instant dueAt,
            Instant observedAt) {

        if (decisionId == null || decisionId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Decision ID is required");
        }
        if (taskId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Task ID is required");
        }

        RecommendationDecisionEventEntity decision = decisionPort.findById(decisionId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                        "Recommendation decision not found: " + decisionId));

        // Strict linkage verification:
        // 1. Task ID agreement
        if (decision.getTaskId() != null && !decision.getTaskId().equals(taskId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Decision task ID " + decision.getTaskId() + " does not match requested task ID " + taskId);
        }

        // 2. Snapshot existence and linkage
        RecommendationSnapshotEntity snapshot = snapshotPort.findById(decision.getSnapshotId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                        "Linked recommendation snapshot not found: " + decision.getSnapshotId()));

        if (!Objects.equals(decision.getProjectId(), snapshot.getProjectId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Decision project ID " + decision.getProjectId() + " does not match snapshot project ID " + snapshot.getProjectId());
        }

        if (snapshot.getTaskId() != null && !snapshot.getTaskId().equals(taskId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Snapshot task ID " + snapshot.getTaskId() + " does not match requested task ID " + taskId);
        }

        // 3. Decision type verification: Non-assignment decisions produce NO outcome
        if (decision.getDecisionType() == RecommendationDecisionType.REJECTED
                || decision.getDecisionType() == RecommendationDecisionType.CANCELED
                || decision.getDecisionType() == RecommendationDecisionType.EXPIRED) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Non-assignment decision " + decision.getDecisionType() + " cannot produce a task outcome");
        }

        // 4. Duplicate prevention
        if (outcomePort.findByDecisionIdAndTaskId(decisionId, taskId).isPresent()) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Conflict: An outcome record has already been recorded for decision " + decisionId + " and task " + taskId);
        }

        Instant effectiveObservedAt = observedAt != null ? observedAt : Instant.now();

        // 5. Evaluate outcome
        TaskOutcomeEvaluator.EvaluationResult evalResult = outcomeEvaluator.evaluate(
                taskStatus,
                dueAt,
                completedAt,
                effectiveObservedAt,
                observedAssigneeId,
                decision.getSelectedCandidateId()
        );

        String outcomeId = "rec-out-" + UUID.randomUUID();
        RecommendationTaskOutcomeEntity outcomeEntity = RecommendationTaskOutcomeEntity.builder()
                .outcomeId(outcomeId)
                .snapshotId(snapshot.getSnapshotId())
                .decisionId(decision.getDecisionId())
                .projectId(decision.getProjectId())
                .taskId(taskId)
                .recommendedCandidateId(decision.getRecommendedCandidateId())
                .selectedCandidateId(decision.getSelectedCandidateId())
                .decisionType(decision.getDecisionType())
                .decisionSource(decision.getDecisionSource())
                .observedAssigneeId(observedAssigneeId)
                .taskStatus(taskStatus != null ? taskStatus : "UNKNOWN")
                .dueAt(dueAt)
                .completedAt(completedAt)
                .outcomeType(evalResult.outcomeType())
                .exclusionReason(evalResult.exclusionReason())
                .observedAt(effectiveObservedAt)
                .outcomeVersion(OUTCOME_VERSION_V1)
                .build();

        RecommendationTaskOutcomeEntity saved;
        try {
            saved = outcomePort.save(outcomeEntity);
        } catch (DataIntegrityViolationException dive) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Conflict: Duplicate outcome record or persistence constraint violation for decision " + decisionId + " and task " + taskId);
        }

        log.info("[TaskOutcome] Recorded outcome {} ({}) for task {} decision {} snapshot {}",
                outcomeId, evalResult.outcomeType(), taskId, decision.getDecisionId(), snapshot.getSnapshotId());

        return mapToDto(saved);
    }

    /**
     * Read-only outcome retrieval by task ID with project membership check.
     */
    @Transactional(readOnly = true)
    public List<TaskOutcomeDto> getOutcomesByTaskId(Long taskId, Long requestingUserId) {
        if (taskId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Task ID is required");
        }
        if (requestingUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Authenticated user ID is required");
        }
        List<RecommendationTaskOutcomeEntity> outcomes = outcomePort.findByTaskId(taskId);
        if (outcomes.isEmpty()) {
            return List.of();
        }
        Long projectId = outcomes.get(0).getProjectId();
        if (!projectMemberPort.isProjectMember(projectId, requestingUserId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "User " + requestingUserId + " is not authorized to view outcomes for project " + projectId);
        }
        return outcomes.stream().map(this::mapToDto).toList();
    }

    /**
     * Read-only outcome retrieval by decision ID with project membership check.
     */
    @Transactional(readOnly = true)
    public TaskOutcomeDto getOutcomeByDecisionId(String decisionId, Long requestingUserId) {
        if (decisionId == null || decisionId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Decision ID is required");
        }
        if (requestingUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Authenticated user ID is required");
        }
        List<RecommendationTaskOutcomeEntity> outcomes = outcomePort.findByDecisionId(decisionId);
        if (outcomes.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND.value(),
                    "Outcome not found for decision: " + decisionId);
        }
        RecommendationTaskOutcomeEntity outcome = outcomes.get(0);
        if (!projectMemberPort.isProjectMember(outcome.getProjectId(), requestingUserId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "User " + requestingUserId + " is not authorized to view outcomes for project " + outcome.getProjectId());
        }
        return mapToDto(outcome);
    }

    /**
     * Read-only outcome retrieval by outcome ID with project membership check.
     */
    @Transactional(readOnly = true)
    public TaskOutcomeDto getOutcomeById(String outcomeId, Long requestingUserId) {
        if (outcomeId == null || outcomeId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Outcome ID is required");
        }
        if (requestingUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Authenticated user ID is required");
        }
        RecommendationTaskOutcomeEntity outcome = outcomePort.findById(outcomeId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                        "Outcome not found: " + outcomeId));
        if (!projectMemberPort.isProjectMember(outcome.getProjectId(), requestingUserId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "User " + requestingUserId + " is not authorized to view outcomes for project " + outcome.getProjectId());
        }
        return mapToDto(outcome);
    }

    /**
     * Evaluates Adaptive eligibility metadata for Phase 2D/Phase 2E.
     * Does NOT calculate weights or modify models.
     */
    @Transactional(readOnly = true)
    public AdaptiveEligibilityDto evaluateAdaptiveEligibility(String outcomeId) {
        if (outcomeId == null || outcomeId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Outcome ID is required");
        }
        RecommendationTaskOutcomeEntity outcome = outcomePort.findById(outcomeId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                        "Outcome not found: " + outcomeId));

        RecommendationDecisionEventEntity decision = decisionPort.findById(outcome.getDecisionId()).orElse(null);
        RecommendationSnapshotEntity snapshot = snapshotPort.findById(outcome.getSnapshotId()).orElse(null);

        boolean validLinkage = snapshot != null && decision != null
                && Objects.equals(snapshot.getSnapshotId(), decision.getSnapshotId())
                && Objects.equals(snapshot.getProjectId(), decision.getProjectId())
                && Objects.equals(decision.getProjectId(), outcome.getProjectId())
                && (decision.getTaskId() == null || Objects.equals(decision.getTaskId(), outcome.getTaskId()));

        boolean hasCompletionTimestamp = outcome.getCompletedAt() != null;
        boolean hasDueDate = outcome.getDueAt() != null;

        String snapshotVersion = snapshot != null ? snapshot.getPresentationContractVersion() : null;
        RecommendationDecisionType decisionType = outcome.getDecisionType();
        RecommendationDecisionSource decisionSource = outcome.getDecisionSource();

        // Find candidate evidence from snapshot
        RecommendationSnapshotCandidateEntity candidate = null;
        if (snapshot != null && snapshot.getCandidates() != null && outcome.getSelectedCandidateId() != null) {
            candidate = snapshot.getCandidates().stream()
                    .filter(c -> Objects.equals(c.getCandidateId(), outcome.getSelectedCandidateId()))
                    .findFirst()
                    .orElse(null);
        }

        String workloadStatus = candidate != null && candidate.getWorkloadStatus() != null
                ? candidate.getWorkloadStatus().name() : null;
        String workloadUnit = candidate != null ? candidate.getWorkloadUnit() : null;
        String workloadScope = candidate != null ? candidate.getWorkloadScope() : null;
        String performanceStatus = candidate != null && candidate.getPerformanceStatus() != null
                ? candidate.getPerformanceStatus().name() : null;

        // Check eligibility constraints (Section 9):
        // 1. Valid snapshot
        if (snapshot == null) {
            return new AdaptiveEligibilityDto(false, "MISSING_SNAPSHOT",
                    outcome.getSnapshotId(), snapshotVersion, decisionType, decisionSource,
                    workloadStatus, workloadUnit, workloadScope, performanceStatus,
                    outcome.getOutcomeType(), hasCompletionTimestamp, hasDueDate, false);
        }

        // 2. UNOBSERVED snapshot creates no learning outcome
        if ("UNOBSERVED".equalsIgnoreCase(snapshot.getDifferentiationStatus())) {
            return new AdaptiveEligibilityDto(false, "UNOBSERVED_SNAPSHOT_EXCLUDED",
                    outcome.getSnapshotId(), snapshotVersion, decisionType, decisionSource,
                    workloadStatus, workloadUnit, workloadScope, performanceStatus,
                    outcome.getOutcomeType(), hasCompletionTimestamp, hasDueDate, validLinkage);
        }

        // 3. Valid linkage
        if (!validLinkage) {
            return new AdaptiveEligibilityDto(false, "INVALID_LINKAGE",
                    outcome.getSnapshotId(), snapshotVersion, decisionType, decisionSource,
                    workloadStatus, workloadUnit, workloadScope, performanceStatus,
                    outcome.getOutcomeType(), hasCompletionTimestamp, hasDueDate, false);
        }

        // 4. Valid terminal USER decision (ACCEPTED or OVERRIDDEN)
        if (decisionSource != RecommendationDecisionSource.USER) {
            return new AdaptiveEligibilityDto(false, "NON_USER_DECISION_EXCLUDED",
                    outcome.getSnapshotId(), snapshotVersion, decisionType, decisionSource,
                    workloadStatus, workloadUnit, workloadScope, performanceStatus,
                    outcome.getOutcomeType(), hasCompletionTimestamp, hasDueDate, validLinkage);
        }

        if (decisionType != RecommendationDecisionType.ACCEPTED && decisionType != RecommendationDecisionType.OVERRIDDEN) {
            return new AdaptiveEligibilityDto(false, "NON_ASSIGNMENT_DECISION_EXCLUDED",
                    outcome.getSnapshotId(), snapshotVersion, decisionType, decisionSource,
                    workloadStatus, workloadUnit, workloadScope, performanceStatus,
                    outcome.getOutcomeType(), hasCompletionTimestamp, hasDueDate, validLinkage);
        }

        // 5. Valid selected candidate present in snapshot
        if (candidate == null) {
            return new AdaptiveEligibilityDto(false, "SELECTED_CANDIDATE_NOT_FOUND_IN_SNAPSHOT",
                    outcome.getSnapshotId(), snapshotVersion, decisionType, decisionSource,
                    workloadStatus, workloadUnit, workloadScope, performanceStatus,
                    outcome.getOutcomeType(), hasCompletionTimestamp, hasDueDate, validLinkage);
        }

        // 6. Measured Skill Fit
        if (candidate.getFitStatus() != MetricDataStatus.MEASURED) {
            return new AdaptiveEligibilityDto(false, "FIT_STATUS_NOT_MEASURED",
                    outcome.getSnapshotId(), snapshotVersion, decisionType, decisionSource,
                    workloadStatus, workloadUnit, workloadScope, performanceStatus,
                    outcome.getOutcomeType(), hasCompletionTimestamp, hasDueDate, validLinkage);
        }

        // 7. Measured ACTIVE_TASK_COUNT workload
        if (candidate.getWorkloadStatus() != MetricDataStatus.MEASURED
                || !"ACTIVE_TASK_COUNT".equalsIgnoreCase(candidate.getWorkloadUnit())) {
            return new AdaptiveEligibilityDto(false, "WORKLOAD_NOT_MEASURED_ACTIVE_TASK_COUNT",
                    outcome.getSnapshotId(), snapshotVersion, decisionType, decisionSource,
                    workloadStatus, workloadUnit, workloadScope, performanceStatus,
                    outcome.getOutcomeType(), hasCompletionTimestamp, hasDueDate, validLinkage);
        }

        // 8. Performance DEFAULT remains excluded
        if (candidate.getPerformanceStatus() == MetricDataStatus.DEFAULT
                || "DEFAULT".equalsIgnoreCase(performanceStatus)) {
            return new AdaptiveEligibilityDto(false, "PERFORMANCE_STATUS_DEFAULT_EXCLUDED",
                    outcome.getSnapshotId(), snapshotVersion, decisionType, decisionSource,
                    workloadStatus, workloadUnit, workloadScope, performanceStatus,
                    outcome.getOutcomeType(), hasCompletionTimestamp, hasDueDate, validLinkage);
        }

        // 9. Final observable task outcome (COMPLETED_ON_TIME or COMPLETED_LATE)
        if (outcome.getOutcomeType() == TaskOutcomeType.EXCLUDED) {
            return new AdaptiveEligibilityDto(false, "OUTCOME_EXCLUDED: " + outcome.getExclusionReason(),
                    outcome.getSnapshotId(), snapshotVersion, decisionType, decisionSource,
                    workloadStatus, workloadUnit, workloadScope, performanceStatus,
                    outcome.getOutcomeType(), hasCompletionTimestamp, hasDueDate, validLinkage);
        }

        if (outcome.getOutcomeType() == TaskOutcomeType.NOT_YET_OBSERVABLE) {
            return new AdaptiveEligibilityDto(false, "OUTCOME_NOT_YET_OBSERVABLE",
                    outcome.getSnapshotId(), snapshotVersion, decisionType, decisionSource,
                    workloadStatus, workloadUnit, workloadScope, performanceStatus,
                    outcome.getOutcomeType(), hasCompletionTimestamp, hasDueDate, validLinkage);
        }

        // All criteria satisfied: eligible
        return new AdaptiveEligibilityDto(true, "ELIGIBLE",
                outcome.getSnapshotId(), snapshotVersion, decisionType, decisionSource,
                workloadStatus, workloadUnit, workloadScope, performanceStatus,
                outcome.getOutcomeType(), hasCompletionTimestamp, hasDueDate, validLinkage);
    }

    private TaskOutcomeDto mapToDto(RecommendationTaskOutcomeEntity entity) {
        return new TaskOutcomeDto(
                entity.getOutcomeId(),
                entity.getSnapshotId(),
                entity.getDecisionId(),
                entity.getProjectId(),
                entity.getTaskId(),
                entity.getRecommendedCandidateId(),
                entity.getSelectedCandidateId(),
                entity.getDecisionType(),
                entity.getDecisionSource(),
                entity.getObservedAssigneeId(),
                entity.getTaskStatus(),
                entity.getDueAt(),
                entity.getCompletedAt(),
                entity.getOutcomeType(),
                entity.getExclusionReason(),
                entity.getObservedAt(),
                entity.getOutcomeVersion()
        );
    }
}
