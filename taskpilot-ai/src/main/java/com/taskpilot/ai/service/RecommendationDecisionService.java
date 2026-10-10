package com.taskpilot.ai.service;

import com.taskpilot.ai.assignment.port.out.RecommendationDecisionEventPort;
import com.taskpilot.ai.assignment.port.out.RecommendationSnapshotPort;
import com.taskpilot.ai.dto.RecommendationDecisionSource;
import com.taskpilot.ai.dto.RecommendationDecisionType;
import com.taskpilot.ai.entity.RecommendationDecisionEventEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotCandidateEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotEntity;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Append-only PM decision event service for Phase 2B.
 * Captures terminal PM actions (ACCEPTED, OVERRIDDEN, REJECTED, CANCELED, EXPIRED)
 * linked to Phase 2A recommendation snapshots.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationDecisionService {

    public static final int MAX_NOTE_LENGTH = 1000;

    private final RecommendationSnapshotPort snapshotPort;
    private final RecommendationDecisionEventPort decisionPort;
    private final ProjectMemberPort projectMemberPort;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RecommendationDecisionEventEntity recordDecision(
            Long decidedByUserId,
            String snapshotId,
            RecommendationDecisionType decisionType,
            Long selectedCandidateId,
            String reasonCode,
            String note,
            Long expectedProjectId,
            Long expectedTaskId) {

        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("Snapshot ID is required for recording decision");
        }
        if (decisionType == null) {
            throw new IllegalArgumentException("Decision type is required");
        }

        RecommendationSnapshotEntity snapshot = snapshotPort.findById(snapshotId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                        "Recommendation snapshot not found: " + snapshotId));

        if (expectedProjectId != null && !Objects.equals(expectedProjectId, snapshot.getProjectId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Snapshot project " + snapshot.getProjectId() + " does not match expected project " + expectedProjectId);
        }
        if (expectedTaskId != null && !Objects.equals(expectedTaskId, snapshot.getTaskId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Snapshot task " + snapshot.getTaskId() + " does not match expected task " + expectedTaskId);
        }

        // State rule: At most one terminal decision per snapshot
        Optional<RecommendationDecisionEventEntity> existing = decisionPort.findBySnapshotId(snapshotId);
        if (existing.isPresent()) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Conflict: A decision has already been recorded for this recommendation snapshot or the event violates a persistence constraint.");
        }

        // Authorization & Actor Semantics:
        // EXPIRED is system-generated: decisionSource = SYSTEM, decidedByUserId = null.
        // Original requester remains available on the linked snapshot.
        // ACCEPTED, OVERRIDDEN, REJECTED, and CANCELED are user-generated: decisionSource = USER,
        // requiring authenticated manager context at decision time.
        RecommendationDecisionSource decisionSource;
        Long effectiveDecidedByUserId;

        if (decisionType == RecommendationDecisionType.EXPIRED) {
            decisionSource = RecommendationDecisionSource.SYSTEM;
            effectiveDecidedByUserId = null;
        } else {
            decisionSource = RecommendationDecisionSource.USER;
            if (decidedByUserId == null) {
                throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Authenticated user ID is required to record user decision");
            }
            if (!projectMemberPort.isProjectManager(snapshot.getProjectId(), decidedByUserId)) {
                throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                        "User " + decidedByUserId + " is not a manager of project " + snapshot.getProjectId());
            }
            effectiveDecidedByUserId = decidedByUserId;
        }

        // Candidate verification
        Set<Long> candidateIds = snapshot.getCandidates() != null
                ? snapshot.getCandidates().stream().map(RecommendationSnapshotCandidateEntity::getCandidateId).collect(Collectors.toSet())
                : Set.of();

        Long recommendedId = snapshot.getRecommendedCandidateId();
        Long effectiveSelectedId = selectedCandidateId;

        switch (decisionType) {
            case ACCEPTED -> {
                if (effectiveSelectedId == null) {
                    effectiveSelectedId = recommendedId;
                }
                if (effectiveSelectedId == null) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                            "Cannot record ACCEPTED for snapshot with no recommended candidate");
                }
                if (!Objects.equals(effectiveSelectedId, recommendedId)) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                            "Selected candidate " + effectiveSelectedId + " does not match recommended candidate " + recommendedId + "; use OVERRIDDEN instead");
                }
                if (!candidateIds.contains(effectiveSelectedId)) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                            "Candidate " + effectiveSelectedId + " is not present in snapshot " + snapshotId);
                }
            }
            case OVERRIDDEN -> {
                if (effectiveSelectedId == null) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                            "Selected candidate is required for OVERRIDDEN decision");
                }
                if (!candidateIds.contains(effectiveSelectedId)) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                            "Override candidate " + effectiveSelectedId + " is not present in snapshot " + snapshotId);
                }
                if (Objects.equals(effectiveSelectedId, recommendedId)) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                            "Override candidate matches recommended candidate; use ACCEPTED instead");
                }
            }
            case REJECTED, CANCELED, EXPIRED -> {
                effectiveSelectedId = null;
            }
        }

        String safeNote = note;
        if (safeNote != null && safeNote.length() > MAX_NOTE_LENGTH) {
            safeNote = safeNote.substring(0, MAX_NOTE_LENGTH);
        }

        String decisionId = "rec-dec-" + UUID.randomUUID();
        RecommendationDecisionEventEntity event = RecommendationDecisionEventEntity.builder()
                .decisionId(decisionId)
                .snapshotId(snapshotId)
                .projectId(snapshot.getProjectId())
                .taskId(snapshot.getTaskId())
                .decidedByUserId(effectiveDecidedByUserId)
                .decisionSource(decisionSource)
                .decisionType(decisionType)
                .recommendedCandidateId(recommendedId)
                .selectedCandidateId(effectiveSelectedId)
                .reasonCode(reasonCode)
                .note(safeNote)
                .createdAt(Instant.now())
                .build();

        RecommendationDecisionEventEntity saved;
        try {
            saved = decisionPort.save(event);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Conflict: A decision has already been recorded for this recommendation snapshot or the event violates a persistence constraint.");
        }
        log.info("[DecisionEvent] Recorded {} ({}) decision {} for snapshot {} on project {} by user {}",
                decisionType, decisionSource, decisionId, snapshotId, snapshot.getProjectId(), effectiveDecidedByUserId);
        return saved;
    }

    public Optional<RecommendationSnapshotEntity> findSnapshot(String snapshotId) {
        return snapshotPort.findById(snapshotId);
    }

    public RecommendationDecisionEventEntity recordAccepted(
            Long managerUserId, String snapshotId, Long candidateId, String reasonCode, String note) {
        return recordDecision(managerUserId, snapshotId, RecommendationDecisionType.ACCEPTED, candidateId, reasonCode, note, null, null);
    }

    public RecommendationDecisionEventEntity recordOverridden(
            Long managerUserId, String snapshotId, Long candidateId, String reasonCode, String note) {
        return recordDecision(managerUserId, snapshotId, RecommendationDecisionType.OVERRIDDEN, candidateId, reasonCode, note, null, null);
    }

    public RecommendationDecisionEventEntity recordRejected(
            Long managerUserId, String snapshotId, String reasonCode, String note) {
        return recordDecision(managerUserId, snapshotId, RecommendationDecisionType.REJECTED, null, reasonCode, note, null, null);
    }

    public RecommendationDecisionEventEntity recordCanceled(
            Long managerUserId, String snapshotId, String reasonCode, String note) {
        return recordDecision(managerUserId, snapshotId, RecommendationDecisionType.CANCELED, null, reasonCode, note, null, null);
    }

    public RecommendationDecisionEventEntity recordExpired(String snapshotId, String note) {
        return recordDecision(null, snapshotId, RecommendationDecisionType.EXPIRED, null, "EXPIRED", note, null, null);
    }

    @Transactional(readOnly = true)
    public RecommendationDecisionEventEntity getDecision(String snapshotId, Long requestingUserId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new BusinessException(HttpStatus.NOT_FOUND.value(), "Snapshot ID is required");
        }
        RecommendationDecisionEventEntity decision = decisionPort.findBySnapshotId(snapshotId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                        "No decision found for snapshot: " + snapshotId));

        if (requestingUserId == null || !projectMemberPort.isProjectMember(decision.getProjectId(), requestingUserId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "User " + requestingUserId + " is not authorized to view decisions for project " + decision.getProjectId());
        }

        return decision;
    }
}
