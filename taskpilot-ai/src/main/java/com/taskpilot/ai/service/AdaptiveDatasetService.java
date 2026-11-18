package com.taskpilot.ai.service;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.taskpilot.ai.assignment.port.out.RecommendationDecisionEventPort;
import com.taskpilot.ai.assignment.port.out.RecommendationSnapshotPort;
import com.taskpilot.ai.dto.AdaptiveDatasetQualityReportDto;
import com.taskpilot.ai.dto.AdaptiveDatasetQualityReportDto.AdaptiveReportScope;
import com.taskpilot.ai.dto.AdaptiveDatasetRowDto;
import com.taskpilot.ai.dto.AdaptiveExclusionReason;
import com.taskpilot.ai.dto.CandidateFeatureDto;
import com.taskpilot.ai.dto.ChosenTaskOutcomeDiagnosticsDto;
import com.taskpilot.ai.dto.MetricDataStatus;
import com.taskpilot.ai.dto.RecommendationDecisionSource;
import com.taskpilot.ai.dto.RecommendationDecisionType;
import com.taskpilot.ai.entity.AdaptiveReviewedOutcomeEntity;
import com.taskpilot.ai.entity.RecommendationDecisionEventEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotCandidateEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotEntity;
import com.taskpilot.ai.entity.RecommendationTaskOutcomeEntity;
import com.taskpilot.ai.heuristic.NormalizedScores;
import com.taskpilot.ai.repository.AdaptiveReviewedOutcomeRepository;
import com.taskpilot.ai.repository.RecommendationTaskOutcomeRepository;
import com.taskpilot.contracts.task.TaskEvaluationJournalStatePort;
import com.taskpilot.contracts.task.TaskEvaluationJournalStatePort.JournalHealthSummary;
import com.taskpilot.infrastructure.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Deterministic dataset-v2 builder and quality report service for P8.2.
 * Separates preference learning pairs from manual, self-attested, legacy, synthetic,
 * invalidated, and REJECT-only evidence while preserving snapshot-time feature values.
 */
@Slf4j
@Service
public class AdaptiveDatasetService {

    public static final String EXPECTED_PRESENTATION_CONTRACT_VERSION = "allowlisted-view-v3";
    public static final String EXPECTED_SCORING_MODEL_VERSION = NormalizedScores.PROJECT_RANKING_VERSION;
    public static final String EXPECTED_WORKLOAD_UNIT = "ACTIVE_TASK_COUNT";
    public static final String EXPECTED_WORKLOAD_SCOPE = "PROJECT";
    public static final int DEFAULT_LIMIT = 200;
    public static final int MAX_LIMIT = 500;
    private static final int MAX_SCOPED_OUTCOMES = 500;
    private static final Set<String> SUPPORTED_MODES = Set.of("BALANCED", "URGENT", "TRAINING");

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final AdaptiveReviewedOutcomeRepository outcomeRepository;
    private final RecommendationTaskOutcomeRepository legacyOutcomeRepository;
    private final RecommendationDecisionEventPort decisionPort;
    private final RecommendationSnapshotPort snapshotPort;
    private final TaskEvaluationJournalStatePort journalStatePort;
    private final Clock clock;

    @Value("${ai.adaptive.minimum-samples:20}")
    private int minimumRequiredSamples = AdaptiveDatasetQualityReportDto.DEFAULT_MINIMUM_REQUIRED_SAMPLES;

    @Autowired
    public AdaptiveDatasetService(
            AdaptiveReviewedOutcomeRepository outcomeRepository,
            RecommendationTaskOutcomeRepository legacyOutcomeRepository,
            RecommendationDecisionEventPort decisionPort,
            RecommendationSnapshotPort snapshotPort,
            @Autowired(required = false) TaskEvaluationJournalStatePort journalStatePort,
            Optional<Clock> clock) {
        this(outcomeRepository, legacyOutcomeRepository, decisionPort, snapshotPort, journalStatePort,
                clock != null ? clock.orElse(Clock.systemUTC()) : Clock.systemUTC());
    }

