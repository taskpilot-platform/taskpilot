package com.taskpilot.ai.service;

import com.taskpilot.ai.assignment.port.out.RecommendationDecisionEventPort;
import com.taskpilot.ai.entity.AdaptiveOutcomeInvalidationEntity;
import com.taskpilot.ai.entity.AdaptiveReviewedOutcomeEntity;
import com.taskpilot.ai.entity.RecommendationDecisionEventEntity;
import com.taskpilot.ai.repository.AdaptiveOutcomeInvalidationRepository;
import com.taskpilot.ai.repository.AdaptiveReviewedOutcomeRepository;
import com.taskpilot.contracts.task.TaskEvaluationAccepted;
import com.taskpilot.contracts.task.TaskEvaluationInvalidated;
import com.taskpilot.contracts.task.TaskEvaluationJournalConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotent downstream projection consumer for P5/P8 task evaluation lifecycle journal events.
 * Projects reviewed-outcome-v2 rows and handles reopen/supersede/delete invalidations without
 * ever rolling back committed task reviews or altering earlier snapshot feature inputs.
 */
@Slf4j
@Service
public class TaskEvaluationJournalListener implements TaskEvaluationJournalConsumer {

    private static final int MAX_TRACKED_EVENTS = 10_000;

    private final AdaptiveReviewedOutcomeRepository outcomeRepository;
    private final AdaptiveOutcomeInvalidationRepository invalidationRepository;
    private final RecommendationDecisionEventPort decisionPort;
    private final Clock clock;

