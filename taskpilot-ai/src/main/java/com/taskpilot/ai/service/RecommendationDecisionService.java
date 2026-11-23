package com.taskpilot.ai.service;

import com.taskpilot.ai.assignment.port.out.RecommendationDecisionEventPort;
import com.taskpilot.ai.assignment.port.out.RecommendationSnapshotPort;
import com.taskpilot.ai.dto.RecommendationDecisionResponse;
import com.taskpilot.ai.dto.RecommendationDecisionSource;
import com.taskpilot.ai.dto.RecommendationDecisionType;
import com.taskpilot.ai.entity.RecommendationDecisionEventEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotCandidateEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotEntity;
import com.taskpilot.contracts.aiquery.dto.TaskAssignmentResultDto;
import com.taskpilot.contracts.aiquery.port.out.TaskCommandPort;
import com.taskpilot.contracts.assignment.dto.TaskAssignmentContextDto;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.contracts.user.dto.AccountAuthStateDto;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.infrastructure.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RecommendationDecisionService {

    public static final int MAX_NOTE_LENGTH = 1000;

    private final RecommendationSnapshotPort snapshotPort;
    private final RecommendationDecisionEventPort decisionPort;
    private final ProjectMemberPort projectMemberPort;
    private final TaskCommandPort taskCommandPort;
    private final AccountAuthStatePort accountAuthStatePort;
    private final Clock clock;

    @Autowired
    public RecommendationDecisionService(
            RecommendationSnapshotPort snapshotPort,
            RecommendationDecisionEventPort decisionPort,
            ProjectMemberPort projectMemberPort,
            TaskCommandPort taskCommandPort,
            @Autowired(required = false) AccountAuthStatePort accountAuthStatePort,
            Optional<Clock> clock) {
        this(snapshotPort, decisionPort, projectMemberPort, taskCommandPort, accountAuthStatePort,
                clock != null ? clock.orElse(Clock.systemUTC()) : Clock.systemUTC());
    }

    public RecommendationDecisionService(
            RecommendationSnapshotPort snapshotPort,
            RecommendationDecisionEventPort decisionPort,
            ProjectMemberPort projectMemberPort,
            TaskCommandPort taskCommandPort,
            AccountAuthStatePort accountAuthStatePort,
            Clock clock) {
        this.snapshotPort = snapshotPort;
        this.decisionPort = decisionPort;
        this.projectMemberPort = projectMemberPort;
        this.taskCommandPort = taskCommandPort;
        this.accountAuthStatePort = accountAuthStatePort;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Transactional
    public RecommendationDecisionResponse recordAndApplyDecision(
            Long actorUserId,
            String snapshotId,
            String rawDecisionType,
            Long selectedCandidateId,
            Long expectedTaskId,
            Long expectedTaskVersion,
            UUID requestId,
            String reasonCode,
            String note) {
        if (actorUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Authentication required");
        }
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "snapshotId is required");
        }
        if (requestId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "requestId / Idempotency-Key is required");
        }
        RecommendationDecisionType decisionType;
        try {
            decisionType = RecommendationDecisionType.fromInput(rawDecisionType);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), ex.getMessage());
        }
        requireActiveAccount(actorUserId);

        String idempotencyKey = requestId.toString();
        Optional<RecommendationDecisionEventEntity> replayOpt =
                decisionPort.findByDecidedByUserIdAndIdempotencyKey(actorUserId, idempotencyKey);
        if (replayOpt.isPresent()) {
            return handleIdempotentReplay(replayOpt.get(), snapshotId, decisionType, selectedCandidateId, expectedTaskId);
        }

        RecommendationSnapshotEntity snapshot = loadAndAuthorizeSnapshot(snapshotId, actorUserId);
        if (expectedTaskId != null && snapshot.getTaskId() != null
                && !Objects.equals(expectedTaskId, snapshot.getTaskId())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Snapshot is bound to task " + snapshot.getTaskId() + ", not task " + expectedTaskId);
        }

        if (decisionPort.findBySnapshotId(snapshotId).isPresent()) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "A decision has already been recorded for snapshot " + snapshotId);
        }

        return switch (decisionType) {
            case ACCEPTED, OVERRIDDEN -> executeAssignmentDecision(
                    actorUserId, snapshot, decisionType, selectedCandidateId,
                    expectedTaskId, expectedTaskVersion, requestId, idempotencyKey, reasonCode, note, true);
            case REJECTED, CANCELED -> recordNonAssignmentDecision(
                    actorUserId, snapshot, decisionType, selectedCandidateId,
                    expectedTaskId, expectedTaskVersion, idempotencyKey, reasonCode, note);
            case EXPIRED -> throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "EXPIRED decisions cannot be submitted directly");
        };
    }

    @Transactional
    public RecommendationDecisionResponse commitToolAcceptedAssignment(
            Long actorUserId,
            String snapshotId,
            Long taskId,
            Long selectedCandidateId,
            Long expectedTaskVersion,
            UUID assignRequestId,
            String reason,
            boolean taskVersionBumpedBySkillUpdate) {
        if (actorUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Authentication required");
        }
        if (assignRequestId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "assignRequestId is required");
        }
        requireActiveAccount(actorUserId);
        String idempotencyKey = assignRequestId.toString();
        Optional<RecommendationDecisionEventEntity> replayOpt =
                decisionPort.findByDecidedByUserIdAndIdempotencyKey(actorUserId, idempotencyKey);
        if (replayOpt.isPresent()) {
            return handleIdempotentReplay(replayOpt.get(), snapshotId, RecommendationDecisionType.ACCEPTED,
                    selectedCandidateId, taskId);
        }

        RecommendationSnapshotEntity snapshot = loadAndAuthorizeSnapshot(snapshotId, actorUserId);
        if (snapshot.getTaskId() == null || !Objects.equals(snapshot.getTaskId(), taskId)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Recommendation snapshot is not bound to task " + taskId);
        }
        if (decisionPort.findBySnapshotId(snapshotId).isPresent()) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "A decision has already been recorded for snapshot " + snapshotId);
        }
        return executeAssignmentDecision(
                actorUserId, snapshot, RecommendationDecisionType.ACCEPTED, selectedCandidateId,
                taskId, expectedTaskVersion, assignRequestId, idempotencyKey,
                "AI_RECOMMEND_AND_ASSIGN", reason, !taskVersionBumpedBySkillUpdate);
    }

    @Transactional
    public void recordRejectedIfUndecided(Long actorUserId, String snapshotId, String reasonCode, String note) {
        if (actorUserId == null || snapshotId == null || snapshotId.isBlank()) {
            return;
        }
        if (decisionPort.findBySnapshotId(snapshotId).isPresent()) {
            return;
        }
        Optional<RecommendationSnapshotEntity> snapOpt = snapshotPort.findById(snapshotId);
        if (snapOpt.isEmpty()) {
            return;
        }
        RecommendationSnapshotEntity snapshot = snapOpt.get();
        if (!projectMemberPort.isProjectManager(snapshot.getProjectId(), actorUserId)) {
            return;
        }
        recordNonAssignmentDecision(actorUserId, snapshot, RecommendationDecisionType.REJECTED,
                null, snapshot.getTaskId(), null, null, reasonCode, note);
    }

    @Transactional(readOnly = true)
    public RecommendationDecisionResponse getDecisionBySnapshotId(String snapshotId, Long requestingUserId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "snapshotId is required");
        }
        RecommendationDecisionEventEntity decision = decisionPort.findBySnapshotId(snapshotId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                        "No decision found for snapshot: " + snapshotId));
        if (requestingUserId == null || !projectMemberPort.isProjectMember(decision.getProjectId(), requestingUserId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "You are not authorized to view decisions for project " + decision.getProjectId());
        }
        return toResponse(decision, null);
    }

    private RecommendationDecisionResponse executeAssignmentDecision(
            Long actorUserId,
            RecommendationSnapshotEntity snapshot,
            RecommendationDecisionType decisionType,
            Long selectedCandidateId,
            Long expectedTaskId,
            Long expectedTaskVersion,
            UUID requestId,
            String idempotencyKey,
            String reasonCode,
            String note,
            boolean enforceSnapshotTaskVersionMatch) {

        Long taskId = snapshot.getTaskId();
        if (taskId == null) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Unbound preview snapshots cannot commit an assignment; request a task-bound recommendation");
        }
        if (expectedTaskId != null && !Objects.equals(expectedTaskId, taskId)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Snapshot task " + taskId + " does not match requested task " + expectedTaskId);
        }

        TaskAssignmentContextDto taskCtx = taskCommandPort.getTaskAssignmentContext(taskId, actorUserId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found: " + taskId));
        if (taskCtx.deleted()) {
            throw new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found: " + taskId);
        }
        if (!Objects.equals(taskCtx.projectId(), snapshot.getProjectId())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Task project " + taskCtx.projectId() + " does not match snapshot project " + snapshot.getProjectId());
        }
        if (expectedTaskVersion == null) {
            throw new BusinessException(HttpStatus.PRECONDITION_REQUIRED.value(),
                    "expectedTaskVersion is required to commit a recommendation assignment");
        }
        if (!Objects.equals(expectedTaskVersion, taskCtx.taskVersion())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Task has been modified (expected version " + expectedTaskVersion
                            + ", actual " + taskCtx.taskVersion() + "); refresh and retry");
        }
        if (enforceSnapshotTaskVersionMatch && snapshot.getTaskVersion() != null
                && !Objects.equals(snapshot.getTaskVersion(), taskCtx.taskVersion())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Task changed after recommendation snapshot (snapshot version " + snapshot.getTaskVersion()
                            + ", actual " + taskCtx.taskVersion() + "); request a new recommendation");
        }
        if (snapshot.getAssignmentVersion() != null
                && !Objects.equals(snapshot.getAssignmentVersion(), taskCtx.assignmentVersion())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Task assignment changed after recommendation snapshot; request a new recommendation");
        }

        String currentRoster = projectMemberPort.computeProjectRosterRevision(snapshot.getProjectId());
        if (snapshot.getRosterRevision() != null && !Objects.equals(currentRoster, snapshot.getRosterRevision())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Project roster changed since recommendation snapshot; request a fresh recommendation");
        }

        Long effectiveSelectedId = validateCandidateSelection(snapshot, decisionType, selectedCandidateId);
        String decisionId = "rec-dec-" + UUID.randomUUID();
        long nextAssignmentVersion = taskCtx.assignmentVersion() + 1L;
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        RecommendationDecisionEventEntity event = RecommendationDecisionEventEntity.builder()
                .decisionId(decisionId)
                .snapshotId(snapshot.getSnapshotId())
                .projectId(snapshot.getProjectId())
                .taskId(taskId)
                .decidedByUserId(actorUserId)
                .decisionSource(RecommendationDecisionSource.USER)
                .decisionType(decisionType)
                .recommendedCandidateId(snapshot.getRecommendedCandidateId())
                .selectedCandidateId(effectiveSelectedId)
                .reasonCode(trimToNull(reasonCode, 64))
                .note(trimToNull(note, MAX_NOTE_LENGTH))
                .createdAt(now)
                .taskVersion(taskCtx.taskVersion())
                .assignmentVersion(nextAssignmentVersion)
                .idempotencyKey(idempotencyKey)
                .rosterRevision(currentRoster)
                .synthetic(Boolean.FALSE)
                .build();

        RecommendationDecisionEventEntity savedDecision = persistDecision(event);

        String assignReason = (note != null && !note.isBlank())
                ? note.trim()
                : (decisionType == RecommendationDecisionType.ACCEPTED
                ? "Accepted AI assignment recommendation"
                : "Overrode AI assignment recommendation");

        TaskAssignmentResultDto assignmentResult = taskCommandPort.assignTaskToMember(
                taskId,
                effectiveSelectedId,
                assignReason,
                taskCtx.taskVersion(),
                requestId,
                actorUserId,
                false,
                savedDecision.getDecisionId());

        log.info("[DecisionEvent] Committed {} decision {} + assignment for task {} candidate {} (assignVer={})",
                decisionType, savedDecision.getDecisionId(), taskId, effectiveSelectedId, nextAssignmentVersion);
        return toResponse(savedDecision, assignmentResult);
    }

    private RecommendationDecisionResponse recordNonAssignmentDecision(
            Long actorUserId,
            RecommendationSnapshotEntity snapshot,
            RecommendationDecisionType decisionType,
            Long selectedCandidateId,
            Long expectedTaskId,
            Long expectedTaskVersion,
            String idempotencyKey,
            String reasonCode,
            String note) {
        if (selectedCandidateId != null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    decisionType + " must not include a selectedCandidateId; use OVERRIDE to select an alternative");
        }
        Long boundTaskId = snapshot.getTaskId();
        if (expectedTaskId != null && boundTaskId != null && !Objects.equals(expectedTaskId, boundTaskId)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Snapshot task " + boundTaskId + " does not match requested task " + expectedTaskId);
        }

        Long observedTaskVersion = snapshot.getTaskVersion();
        Long observedAssignmentVersion = snapshot.getAssignmentVersion();
        if (boundTaskId != null) {
            TaskAssignmentContextDto taskCtx = taskCommandPort.getTaskAssignmentContext(boundTaskId, actorUserId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                            "Task not found: " + boundTaskId));
            if (taskCtx.deleted()) {
                throw new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found: " + boundTaskId);
            }
            if (!Objects.equals(taskCtx.projectId(), snapshot.getProjectId())) {
                throw new BusinessException(HttpStatus.CONFLICT.value(), "Task project mismatch");
            }
            if (expectedTaskVersion != null && !Objects.equals(expectedTaskVersion, taskCtx.taskVersion())) {
                throw new BusinessException(HttpStatus.CONFLICT.value(),
                        "Task version is stale (expected " + expectedTaskVersion + ", actual " + taskCtx.taskVersion() + ")");
            }
            observedTaskVersion = taskCtx.taskVersion();
            observedAssignmentVersion = taskCtx.assignmentVersion();
        }

        String currentRoster = projectMemberPort.computeProjectRosterRevision(snapshot.getProjectId());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        RecommendationDecisionEventEntity event = RecommendationDecisionEventEntity.builder()
                .decisionId("rec-dec-" + UUID.randomUUID())
                .snapshotId(snapshot.getSnapshotId())
                .projectId(snapshot.getProjectId())
                .taskId(boundTaskId != null ? boundTaskId : expectedTaskId)
                .decidedByUserId(actorUserId)
                .decisionSource(RecommendationDecisionSource.USER)
                .decisionType(decisionType)
                .recommendedCandidateId(snapshot.getRecommendedCandidateId())
                .selectedCandidateId(null)
                .reasonCode(trimToNull(reasonCode, 64))
                .note(trimToNull(note, MAX_NOTE_LENGTH))
                .createdAt(now)
                .taskVersion(observedTaskVersion)
                .assignmentVersion(observedAssignmentVersion)
                .idempotencyKey(idempotencyKey)
                .rosterRevision(currentRoster)
                .synthetic(Boolean.FALSE)
                .build();

        RecommendationDecisionEventEntity saved = persistDecision(event);
        log.info("[DecisionEvent] Recorded non-assignment {} decision {} for snapshot {} project {} task {}",
                decisionType, saved.getDecisionId(), snapshot.getSnapshotId(), snapshot.getProjectId(), saved.getTaskId());
        return toResponse(saved, null);
    }

    private RecommendationSnapshotEntity loadAndAuthorizeSnapshot(String snapshotId, Long actorUserId) {
        RecommendationSnapshotEntity snapshot = snapshotPort.findById(snapshotId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                        "Recommendation snapshot not found: " + snapshotId));
        if (!projectMemberPort.isProjectManager(snapshot.getProjectId(), actorUserId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Only an active project manager can decide on recommendations for project " + snapshot.getProjectId());
        }
        return snapshot;
    }

    private void requireActiveAccount(Long actorUserId) {
        if (accountAuthStatePort == null) {
            return;
        }
        AccountAuthStateDto account = accountAuthStatePort.findById(actorUserId)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Account not found"));
        if (!"ACTIVE".equals(account.lifecycle()) || !account.emailVerified()) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Account is not active and verified for this operation");
        }
    }

    private Long validateCandidateSelection(
            RecommendationSnapshotEntity snapshot,
            RecommendationDecisionType decisionType,
            Long selectedCandidateId) {
        Set<Long> candidateIds = snapshot.getCandidates() == null
                ? Set.of()
                : snapshot.getCandidates().stream()
                        .map(RecommendationSnapshotCandidateEntity::getCandidateId)
                        .collect(Collectors.toSet());
        Long recommendedId = snapshot.getRecommendedCandidateId();

        if (decisionType == RecommendationDecisionType.ACCEPTED) {
            Long effectiveId = selectedCandidateId != null ? selectedCandidateId : recommendedId;
            if (effectiveId == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "Cannot ACCEPT snapshot with no recommended candidate");
            }
            if (!Objects.equals(effectiveId, recommendedId)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "Selected candidate " + effectiveId + " does not match recommended candidate "
                                + recommendedId + "; use OVERRIDE instead");
            }
            if (!candidateIds.contains(effectiveId)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "Candidate " + effectiveId + " is not present in snapshot " + snapshot.getSnapshotId());
            }
            return effectiveId;
        }

        // OVERRIDDEN
        if (selectedCandidateId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "selectedCandidateId is required for OVERRIDE decision");
        }
        if (!candidateIds.contains(selectedCandidateId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Override candidate " + selectedCandidateId + " is not present in snapshot " + snapshot.getSnapshotId());
        }
        if (Objects.equals(selectedCandidateId, recommendedId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Override candidate matches recommended candidate; use ACCEPT instead");
        }
        return selectedCandidateId;
    }

    private RecommendationDecisionEventEntity persistDecision(RecommendationDecisionEventEntity event) {
        try {
            return decisionPort.save(event);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Conflict: A decision has already been recorded for this snapshot or idempotency key");
        }
    }

    private RecommendationDecisionResponse handleIdempotentReplay(
            RecommendationDecisionEventEntity existing,
            String snapshotId,
            RecommendationDecisionType decisionType,
            Long selectedCandidateId,
            Long expectedTaskId) {
        Long effectiveExpectedSelected = (decisionType == RecommendationDecisionType.ACCEPTED && selectedCandidateId == null)
                ? existing.getRecommendedCandidateId()
                : selectedCandidateId;
        if (!Objects.equals(existing.getSnapshotId(), snapshotId)
                || existing.getDecisionType() != decisionType
                || !Objects.equals(existing.getSelectedCandidateId(), effectiveExpectedSelected)
                || (expectedTaskId != null && !Objects.equals(expectedTaskId, existing.getTaskId()))) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Idempotency key already used with a different decision payload");
        }
        TaskAssignmentResultDto replayAssign = (decisionType == RecommendationDecisionType.ACCEPTED
                || decisionType == RecommendationDecisionType.OVERRIDDEN)
                ? TaskAssignmentResultDto.success(existing.getTaskId(), existing.getSelectedCandidateId(),
                        "Idempotent replay of committed decision " + existing.getDecisionId())
                : null;
        return toResponse(existing, replayAssign);
    }

    private static String trimToNull(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > maxLen ? trimmed.substring(0, maxLen) : trimmed;
    }

    private static RecommendationDecisionResponse toResponse(
            RecommendationDecisionEventEntity e,
            TaskAssignmentResultDto assignmentResult) {
        return new RecommendationDecisionResponse(
                e.getDecisionId(),
                e.getSnapshotId(),
                e.getProjectId(),
                e.getTaskId(),
                e.getDecidedByUserId(),
                e.getDecisionSource(),
                e.getDecisionType(),
                e.getRecommendedCandidateId(),
                e.getSelectedCandidateId(),
                e.getTaskVersion(),
                e.getAssignmentVersion(),
                e.getReasonCode(),
                e.getNote(),
                e.getCreatedAt(),
                assignmentResult);
    }
}