    public AdaptiveDatasetService(
            AdaptiveReviewedOutcomeRepository outcomeRepository,
            RecommendationTaskOutcomeRepository legacyOutcomeRepository,
            RecommendationDecisionEventPort decisionPort,
            RecommendationSnapshotPort snapshotPort,
            TaskEvaluationJournalStatePort journalStatePort,
            Clock clock) {
        this.outcomeRepository = outcomeRepository;
        this.legacyOutcomeRepository = legacyOutcomeRepository;
        this.decisionPort = decisionPort;
        this.snapshotPort = snapshotPort;
        this.journalStatePort = journalStatePort;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public record DatasetResult(
            List<AdaptiveDatasetRowDto> eligibleRows,
            AdaptiveDatasetQualityReportDto qualityReport
    ) {
    }

    @Transactional(readOnly = true)
    public DatasetResult buildDataset(Long projectId, Long managerId, String mode, int page, int limit) {
        validatePagination(page, limit);
        String resolvedMode = normalizeMode(mode);
        var diagnosticsBound = PageRequest.of(0, MAX_SCOPED_OUTCOMES);
        List<AdaptiveReviewedOutcomeEntity> scoped = outcomeRepository.findScopedOutcomes(
                projectId, managerId, resolvedMode, PageRequest.of(0, MAX_SCOPED_OUTCOMES + 1));
        boolean truncated = scoped.size() > MAX_SCOPED_OUTCOMES;
        List<AdaptiveReviewedOutcomeEntity> outcomes =
                new ArrayList<>(scoped.subList(0, Math.min(scoped.size(), MAX_SCOPED_OUTCOMES)));
        outcomes.addAll(outcomeRepository.findUnlinkedOutcomes(projectId, diagnosticsBound));
        List<RecommendationDecisionEventEntity> rejectedDecisions = decisionPort
                .findScopedByDecisionType(RecommendationDecisionType.REJECTED, projectId, managerId, resolvedMode,
                        diagnosticsBound);
        List<RecommendationTaskOutcomeEntity> legacyOutcomes = legacyOutcomeRepository
                .findScopedLegacyOutcomes(projectId, diagnosticsBound);

        return evaluateScoped(outcomes, rejectedDecisions, legacyOutcomes, projectId, managerId, resolvedMode,
                page, limit, truncated);
    }

    @Transactional(readOnly = true)
    public AdaptiveDatasetQualityReportDto generateQualityReport(
            Long projectId, Long managerId, String mode, int page, int limit) {
        return buildDataset(projectId, managerId, mode, page, limit).qualityReport();
    }

    @Transactional(readOnly = true)
    public AdaptiveDatasetQualityReportDto generateQualityReport() {
        return generateQualityReport(null, null, "BALANCED", 0, DEFAULT_LIMIT);
    }

    private DatasetResult evaluateScoped(
            List<AdaptiveReviewedOutcomeEntity> outcomes,
            List<RecommendationDecisionEventEntity> rejectedDecisions,
            List<RecommendationTaskOutcomeEntity> legacyOutcomes,
            Long projectId,
            Long managerId,
            String mode,
            int page,
            int limit,
            boolean truncated) {

        Set<String> decisionIds = outcomes.stream()
                .map(AdaptiveReviewedOutcomeEntity::getDecisionId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        Map<String, RecommendationDecisionEventEntity> decisionsById = decisionIds.isEmpty()
                ? Map.of()
                : decisionPort.findAllByIds(decisionIds).stream()
                        .collect(Collectors.toMap(RecommendationDecisionEventEntity::getDecisionId, Function.identity(),
                                (a, b) -> a));

        Set<String> snapshotIds = new LinkedHashSet<>();
        outcomes.stream()
                .map(AdaptiveReviewedOutcomeEntity::getSnapshotId)
                .filter(id -> id != null && !id.isBlank())
                .forEach(snapshotIds::add);
        decisionsById.values().stream()
                .map(RecommendationDecisionEventEntity::getSnapshotId)
                .filter(id -> id != null && !id.isBlank())
                .forEach(snapshotIds::add);
        Map<String, RecommendationSnapshotEntity> snapshotsById = snapshotIds.isEmpty()
                ? Map.of()
                : snapshotPort.findAllByIds(snapshotIds).stream()
                        .collect(Collectors.toMap(RecommendationSnapshotEntity::getSnapshotId, Function.identity(),
                                (a, b) -> a));

        List<AdaptiveDatasetRowDto> eligibleRows = new ArrayList<>();
        Map<String, Long> exclusionBreakdown = new TreeMap<>();
        Set<String> seenEvalKeys = new HashSet<>();
        Set<String> seenDecisionTaskKeys = new HashSet<>();

        long totalOutcomesEvaluated = 0;
        long manualEvidenceCount = 0;
        long selfAttestedEvidenceCount = 0;
        long invalidatedOutcomesCount = 0;
        long syntheticRowsCount = 0;
        long rejectedFeedbackCount = 0;
        long legacyOutcomeCount = 0;

        // Task workflow commits invalidations before their AI projection catches up.
        Map<Long, String> invalidations = journalStatePort != null
                ? journalStatePort.findInvalidationReasons(outcomes.stream()
                        .map(AdaptiveReviewedOutcomeEntity::getEvaluationId).distinct().toList())
                : Map.of();
        for (AdaptiveReviewedOutcomeEntity outcome : outcomes) {
            if (outcome == null) {
                continue;
            }
            if (projectId != null && !Objects.equals(projectId, outcome.getProjectId())) {
                continue;
            }
            RecommendationDecisionEventEntity decision = outcome.getDecisionId() != null
                    ? decisionsById.get(outcome.getDecisionId())
                    : null;
            if (managerId != null && decision != null && !Objects.equals(managerId, decision.getDecidedByUserId())) {
                continue;
            }

            totalOutcomesEvaluated++;

            if (Boolean.TRUE.equals(outcome.getSynthetic())) {
                syntheticRowsCount++;
                incrementReason(exclusionBreakdown, AdaptiveExclusionReason.SYNTHETIC_EXCLUDED);
                continue;
            }

            String invalidationReason = invalidations.get(outcome.getEvaluationId());
            if (invalidationReason != null || Boolean.TRUE.equals(outcome.getInvalidated())) {
                invalidatedOutcomesCount++;
                incrementReason(exclusionBreakdown, mapInvalidationReason(
                        invalidationReason != null ? invalidationReason : outcome.getInvalidationReason()));
                continue;
            }

            String evalKey = outcome.getEvaluationId() + ":" + outcome.getEvaluationVersion();
            if (!seenEvalKeys.add(evalKey)) {
                incrementReason(exclusionBreakdown, AdaptiveExclusionReason.DUPLICATE_SAMPLE);
                continue;
            }

            boolean isSelfAttested = !"INDEPENDENT_ACCEPTED".equals(outcome.getEvidenceStatus());
            boolean isManual = outcome.getDecisionId() == null || outcome.getDecisionId().isBlank();
            if (isSelfAttested) {
                selfAttestedEvidenceCount++;
                incrementReason(exclusionBreakdown, AdaptiveExclusionReason.SELF_ATTESTED_SEPARATED);
                if (isManual) {
                    manualEvidenceCount++;
                }
                continue;
            }
            if (isManual) {
                manualEvidenceCount++;
                incrementReason(exclusionBreakdown, AdaptiveExclusionReason.MANUAL_ASSIGNMENT_NO_DECISION);
                continue;
            }

            Optional<AdaptiveExclusionReason> exclusion = validateEligibleSample(
                    outcome, decision, snapshotsById, managerId, mode, seenDecisionTaskKeys);
            if (exclusion.isPresent()) {
                AdaptiveExclusionReason reason = exclusion.get();
                if (reason == AdaptiveExclusionReason.SYNTHETIC_EXCLUDED) {
                    syntheticRowsCount++;
                }
                incrementReason(exclusionBreakdown, reason);
                continue;
            }

            RecommendationSnapshotEntity snapshot = resolveSnapshot(outcome, decision, snapshotsById);
            AdaptiveDatasetRowDto row = buildDatasetRow(outcome, decision, snapshot);
            if (row != null) {
                eligibleRows.add(row);
            } else {
                incrementReason(exclusionBreakdown, AdaptiveExclusionReason.OUTCOME_EXCLUDED);
            }
        }

        for (RecommendationDecisionEventEntity rejected : rejectedDecisions) {
            if (rejected == null) {
                continue;
            }
            if (Boolean.TRUE.equals(rejected.getSynthetic())) {
                syntheticRowsCount++;
                incrementReason(exclusionBreakdown, AdaptiveExclusionReason.SYNTHETIC_EXCLUDED);
                continue;
            }
            rejectedFeedbackCount++;
            incrementReason(exclusionBreakdown, AdaptiveExclusionReason.NO_COMPARABLE_CHOICE);
        }

        for (RecommendationTaskOutcomeEntity legacy : legacyOutcomes) {
            if (legacy == null) {
                continue;
            }
            legacyOutcomeCount++;
            incrementReason(exclusionBreakdown, AdaptiveExclusionReason.LEGACY_OUTCOME_V1);
        }

        eligibleRows.sort(Comparator
                .comparing(AdaptiveDatasetRowDto::taskId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(AdaptiveDatasetRowDto::decisionId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(r -> r.outcomeDiagnostics().evaluationId())
                .thenComparing(r -> r.outcomeDiagnostics().evaluationVersion())
                .thenComparing(AdaptiveDatasetRowDto::rowId, Comparator.nullsLast(Comparator.naturalOrder())));

        boolean exactScope = projectId != null && managerId != null;
        boolean hasCanonicalDataset = exactScope && !truncated;
        String canonicalSha256 = hasCanonicalDataset ? computeCanonicalDatasetSha256(eligibleRows) : null;
        long eligibleCount = eligibleRows.size();
        long excludedCount = Math.max(0L, totalOutcomesEvaluated - eligibleCount);
        boolean sufficientEvidence = hasCanonicalDataset && eligibleCount >= minimumRequiredSamples;

        JournalHealthSummary journalHealth = journalStatePort != null
                ? journalStatePort.summarize(projectId)
                : new JournalHealthSummary(0L, 0L, 0L);

        String readinessStatus;
        String readinessMessage;
        if (!exactScope) {
            readinessStatus = "SCOPE_REQUIRED";
            readinessMessage = "Choose a project and manager to evaluate proposal readiness.";
        } else if (truncated) {
            readinessStatus = "DATASET_LIMIT_REACHED";
            readinessMessage = "This scope exceeds the " + MAX_SCOPED_OUTCOMES + "-outcome evaluation limit.";
        } else {
            readinessStatus = sufficientEvidence ? "READY_FOR_PROPOSAL" : "INSUFFICIENT_DATA";
            readinessMessage = sufficientEvidence
                    ? "Sufficient independent-reviewed preference samples (" + eligibleCount + "/" + minimumRequiredSamples + ")."
                    : "Insufficient data: " + eligibleCount + " of " + minimumRequiredSamples
                            + " required independent-reviewed preference samples available.";
        }

        List<AdaptiveDatasetRowDto> pagedSampleRows = paginateRows(eligibleRows, page, limit);
        AdaptiveReportScope scope = new AdaptiveReportScope(projectId, managerId, mode, page, limit);

        AdaptiveDatasetQualityReportDto report = new AdaptiveDatasetQualityReportDto(
                AdaptiveDatasetQualityReportDto.DATASET_VERSION,
                scope,
                totalOutcomesEvaluated,
                eligibleCount,
                excludedCount,
                manualEvidenceCount,
                selfAttestedEvidenceCount,
                rejectedFeedbackCount,
                invalidatedOutcomesCount,
                legacyOutcomeCount,
                syntheticRowsCount,
                journalHealth.pendingCount() + journalHealth.retryPendingCount(),
                journalHealth.failedCount(),
                Collections.unmodifiableMap(exclusionBreakdown),
                canonicalSha256,
                sufficientEvidence,
                minimumRequiredSamples,
                readinessStatus,
                readinessMessage,
                clock.instant(),
                pagedSampleRows
        );

        return new DatasetResult(hasCanonicalDataset ? List.copyOf(eligibleRows) : List.of(), report);
    }

    private Optional<AdaptiveExclusionReason> validateEligibleSample(
            AdaptiveReviewedOutcomeEntity outcome,
            RecommendationDecisionEventEntity decision,
            Map<String, RecommendationSnapshotEntity> snapshotsById,
            Long managerId,
            String mode,
            Set<String> seenDecisionTaskKeys) {
        if (decision == null) {
            return Optional.of(AdaptiveExclusionReason.DECISION_NOT_FOUND);
        }
        if (Boolean.TRUE.equals(decision.getSynthetic())) {
            return Optional.of(AdaptiveExclusionReason.SYNTHETIC_EXCLUDED);
        }
        if (managerId != null && !Objects.equals(managerId, decision.getDecidedByUserId())) {
            return Optional.of(AdaptiveExclusionReason.OUT_OF_SCOPE_MANAGER);
        }
        if (decision.getDecisionSource() != RecommendationDecisionSource.USER) {
            return Optional.of(AdaptiveExclusionReason.NON_USER_DECISION);
        }
        if (decision.getDecisionType() != RecommendationDecisionType.ACCEPTED
                && decision.getDecisionType() != RecommendationDecisionType.OVERRIDDEN) {
            return Optional.of(AdaptiveExclusionReason.NON_ASSIGNMENT_DECISION);
        }
        if (decision.getTaskId() != null && !Objects.equals(decision.getTaskId(), outcome.getTaskId())) {
            return Optional.of(AdaptiveExclusionReason.DECISION_TASK_MISMATCH);
        }
        if (!Objects.equals(decision.getProjectId(), outcome.getProjectId())) {
            return Optional.of(AdaptiveExclusionReason.DECISION_PROJECT_MISMATCH);
        }
        if (decision.getAssignmentVersion() != null
                && !Objects.equals(decision.getAssignmentVersion(), outcome.getAssignmentVersion())) {
            return Optional.of(AdaptiveExclusionReason.ASSIGNMENT_VERSION_MISMATCH);
        }
        if (decision.getSelectedCandidateId() == null) {
            return Optional.of(AdaptiveExclusionReason.SELECTED_CANDIDATE_NOT_FOUND);
        }
        if (!Objects.equals(decision.getSelectedCandidateId(), outcome.getAssigneeId())) {
            return Optional.of(AdaptiveExclusionReason.ASSIGNEE_MISMATCH);
        }

        String dedupKey = decision.getDecisionId() + ":" + outcome.getTaskId();
        if (!seenDecisionTaskKeys.add(dedupKey)) {
            return Optional.of(AdaptiveExclusionReason.DUPLICATE_SAMPLE);
        }

        RecommendationSnapshotEntity snapshot = resolveSnapshot(outcome, decision, snapshotsById);
        if (snapshot == null) {
            return Optional.of(AdaptiveExclusionReason.SNAPSHOT_NOT_FOUND);
        }
        if (Boolean.TRUE.equals(snapshot.getSynthetic())) {
            return Optional.of(AdaptiveExclusionReason.SYNTHETIC_EXCLUDED);
        }
        if (!Objects.equals(snapshot.getProjectId(), outcome.getProjectId())) {
            return Optional.of(AdaptiveExclusionReason.DECISION_PROJECT_MISMATCH);
        }
        if (snapshot.getTaskId() != null && !Objects.equals(snapshot.getTaskId(), outcome.getTaskId())) {
            return Optional.of(AdaptiveExclusionReason.DECISION_TASK_MISMATCH);
        }
        if (!EXPECTED_PRESENTATION_CONTRACT_VERSION.equalsIgnoreCase(snapshot.getPresentationContractVersion())
                || !EXPECTED_SCORING_MODEL_VERSION.equalsIgnoreCase(snapshot.getScoringModelVersion())) {
            return Optional.of(AdaptiveExclusionReason.UNSUPPORTED_SNAPSHOT_VERSION);
        }
        if (snapshot.getHeuristicMode() == null || !mode.equalsIgnoreCase(snapshot.getHeuristicMode().trim())) {
            return Optional.of(AdaptiveExclusionReason.UNSUPPORTED_HEURISTIC_MODE);
        }
        if ("UNOBSERVED".equalsIgnoreCase(snapshot.getDifferentiationStatus())) {
            return Optional.of(AdaptiveExclusionReason.UNOBSERVED_SNAPSHOT);
        }

        List<RecommendationSnapshotCandidateEntity> candidates = snapshot.getCandidates();
        if (candidates == null || candidates.isEmpty()) {
            return Optional.of(AdaptiveExclusionReason.SELECTED_CANDIDATE_NOT_FOUND);
        }

        RecommendationSnapshotCandidateEntity selected = candidates.stream()
                .filter(c -> Objects.equals(c.getCandidateId(), decision.getSelectedCandidateId()))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            return Optional.of(AdaptiveExclusionReason.SELECTED_CANDIDATE_NOT_FOUND);
        }

        Optional<AdaptiveExclusionReason> chosenCheck = validateCandidateFeatures(selected);
        if (chosenCheck.isPresent()) {
            return chosenCheck;
        }

        int eligibleAlternatives = 0;
        for (RecommendationSnapshotCandidateEntity c : candidates) {
            if (Objects.equals(c.getCandidateId(), decision.getSelectedCandidateId())) {
                continue;
            }
            if (validateCandidateFeatures(c).isPresent()) {
                return Optional.of(AdaptiveExclusionReason.POOL_CANDIDATE_INVALID);
            }
            eligibleAlternatives++;
        }
        if (eligibleAlternatives < 1) {
            return Optional.of(AdaptiveExclusionReason.INSUFFICIENT_ALTERNATIVES);
        }

        return Optional.empty();
    }

    private Optional<AdaptiveExclusionReason> validateCandidateFeatures(RecommendationSnapshotCandidateEntity c) {
        if (c.getFitStatus() != MetricDataStatus.MEASURED) {
            return Optional.of(AdaptiveExclusionReason.FIT_STATUS_NOT_MEASURED);
        }
        Double fitVal = c.getPresentationFitValue() != null ? c.getPresentationFitValue() : c.getRankingRawFit();
        if (fitVal == null || Double.isNaN(fitVal) || Double.isInfinite(fitVal) || fitVal < 0.0 || fitVal > 1.0) {
            return Optional.of(AdaptiveExclusionReason.INVALID_FIT_VALUE);
        }
        if (c.getWorkloadStatus() != MetricDataStatus.MEASURED) {
            return Optional.of(AdaptiveExclusionReason.WORKLOAD_STATUS_NOT_MEASURED);
        }
        if (!EXPECTED_WORKLOAD_UNIT.equalsIgnoreCase(c.getWorkloadUnit())) {
            return Optional.of(AdaptiveExclusionReason.UNSUPPORTED_WORKLOAD_UNIT);
        }
        if (!EXPECTED_WORKLOAD_SCOPE.equalsIgnoreCase(c.getWorkloadScope())) {
            return Optional.of(AdaptiveExclusionReason.UNSUPPORTED_WORKLOAD_SCOPE);
        }
        if (c.getWorkloadMeasuredAt() == null) {
            return Optional.of(AdaptiveExclusionReason.MISSING_WORKLOAD_TIMESTAMP);
        }
        if (c.getStoredWorkloadValue() == null || c.getStoredWorkloadValue() < 0) {
            return Optional.of(AdaptiveExclusionReason.INVALID_WORKLOAD_VALUE);
        }
        Double perf = c.getDerivedPerformanceInput();
        if (perf == null || Double.isNaN(perf) || Double.isInfinite(perf) || perf < 0.0 || perf > 1.0
                || (c.getPerformanceStatus() != MetricDataStatus.DEFAULT
                        && c.getPerformanceStatus() != MetricDataStatus.MEASURED)) {
            return Optional.of(AdaptiveExclusionReason.INVALID_PERFORMANCE_INPUT);
        }
        return Optional.empty();
    }

    private AdaptiveDatasetRowDto buildDatasetRow(
            AdaptiveReviewedOutcomeEntity outcome,
            RecommendationDecisionEventEntity decision,
            RecommendationSnapshotEntity snapshot) {
        RecommendationSnapshotCandidateEntity selected = snapshot.getCandidates().stream()
                .filter(c -> Objects.equals(c.getCandidateId(), decision.getSelectedCandidateId()))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            return null;
        }

        double selectedFit = selected.getPresentationFitValue() != null
                ? selected.getPresentationFitValue()
                : selected.getRankingRawFit();
        long selectedLoad = selected.getStoredWorkloadValue().longValue();

        List<CandidateFeatureDto> candidateFeatures = snapshot.getCandidates().stream()
                .map(c -> new CandidateFeatureDto(
                        c.getCandidateId(),
                        c.getPresentationFitValue() != null ? c.getPresentationFitValue() : c.getRankingRawFit(),
                        c.getStoredWorkloadValue().longValue(),
                        c.getDerivedPerformanceInput(),
                        c.getPerformanceStatus().name(),
                        Boolean.TRUE.equals(c.getSelectedAsRecommendation()) || (c.getRank() != null && c.getRank() == 1),
                        Objects.equals(c.getCandidateId(), decision.getSelectedCandidateId())))
                .sorted(Comparator.comparing(CandidateFeatureDto::candidateId))
                .toList();

        ChosenTaskOutcomeDiagnosticsDto diagnostics = new ChosenTaskOutcomeDiagnosticsDto(
                outcome.getOutcomeId(),
                outcome.getEvaluationId(),
                outcome.getEvaluationVersion(),
                outcome.getEvidenceStatus(),
                outcome.getQualityRating(),
                outcome.getRubricVersion(),
                outcome.getOnTime(),
                outcome.getTimelinessStatus(),
                outcome.getAcceptedSubmittedAt(),
                outcome.getReviewedAt(),
                outcome.getCommitmentDueAt());

        return new AdaptiveDatasetRowDto(
                outcome.getOutcomeId(),
                snapshot.getSnapshotId(),
                decision.getDecisionId(),
                outcome.getTaskId(),
                outcome.getProjectId(),
                decision.getDecidedByUserId(),
                snapshot.getHeuristicMode().trim().toUpperCase(Locale.ROOT),
                snapshot.getScoringModelVersion(),
                snapshot.getPresentationContractVersion(),
                snapshot.getWeightVersion(),
                decision.getSelectedCandidateId(),
                decision.getRecommendedCandidateId(),
                decision.getDecisionType(),
                selectedFit,
                selectedLoad,
                selected.getDerivedPerformanceInput(),
                selected.getPerformanceStatus().name(),
                candidateFeatures,
                diagnostics);
    }

    private static RecommendationSnapshotEntity resolveSnapshot(
            AdaptiveReviewedOutcomeEntity outcome,
            RecommendationDecisionEventEntity decision,
            Map<String, RecommendationSnapshotEntity> snapshotsById) {
        String snapId = outcome.getSnapshotId() != null ? outcome.getSnapshotId()
                : (decision != null ? decision.getSnapshotId() : null);
        return snapId == null ? null : snapshotsById.get(snapId);
    }

    private static AdaptiveExclusionReason mapInvalidationReason(String reason) {
        if (reason == null) {
            return AdaptiveExclusionReason.OUTCOME_EXCLUDED;
        }
        return switch (reason) {
            case "REOPENED" -> AdaptiveExclusionReason.REOPENED_TASK_EXCLUDED;
            case "SUPERSEDED_BY_INDEPENDENT_REVIEW" -> AdaptiveExclusionReason.SUPERSEDED_EVALUATION_EXCLUDED;
            case "RATING_CORRECTED" -> AdaptiveExclusionReason.RATING_CORRECTED_EXCLUDED;
            case "TASK_DELETED" -> AdaptiveExclusionReason.TASK_DELETED_EXCLUDED;
            default -> AdaptiveExclusionReason.OUTCOME_EXCLUDED;
        };
    }

    private static void incrementReason(Map<String, Long> map, AdaptiveExclusionReason reason) {
        String key = reason.name();
        map.put(key, map.getOrDefault(key, 0L) + 1L);
    }

    private static List<AdaptiveDatasetRowDto> paginateRows(
            List<AdaptiveDatasetRowDto> rows, int page, int limit) {
        long offset = (long) page * limit;
        if (offset >= rows.size()) {
            return List.of();
        }
        int start = (int) offset;
        int end = Math.min(rows.size(), start + limit);
        return List.copyOf(rows.subList(start, end));
    }

    private static void validatePagination(int page, int limit) {
        if (page < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "page must be >= 0");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "limit must be between 1 and " + MAX_LIMIT);
        }
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "BALANCED";
        }
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_MODES.contains(normalized)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Unsupported heuristic mode: " + mode);
        }
        return normalized;
    }

    private String computeCanonicalDatasetSha256(List<AdaptiveDatasetRowDto> rows) {
        if (rows == null || rows.isEmpty()) {
            return computeSha256(new byte[0]);
        }
        try {
            byte[] jsonBytes = CANONICAL_MAPPER.writeValueAsBytes(rows);
            return computeSha256(jsonBytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize canonical dataset", e);
        }
    }

    private static String computeSha256(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    public void setMinimumRequiredSamples(int minimumRequiredSamples) {
        this.minimumRequiredSamples = minimumRequiredSamples;
    }
}