    private final Map<UUID, String> processedEvents = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, String> eldest) {
                    return size() > MAX_TRACKED_EVENTS;
                }
            });

    @Autowired
    public TaskEvaluationJournalListener(
            AdaptiveReviewedOutcomeRepository outcomeRepository,
            AdaptiveOutcomeInvalidationRepository invalidationRepository,
            RecommendationDecisionEventPort decisionPort,
            Optional<Clock> clock) {
        this.outcomeRepository = outcomeRepository;
        this.invalidationRepository = invalidationRepository;
        this.decisionPort = decisionPort;
        this.clock = clock != null ? clock.orElse(Clock.systemUTC()) : Clock.systemUTC();
    }

    public TaskEvaluationJournalListener() {
        this.outcomeRepository = null;
        this.invalidationRepository = null;
        this.decisionPort = null;
        this.clock = Clock.systemUTC();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void onEvaluationAccepted(TaskEvaluationAccepted event) {
        if (event == null || event.eventId() == null) {
            return;
        }
        if (outcomeRepository == null) {
            processedEvents.putIfAbsent(event.eventId(), "ACCEPTED");
            return;
        }
        if (outcomeRepository.existsBySourceEventId(event.eventId())) {
            processedEvents.put(event.eventId(), "ACCEPTED");
            return;
        }
        int evalVersion = (int) event.evaluationVersion();
        if (outcomeRepository.findByEvaluationIdAndEvaluationVersion(event.evaluationId(), evalVersion).isPresent()) {
            processedEvents.put(event.eventId(), "ACCEPTED");
            return;
        }

        String decisionId = trimToNull(event.assignmentDecisionId());
        String snapshotId = null;
        if (decisionId != null && decisionPort != null) {
            Optional<RecommendationDecisionEventEntity> decisionOpt = decisionPort.findById(decisionId);
            if (decisionOpt.isPresent()) {
                RecommendationDecisionEventEntity decision = decisionOpt.get();
                if (Objects.equals(decision.getProjectId(), event.projectId())
                        && (decision.getTaskId() == null || Objects.equals(decision.getTaskId(), event.taskId()))) {
                    snapshotId = decision.getSnapshotId();
                }
            }
        }

        Optional<AdaptiveOutcomeInvalidationEntity> priorInvalidation = invalidationRepository != null
                ? invalidationRepository.findByEvaluationIdAndEvaluationVersion(event.evaluationId(), evalVersion)
                : Optional.empty();

        String timelinessStatus = event.commitmentDueAt() == null
                ? "NO_DUE_DATE"
                : (Boolean.TRUE.equals(event.onTime()) ? "ON_TIME" : "LATE");

        Instant projectedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AdaptiveReviewedOutcomeEntity entity = AdaptiveReviewedOutcomeEntity.builder()
                .outcomeId("rev-out-" + event.evaluationId() + "-v" + evalVersion)
                .sourceEventId(event.eventId())
                .projectId(event.projectId())
                .taskId(event.taskId())
                .submissionId(event.submissionId())
                .evaluationId(event.evaluationId())
                .evaluationVersion(evalVersion)
                .assignmentVersion(event.assignmentVersion())
                .assigneeId(event.assigneeId())
                .reviewerId(event.reviewerId())
                .decisionId(decisionId)
                .snapshotId(snapshotId)
                .evidenceStatus(event.evidenceStatus())
                .qualityRating(event.qualityRating())
                .rubricVersion(event.rubricVersion())
                .acceptedSubmittedAt(event.acceptedSubmittedAt())
                .reviewedAt(event.reviewedAt())
                .commitmentDueAt(event.commitmentDueAt())
                .onTime(event.onTime())
                .timelinessStatus(timelinessStatus)
                .evidenceRevision(event.evidenceRevision())
                .invalidated(priorInvalidation.isPresent())
                .invalidationReason(priorInvalidation.map(AdaptiveOutcomeInvalidationEntity::getReason).orElse(null))
                .invalidatedAt(priorInvalidation.map(AdaptiveOutcomeInvalidationEntity::getOccurredAt).orElse(null))
                .invalidationEventId(priorInvalidation.map(AdaptiveOutcomeInvalidationEntity::getEventId).orElse(null))
                .invalidatedEvidenceRevision(priorInvalidation.map(AdaptiveOutcomeInvalidationEntity::getEvidenceRevision).orElse(null))
                .synthetic(Boolean.FALSE)
                .outcomeVersion(AdaptiveReviewedOutcomeEntity.OUTCOME_VERSION_V2)
                .projectedAt(projectedAt)
                .build();

        outcomeRepository.saveAndFlush(entity);
        processedEvents.put(event.eventId(), "ACCEPTED");
        log.debug("[AiProjection] Projected reviewed-outcome-v2 evalId={} ver={} taskId={} decisionId={}",
                event.evaluationId(), evalVersion, event.taskId(), decisionId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void onEvaluationInvalidated(TaskEvaluationInvalidated event) {
        if (event == null || event.eventId() == null) {
            return;
        }
        if (outcomeRepository == null || invalidationRepository == null) {
            processedEvents.putIfAbsent(event.eventId(), "INVALIDATED");
            return;
        }
        if (invalidationRepository.existsByEventId(event.eventId())) {
            processedEvents.put(event.eventId(), "INVALIDATED");
            return;
        }

        int evalVersion = (int) event.evaluationVersion();
        if (invalidationRepository.findByEvaluationIdAndEvaluationVersion(event.evaluationId(), evalVersion).isEmpty()) {
            invalidationRepository.saveAndFlush(AdaptiveOutcomeInvalidationEntity.builder()
                    .eventId(event.eventId())
                    .projectId(event.projectId())
                    .taskId(event.taskId())
                    .evaluationId(event.evaluationId())
                    .evaluationVersion(evalVersion)
                    .reason(event.reason())
                    .occurredAt(event.occurredAt())
                    .evidenceRevision(event.evidenceRevision())
                    .build());
        }

        outcomeRepository.findByEvaluationIdAndEvaluationVersion(event.evaluationId(), evalVersion)
                .ifPresent(outcome -> {
                    if (!Boolean.TRUE.equals(outcome.getInvalidated())) {
                        outcome.setInvalidated(Boolean.TRUE);
                        outcome.setInvalidationReason(event.reason());
                        outcome.setInvalidatedAt(event.occurredAt());
                        outcome.setInvalidationEventId(event.eventId());
                        outcome.setInvalidatedEvidenceRevision(event.evidenceRevision());
                        outcomeRepository.saveAndFlush(outcome);
                    }
                });

        processedEvents.put(event.eventId(), "INVALIDATED");
        log.debug("[AiProjection] Invalidated reviewed-outcome-v2 evalId={} ver={} taskId={} reason={}",
                event.evaluationId(), evalVersion, event.taskId(), event.reason());
    }

    public boolean hasProcessed(UUID eventId) {
        if (eventId == null) {
            return false;
        }
        if (outcomeRepository != null && outcomeRepository.existsBySourceEventId(eventId)) {
            return true;
        }
        if (invalidationRepository != null && invalidationRepository.existsByEventId(eventId)) {
            return true;
        }
        return outcomeRepository == null && processedEvents.containsKey(eventId);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
