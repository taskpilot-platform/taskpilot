package com.taskpilot.ai.service;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.taskpilot.ai.assignment.port.out.RecommendationDecisionEventPort;
import com.taskpilot.ai.assignment.port.out.RecommendationSnapshotPort;
import com.taskpilot.ai.assignment.port.out.RecommendationTaskOutcomePort;
import com.taskpilot.ai.dto.*;
import com.taskpilot.ai.entity.RecommendationDecisionEventEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotCandidateEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotEntity;
import com.taskpilot.ai.entity.RecommendationTaskOutcomeEntity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for constructing deterministic, privacy-safe Adaptive learning datasets (Phase 2E).
 * Implements strict exclusion auditing across 20+ distinct criteria.
 * Produces machine-readable quality reports and canonical SHA-256 dataset hashes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdaptiveDatasetService {

    public static final String EXPECTED_PRESENTATION_CONTRACT_VERSION = "allowlisted-view-v2";
    public static final String EXPECTED_WORKLOAD_UNIT = "ACTIVE_TASK_COUNT";
    public static final String EXPECTED_WORKLOAD_SCOPE = "PROJECT";

    private final RecommendationTaskOutcomePort outcomePort;
    private final RecommendationDecisionEventPort decisionPort;
    private final RecommendationSnapshotPort snapshotPort;

    @Value("${ai.adaptive.minimum-samples:20}")
    private int minimumRequiredSamples = AdaptiveDatasetQualityReportDto.DEFAULT_MINIMUM_REQUIRED_SAMPLES;

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public record DatasetResult(
            List<AdaptiveDatasetRowDto> eligibleRows,
            AdaptiveDatasetQualityReportDto qualityReport
    ) {
    }

    /**
     * Builds the deterministic Adaptive dataset and quality report from all persisted outcomes.
     */
    @Transactional(readOnly = true)
    public DatasetResult buildDataset() {
        List<RecommendationTaskOutcomeEntity> allOutcomes = outcomePort.findAll();
        return evaluateOutcomes(allOutcomes);
    }

    /**
     * Evaluates a provided collection of outcomes against all eligibility rules.
     */
    @Transactional(readOnly = true)
    public DatasetResult evaluateOutcomes(List<RecommendationTaskOutcomeEntity> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            AdaptiveDatasetQualityReportDto emptyReport = new AdaptiveDatasetQualityReportDto(
                    0,
                    0,
                    0,
                    Collections.emptyMap(),
                    computeSha256(new byte[0]),
                    false,
                    minimumRequiredSamples,
                    Instant.now()
            );
            return new DatasetResult(Collections.emptyList(), emptyReport);
        }

        List<AdaptiveDatasetRowDto> eligibleRows = new ArrayList<>();
        Map<String, Long> exclusionBreakdown = new TreeMap<>();
        Set<String> seenDecisionTaskPairs = new HashSet<>();
        long totalEvaluated = 0;

        for (RecommendationTaskOutcomeEntity outcome : outcomes) {
            totalEvaluated++;
            Optional<AdaptiveExclusionReason> exclusionReason = evaluateOutcomeEligibility(outcome, seenDecisionTaskPairs);

            if (exclusionReason.isPresent()) {
                String reasonKey = exclusionReason.get().name();
                exclusionBreakdown.put(reasonKey, exclusionBreakdown.getOrDefault(reasonKey, 0L) + 1);
            } else {
                // Record is eligible
                AdaptiveDatasetRowDto row = buildDatasetRow(outcome);
                if (row != null) {
                    eligibleRows.add(row);
                } else {
                    String reasonKey = AdaptiveExclusionReason.OUTCOME_EXCLUDED.name();
                    exclusionBreakdown.put(reasonKey, exclusionBreakdown.getOrDefault(reasonKey, 0L) + 1);
                }
            }
        }

        // Deterministic sorting of eligible rows: by taskId ASC, decisionId ASC, rowId ASC
        eligibleRows.sort(Comparator
                .comparing(AdaptiveDatasetRowDto::taskId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(AdaptiveDatasetRowDto::decisionId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(AdaptiveDatasetRowDto::rowId, Comparator.nullsLast(Comparator.naturalOrder())));

        String datasetSha256 = computeCanonicalDatasetSha256(eligibleRows);
        long eligibleCount = eligibleRows.size();
        long excludedCount = totalEvaluated - eligibleCount;
        boolean sufficientEvidence = eligibleCount >= minimumRequiredSamples;

        AdaptiveDatasetQualityReportDto report = new AdaptiveDatasetQualityReportDto(
                totalEvaluated,
                eligibleCount,
                excludedCount,
                exclusionBreakdown,
                datasetSha256,
                sufficientEvidence,
                minimumRequiredSamples,
                Instant.now()
        );

        return new DatasetResult(eligibleRows, report);
    }

    /**
     * Produces a standalone dataset quality report.
     */
    @Transactional(readOnly = true)
    public AdaptiveDatasetQualityReportDto generateQualityReport() {
        return buildDataset().qualityReport();
    }

    /**
     * Evaluates whether an outcome is eligible for the Adaptive dataset or returns the specific exclusion reason.
     */
    public Optional<AdaptiveExclusionReason> evaluateOutcomeEligibility(
            RecommendationTaskOutcomeEntity outcome,
            Set<String> seenDecisionTaskPairs) {

        if (outcome == null) {
            return Optional.of(AdaptiveExclusionReason.OUTCOME_NOT_FOUND);
        }

        // 1. Outcome evaluation status
        if (outcome.getOutcomeType() == TaskOutcomeType.EXCLUDED) {
            return Optional.of(AdaptiveExclusionReason.OUTCOME_EXCLUDED);
        }
        if (outcome.getOutcomeType() != TaskOutcomeType.COMPLETED_ON_TIME
                && outcome.getOutcomeType() != TaskOutcomeType.COMPLETED_LATE) {
            return Optional.of(AdaptiveExclusionReason.OUTCOME_NOT_COMPLETED);
        }

        // 2. Duplicate detection
        String dedupKey = outcome.getDecisionId() + ":" + outcome.getTaskId();
        if (seenDecisionTaskPairs != null && !seenDecisionTaskPairs.add(dedupKey)) {
            return Optional.of(AdaptiveExclusionReason.DUPLICATE_SAMPLE);
        }

        // 3. Decision resolution
        if (outcome.getDecisionId() == null || outcome.getDecisionId().isBlank()) {
            return Optional.of(AdaptiveExclusionReason.DECISION_NOT_FOUND);
        }
        Optional<RecommendationDecisionEventEntity> decisionOpt = decisionPort.findById(outcome.getDecisionId());
        if (decisionOpt.isEmpty()) {
            return Optional.of(AdaptiveExclusionReason.DECISION_NOT_FOUND);
        }
        RecommendationDecisionEventEntity decision = decisionOpt.get();

        // 4. Decision source: must be USER
        if (decision.getDecisionSource() != RecommendationDecisionSource.USER) {
            return Optional.of(AdaptiveExclusionReason.NON_USER_DECISION);
        }

        // 5. Decision type: must be terminal assignment (ACCEPTED or OVERRIDDEN)
        if (decision.getDecisionType() != RecommendationDecisionType.ACCEPTED
                && decision.getDecisionType() != RecommendationDecisionType.OVERRIDDEN) {
            return Optional.of(AdaptiveExclusionReason.NON_ASSIGNMENT_DECISION);
        }

        // 6. Linkage agreement: task ID and project ID
        if (decision.getTaskId() != null && outcome.getTaskId() != null
                && !Objects.equals(decision.getTaskId(), outcome.getTaskId())) {
            return Optional.of(AdaptiveExclusionReason.DECISION_TASK_MISMATCH);
        }
        if (decision.getProjectId() != null && outcome.getProjectId() != null
                && !Objects.equals(decision.getProjectId(), outcome.getProjectId())) {
            return Optional.of(AdaptiveExclusionReason.DECISION_PROJECT_MISMATCH);
        }

        // 7. Assignee agreement
        if (decision.getSelectedCandidateId() == null) {
            return Optional.of(AdaptiveExclusionReason.SELECTED_CANDIDATE_NOT_FOUND);
        }
        if (outcome.getObservedAssigneeId() != null
                && !Objects.equals(decision.getSelectedCandidateId(), outcome.getObservedAssigneeId())) {
            return Optional.of(AdaptiveExclusionReason.ASSIGNEE_MISMATCH);
        }

        // 8. Snapshot resolution
        String snapshotId = outcome.getSnapshotId() != null ? outcome.getSnapshotId() : decision.getSnapshotId();
        if (snapshotId == null || snapshotId.isBlank()) {
            return Optional.of(AdaptiveExclusionReason.SNAPSHOT_NOT_FOUND);
        }
        Optional<RecommendationSnapshotEntity> snapOpt = snapshotPort.findById(snapshotId);
        if (snapOpt.isEmpty()) {
            return Optional.of(AdaptiveExclusionReason.SNAPSHOT_NOT_FOUND);
        }
        RecommendationSnapshotEntity snapshot = snapOpt.get();

        // 9. Snapshot project agreement
        if (!Objects.equals(snapshot.getProjectId(), outcome.getProjectId())) {
            return Optional.of(AdaptiveExclusionReason.DECISION_PROJECT_MISMATCH);
        }
        if (snapshot.getTaskId() != null && outcome.getTaskId() != null
                && !Objects.equals(snapshot.getTaskId(), outcome.getTaskId())) {
            return Optional.of(AdaptiveExclusionReason.DECISION_TASK_MISMATCH);
        }

        // 10. Presentation contract version: allowlisted-view-v2
        if (!EXPECTED_PRESENTATION_CONTRACT_VERSION.equalsIgnoreCase(snapshot.getPresentationContractVersion())) {
            return Optional.of(AdaptiveExclusionReason.UNSUPPORTED_SNAPSHOT_VERSION);
        }

        // 11. Differentiation status: must not be UNOBSERVED
        if ("UNOBSERVED".equalsIgnoreCase(snapshot.getDifferentiationStatus())) {
            return Optional.of(AdaptiveExclusionReason.UNOBSERVED_SNAPSHOT);
        }

        // 12. Selected candidate presence in snapshot
        if (snapshot.getCandidates() == null || snapshot.getCandidates().isEmpty()) {
            return Optional.of(AdaptiveExclusionReason.SELECTED_CANDIDATE_NOT_FOUND);
        }

        RecommendationSnapshotCandidateEntity candidate = snapshot.getCandidates().stream()
                .filter(c -> Objects.equals(c.getCandidateId(), decision.getSelectedCandidateId()))
                .findFirst()
                .orElse(null);

        if (candidate == null) {
            return Optional.of(AdaptiveExclusionReason.SELECTED_CANDIDATE_NOT_FOUND);
        }

        // 13. Skill Fit status & value validation
        if (candidate.getFitStatus() != MetricDataStatus.MEASURED) {
            return Optional.of(AdaptiveExclusionReason.FIT_STATUS_NOT_MEASURED);
        }
        Double fitVal = candidate.getPresentationFitValue() != null
                ? candidate.getPresentationFitValue()
                : candidate.getRankingRawFit();
        if (fitVal == null || Double.isNaN(fitVal) || Double.isInfinite(fitVal) || fitVal < 0.0 || fitVal > 1.0) {
            return Optional.of(AdaptiveExclusionReason.INVALID_FIT_VALUE);
        }

        // 14. Workload status & criteria validation
        if (candidate.getWorkloadStatus() != MetricDataStatus.MEASURED) {
            return Optional.of(AdaptiveExclusionReason.WORKLOAD_STATUS_NOT_MEASURED);
        }
        if (!EXPECTED_WORKLOAD_UNIT.equalsIgnoreCase(candidate.getWorkloadUnit())) {
            return Optional.of(AdaptiveExclusionReason.UNSUPPORTED_WORKLOAD_UNIT);
        }
        if (!EXPECTED_WORKLOAD_SCOPE.equalsIgnoreCase(candidate.getWorkloadScope())) {
            return Optional.of(AdaptiveExclusionReason.UNSUPPORTED_WORKLOAD_SCOPE);
        }
        if (candidate.getWorkloadMeasuredAt() == null) {
            return Optional.of(AdaptiveExclusionReason.MISSING_WORKLOAD_TIMESTAMP);
        }
        if (candidate.getStoredWorkloadValue() == null || candidate.getStoredWorkloadValue() < 0) {
            return Optional.of(AdaptiveExclusionReason.INVALID_WORKLOAD_VALUE);
        }

        // 15. All criteria passed
        return Optional.empty();
    }

    private AdaptiveDatasetRowDto buildDatasetRow(RecommendationTaskOutcomeEntity outcome) {
        RecommendationDecisionEventEntity decision = decisionPort.findById(outcome.getDecisionId()).orElse(null);
        if (decision == null) {
            return null;
        }

        RecommendationSnapshotEntity snapshot = snapshotPort.findById(outcome.getSnapshotId()).orElse(null);
        if (snapshot == null) {
            return null;
        }

        RecommendationSnapshotCandidateEntity selectedCandidate = snapshot.getCandidates().stream()
                .filter(c -> Objects.equals(c.getCandidateId(), decision.getSelectedCandidateId()))
                .findFirst()
                .orElse(null);

        if (selectedCandidate == null) {
            return null;
        }

        double fit = selectedCandidate.getPresentationFitValue() != null
                ? selectedCandidate.getPresentationFitValue()
                : selectedCandidate.getRankingRawFit();
        double load = selectedCandidate.getStoredWorkloadValue() != null
                ? selectedCandidate.getStoredWorkloadValue().doubleValue()
                : 0.0;

        List<CandidateFeatureDto> candidateFeatures = new ArrayList<>();
        if (snapshot.getCandidates() != null) {
            for (RecommendationSnapshotCandidateEntity c : snapshot.getCandidates()) {
                double cFit = c.getPresentationFitValue() != null ? c.getPresentationFitValue() : c.getRankingRawFit();
                double cLoad = c.getStoredWorkloadValue() != null ? c.getStoredWorkloadValue().doubleValue() : 0.0;
                boolean isRec = Boolean.TRUE.equals(c.getSelectedAsRecommendation()) || (c.getRank() != null && c.getRank() == 1);
                boolean isSel = Objects.equals(c.getCandidateId(), decision.getSelectedCandidateId());

                candidateFeatures.add(new CandidateFeatureDto(c.getCandidateId(), cFit, cLoad, isRec, isSel));
            }
        }
        // Deterministic candidate sorting
        candidateFeatures.sort(Comparator.comparing(CandidateFeatureDto::candidateId));

        boolean completedOnTime = outcome.getOutcomeType() == TaskOutcomeType.COMPLETED_ON_TIME;

        return new AdaptiveDatasetRowDto(
                outcome.getOutcomeId(),
                snapshot.getSnapshotId(),
                decision.getDecisionId(),
                outcome.getTaskId(),
                outcome.getProjectId(),
                decision.getSelectedCandidateId(),
                decision.getRecommendedCandidateId(),
                decision.getDecisionType(),
                outcome.getOutcomeType(),
                completedOnTime,
                fit,
                load,
                candidateFeatures,
                outcome.getObservedAt()
        );
    }

    private String computeCanonicalDatasetSha256(List<AdaptiveDatasetRowDto> rows) {
        if (rows == null || rows.isEmpty()) {
            return computeSha256(new byte[0]);
        }
        try {
            byte[] jsonBytes = CANONICAL_MAPPER.writeValueAsBytes(rows);
            return computeSha256(jsonBytes);
        } catch (Exception e) {
            log.error("[AdaptiveDataset] Failed to serialize canonical dataset for hashing: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to serialize canonical dataset", e);
        }
    }

    private String computeSha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm unavailable", e);
        }
    }

    public void setMinimumRequiredSamples(int minimumRequiredSamples) {
        this.minimumRequiredSamples = minimumRequiredSamples;
    }
}
