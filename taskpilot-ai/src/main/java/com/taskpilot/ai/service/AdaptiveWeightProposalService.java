package com.taskpilot.ai.service;

import com.taskpilot.ai.dto.*;
import com.taskpilot.ai.entity.AdaptiveWeightProposalEntity;
import com.taskpilot.ai.heuristic.*;
import com.taskpilot.ai.repository.AdaptiveWeightProposalRepository;
import com.taskpilot.infrastructure.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Service for generating, evaluating, and auditing offline shadow Adaptive Weight Proposals (Phase 2F).
 *
 * Absolute Safety Invariant (H-005, H-024):
 * This service operates strictly offline. Approving a proposal NEVER mutates active production weights
 * in system settings or HeuristicConfigProvider.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdaptiveWeightProposalService {

    public static final String DATASET_VERSION_V1 = "dataset-v1";
    public static final String ALGORITHM_VERSION_V1 = "grid-search-v1";
    public static final String PROPOSAL_VERSION_V1 = "proposal-v1";
    public static final double GRID_STEP = 0.05;
    public static final double MIN_FIT_WEIGHT = 0.10;
    public static final double MAX_FIT_WEIGHT = 0.90;
    public static final int MINIMUM_ELIGIBLE_SAMPLES = 20;

    private final AdaptiveDatasetService datasetService;
    private final AdaptiveWeightProposalRepository proposalRepository;
    private final HeuristicStrategyFactory heuristicStrategyFactory;

    private record GridCandidate(
            int index,
            double fitWeight,
            double loadWeight,
            double histAgreement,
            double onTimeAssoc,
            double weightDelta
    ) {
    }

    private record SimulatedCandidate(
            Long candidateId,
            double rawFit,
            long rankingKey
    ) {
    }

    /**
     * Generates a deterministic offline shadow weight proposal from the current Adaptive dataset.
     */
    @Transactional
    public AdaptiveWeightProposalDto generateProposal(Long requestingUserId) {
        if (requestingUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Authenticated user ID is required");
        }

        AdaptiveDatasetService.DatasetResult datasetResult = datasetService.buildDataset();
        List<AdaptiveDatasetRowDto> eligibleRows = datasetResult.eligibleRows();
        AdaptiveDatasetQualityReportDto report = datasetResult.qualityReport();

        HeuristicStrategy currentStrategy = heuristicStrategyFactory.resolve("BALANCED");
        HeuristicWeights currentWeights = currentStrategy.weights();
        double currentFit = currentWeights.fitWeight();
        double currentLoad = currentWeights.loadWeight();
        double currentPerf = currentWeights.performanceWeight();

        // Two-feature Adaptive baseline normalization (Section 10)
        double denominator = currentFit + currentLoad;
        if (Double.isNaN(denominator) || Double.isInfinite(denominator) || denominator <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Invalid current heuristic weights: fitWeight + loadWeight must be positive and finite");
        }
        double currentAdaptiveFit = currentFit / denominator;
        double currentAdaptiveLoad = currentLoad / denominator;
        if (Math.abs((currentAdaptiveFit + currentAdaptiveLoad) - 1.0) > 1e-6) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Normalized baseline weights must sum to 1.0 within numerical tolerance");
        }

        String proposalId = "prop-" + UUID.randomUUID();
        Instant now = Instant.now();

        Instant evaluationWindowStart = eligibleRows.stream()
                .map(AdaptiveDatasetRowDto::observedAt)
                .filter(Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(eligibleRows.isEmpty() ? null : now);
        Instant evaluationWindowEnd = eligibleRows.stream()
                .map(AdaptiveDatasetRowDto::observedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(eligibleRows.isEmpty() ? null : now);

        // Check Minimum Evidence Gate (Section 9)
        if (eligibleRows.size() < report.minimumRequiredSamples()) {
            log.info("[AdaptiveProposal] Insufficient eligible evidence: {}/{} samples. Generating truthful INSUFFICIENT_EVIDENCE proposal {}.",
                    eligibleRows.size(), report.minimumRequiredSamples(), proposalId);

            AdaptiveWeightProposalEntity entity = AdaptiveWeightProposalEntity.builder()
                    .proposalId(proposalId)
                    .status(WeightProposalStatus.INSUFFICIENT_EVIDENCE)
                    .targetHeuristicMode("BALANCED")
                    .datasetVersion(DATASET_VERSION_V1)
                    .datasetHash(report.canonicalDatasetSha256())
                    .datasetSampleCount(eligibleRows.size())
                    .currentFitWeight(currentFit)
                    .currentLoadWeight(currentLoad)
                    .currentPerformanceWeight(currentPerf)
                    .currentAdaptiveFitWeight(currentAdaptiveFit)
                    .currentAdaptiveLoadWeight(currentAdaptiveLoad)
                    .proposedFitWeight(null)
                    .proposedLoadWeight(null)
                    .proposedPerformanceWeight(null)
                    .gridStep(null)
                    .currentMetricHistoricalAgreement(null)
                    .currentMetricOnTimeAssociation(null)
                    .metricHistoricalAgreement(null)
                    .metricOnTimeAssociation(null)
                    .evaluationWindowStart(evaluationWindowStart)
                    .evaluationWindowEnd(evaluationWindowEnd)
                    .algorithmVersion(ALGORITHM_VERSION_V1)
                    .createdAt(now)
                    .createdByUserId(requestingUserId)
                    .proposalVersion(PROPOSAL_VERSION_V1)
                    .build();

            AdaptiveWeightProposalEntity saved = proposalRepository.save(entity);
            return mapToDto(saved);
        }

        // Calculate baseline metrics on eligible rows under normalized two-feature space
        int baselineAgreementCount = 0;
        int baselineOnTimeCount = 0;
        for (AdaptiveDatasetRowDto row : eligibleRows) {
            Long topCandidateId = simulateTopCandidate(row, currentAdaptiveFit, currentAdaptiveLoad);
            if (topCandidateId != null && topCandidateId.equals(row.selectedCandidateId())) {
                baselineAgreementCount++;
                if (row.completedOnTime()) {
                    baselineOnTimeCount++;
                }
            }
        }
        double baselineAgreement = (double) baselineAgreementCount / eligibleRows.size();
        double baselineOnTimeAssoc = (double) baselineOnTimeCount / eligibleRows.size();

        // Run deterministic offline grid search over [0.10, 0.90] with step 0.05
        List<GridCandidate> evaluatedCandidates = evaluateGridSearch(eligibleRows, currentAdaptiveFit, currentAdaptiveLoad);

        // Deterministic 5-level tie-break
        GridCandidate best = selectBestCandidate(evaluatedCandidates);

        log.info("[AdaptiveProposal] Generated proposal {} with proposedFit={} proposedLoad={} (histAgreement={}, onTimeAssoc={}) from {} samples",
                proposalId, best.fitWeight(), best.loadWeight(), best.histAgreement(), best.onTimeAssoc(), eligibleRows.size());

        AdaptiveWeightProposalEntity entity = AdaptiveWeightProposalEntity.builder()
                .proposalId(proposalId)
                .status(WeightProposalStatus.READY_FOR_REVIEW)
                .targetHeuristicMode("BALANCED")
                .datasetVersion(DATASET_VERSION_V1)
                .datasetHash(report.canonicalDatasetSha256())
                .datasetSampleCount(eligibleRows.size())
                .currentFitWeight(currentFit)
                .currentLoadWeight(currentLoad)
                .currentPerformanceWeight(currentPerf)
                .currentAdaptiveFitWeight(currentAdaptiveFit)
                .currentAdaptiveLoadWeight(currentAdaptiveLoad)
                .proposedFitWeight(best.fitWeight())
                .proposedLoadWeight(best.loadWeight())
                .proposedPerformanceWeight(0.0)
                .gridStep(GRID_STEP)
                .currentMetricHistoricalAgreement(baselineAgreement)
                .currentMetricOnTimeAssociation(baselineOnTimeAssoc)
                .metricHistoricalAgreement(best.histAgreement())
                .metricOnTimeAssociation(best.onTimeAssoc())
                .evaluationWindowStart(evaluationWindowStart)
                .evaluationWindowEnd(evaluationWindowEnd)
                .algorithmVersion(ALGORITHM_VERSION_V1)
                .createdAt(now)
                .createdByUserId(requestingUserId)
                .proposalVersion(PROPOSAL_VERSION_V1)
                .build();

        AdaptiveWeightProposalEntity saved = proposalRepository.save(entity);
        return mapToDto(saved);
    }

    /**
     * Approves an offline proposal.
     * Enforces the absolute no-activation guarantee: does NOT update system settings or heuristic weights.
     */
    @Transactional
    public AdaptiveWeightProposalDto approveProposal(String proposalId, Long adminUserId, String reviewNotes) {
        if (proposalId == null || proposalId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Proposal ID is required");
        }
        if (adminUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Admin user ID is required");
        }

        AdaptiveWeightProposalEntity proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Proposal not found: " + proposalId));

        if (proposal.getStatus() != WeightProposalStatus.READY_FOR_REVIEW) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Proposal cannot be approved in status: " + proposal.getStatus());
        }

        validateProposalIntegrityForReview(proposal);

        proposal.setStatus(WeightProposalStatus.APPROVED);
        proposal.setReviewedAt(Instant.now());
        proposal.setReviewedByUserId(adminUserId);
        proposal.setReviewNotes(reviewNotes != null && reviewNotes.length() > 1000 ? reviewNotes.substring(0, 1000) : reviewNotes);

        AdaptiveWeightProposalEntity updated = proposalRepository.save(proposal);
        log.info("[AdaptiveProposal] Proposal {} APPROVED by admin {}. Active weights remain unchanged.",
                proposalId, adminUserId);

        return mapToDto(updated);
    }

    /**
     * Rejects an offline proposal.
     */
    @Transactional
    public AdaptiveWeightProposalDto rejectProposal(String proposalId, Long adminUserId, String rejectionReason) {
        if (proposalId == null || proposalId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Proposal ID is required");
        }
        if (adminUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Admin user ID is required");
        }

        AdaptiveWeightProposalEntity proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Proposal not found: " + proposalId));

        if (proposal.getStatus() != WeightProposalStatus.READY_FOR_REVIEW) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Proposal cannot be rejected in status: " + proposal.getStatus());
        }

        validateProposalIntegrityForReview(proposal);

        proposal.setStatus(WeightProposalStatus.REJECTED);
        proposal.setReviewedAt(Instant.now());
        proposal.setReviewedByUserId(adminUserId);
        proposal.setRejectionReason(rejectionReason != null && rejectionReason.length() > 1000 ? rejectionReason.substring(0, 1000) : rejectionReason);

        AdaptiveWeightProposalEntity updated = proposalRepository.save(proposal);
        log.info("[AdaptiveProposal] Proposal {} REJECTED by admin {}.", proposalId, adminUserId);

        return mapToDto(updated);
    }

    /**
     * Validates proposal integrity before allowing Admin review (Section 6 & Section 12).
     */
    public void validateProposalIntegrityForReview(AdaptiveWeightProposalEntity proposal) {
        if (proposal == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Proposal cannot be null");
        }
        if (proposal.getTargetHeuristicMode() == null || !"BALANCED".equalsIgnoreCase(proposal.getTargetHeuristicMode().trim())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Target heuristic mode must be BALANCED for Adaptive MVP proposals, but found: " + proposal.getTargetHeuristicMode());
        }
        if (!PROPOSAL_VERSION_V1.equals(proposal.getProposalVersion())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Unsupported proposal version: " + proposal.getProposalVersion());
        }
        if (!DATASET_VERSION_V1.equals(proposal.getDatasetVersion())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Unsupported dataset version: " + proposal.getDatasetVersion());
        }
        if (!ALGORITHM_VERSION_V1.equals(proposal.getAlgorithmVersion())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Unsupported algorithm version: " + proposal.getAlgorithmVersion());
        }
        if (proposal.getDatasetHash() == null || proposal.getDatasetHash().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Dataset hash must be present");
        }
        if (proposal.getDatasetSampleCount() == null || proposal.getDatasetSampleCount() < MINIMUM_ELIGIBLE_SAMPLES) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Sample count must be at least 20 for reviewable proposals, but found: " + proposal.getDatasetSampleCount());
        }
        if (proposal.getProposedFitWeight() == null || proposal.getProposedLoadWeight() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Proposed weights must be present");
        }
        double propFit = proposal.getProposedFitWeight();
        double propLoad = proposal.getProposedLoadWeight();
        if (Double.isNaN(propFit) || Double.isInfinite(propFit) || Double.isNaN(propLoad) || Double.isInfinite(propLoad)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Proposed weights must be finite");
        }
        if (propFit < MIN_FIT_WEIGHT - 1e-6 || propFit > MAX_FIT_WEIGHT + 1e-6 || propLoad < 0.0 || propLoad > 1.0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Proposed weights must be within configured safe bounds [0.10, 0.90]");
        }
        if (Math.abs((propFit + propLoad) - 1.0) > 1e-4) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Proposed weights must sum to 1.0 within numerical tolerance");
        }
        if (proposal.getMetricHistoricalAgreement() == null || proposal.getMetricOnTimeAssociation() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Calculated metrics must be present");
        }
        if (Double.isNaN(proposal.getMetricHistoricalAgreement()) || Double.isNaN(proposal.getMetricOnTimeAssociation())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Calculated metrics must be finite");
        }
        if (proposal.getCurrentAdaptiveFitWeight() == null || proposal.getCurrentAdaptiveLoadWeight() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Current Adaptive baseline must be present");
        }
        if (Math.abs((proposal.getCurrentAdaptiveFitWeight() + proposal.getCurrentAdaptiveLoadWeight()) - 1.0) > 1e-4) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Current Adaptive baseline must sum to 1.0");
        }
        if (proposal.getEvaluationWindowStart() == null || proposal.getEvaluationWindowEnd() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Evaluation window must be present");
        }
        if (proposal.getCreatedAt() == null || proposal.getCreatedByUserId() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Created metadata must be present");
        }
    }

    @Transactional(readOnly = true)
    public AdaptiveWeightProposalDto getLatestProposal() {
        return proposalRepository.findTopByOrderByCreatedAtDesc()
                .map(this::mapToDto)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "No proposals found"));
    }

    @Transactional(readOnly = true)
    public AdaptiveWeightProposalDto getProposalById(String proposalId) {
        if (proposalId == null || proposalId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Proposal ID is required");
        }
        return proposalRepository.findById(proposalId)
                .map(this::mapToDto)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Proposal not found: " + proposalId));
    }

    @Transactional(readOnly = true)
    public List<AdaptiveWeightProposalDto> getAllProposals() {
        return proposalRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::mapToDto)
                .toList();
    }

    private List<GridCandidate> evaluateGridSearch(
            List<AdaptiveDatasetRowDto> eligibleRows,
            double currentAdaptiveFit,
            double currentAdaptiveLoad) {

        List<GridCandidate> list = new ArrayList<>();
        int index = 0;

        // Iterate fit weights from 0.10 to 0.90 with step 0.05 (17 total candidate pairs)
        for (double fit = MIN_FIT_WEIGHT; fit <= MAX_FIT_WEIGHT + 1e-6; fit += GRID_STEP) {
            double fitWeight = round2(fit);
            double loadWeight = round2(1.0 - fitWeight);

            int agreementCount = 0;
            int onTimeAgreementCount = 0;

            for (AdaptiveDatasetRowDto row : eligibleRows) {
                Long simulatedTopCandidateId = simulateTopCandidate(row, fitWeight, loadWeight);
                if (simulatedTopCandidateId != null && simulatedTopCandidateId.equals(row.selectedCandidateId())) {
                    agreementCount++;
                    if (row.completedOnTime()) {
                        onTimeAgreementCount++;
                    }
                }
            }

            double histAgreement = (double) agreementCount / eligibleRows.size();
            double onTimeAssoc = (double) onTimeAgreementCount / eligibleRows.size();
            // Compare weight delta against normalized two-feature baseline (Section 10)
            double weightDelta = Math.abs(fitWeight - currentAdaptiveFit) + Math.abs(loadWeight - currentAdaptiveLoad);

            list.add(new GridCandidate(index++, fitWeight, loadWeight, histAgreement, onTimeAssoc, weightDelta));
        }

        return list;
    }

    private Long simulateTopCandidate(AdaptiveDatasetRowDto row, double fitWeight, double loadWeight) {
        List<CandidateFeatureDto> candidates = row.candidates();
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        double fitMin = candidates.stream().mapToDouble(CandidateFeatureDto::rawFit).min().orElse(0.0);
        double fitMax = candidates.stream().mapToDouble(CandidateFeatureDto::rawFit).max().orElse(1.0);
        double loadMin = candidates.stream().mapToDouble(CandidateFeatureDto::rawLoad).min().orElse(0.0);
        double loadMax = candidates.stream().mapToDouble(CandidateFeatureDto::rawLoad).max().orElse(1.0);

        ScoreRange fitRange = new ScoreRange(fitMin, fitMax);
        ScoreRange loadRange = new ScoreRange(loadMin, loadMax);

        List<SimulatedCandidate> scored = new ArrayList<>();
        for (CandidateFeatureDto c : candidates) {
            double normFit = fitRange.normalizeNeutral(c.rawFit(), HeuristicNormalization.BENCHMARK_BENEFIT);
            double normLoad = loadRange.normalizeNeutral(c.rawLoad(), HeuristicNormalization.BENCHMARK_BENEFIT);
            double totalScore = (fitWeight * normFit) - (loadWeight * normLoad);
            long rankingKey = Math.round(totalScore * 1_000_000_000L);

            scored.add(new SimulatedCandidate(c.candidateId(), c.rawFit(), rankingKey));
        }

        // Exact replica of InternalCandidateRanking.STEP_A_COMPARATOR:
        // rankingKey DESC, rawFit DESC, candidateId ASC
        scored.sort(Comparator
                .comparingLong(SimulatedCandidate::rankingKey).reversed()
                .thenComparing(Comparator.comparingDouble(SimulatedCandidate::rawFit).reversed())
                .thenComparingLong(SimulatedCandidate::candidateId));

        return scored.get(0).candidateId();
    }

    private GridCandidate selectBestCandidate(List<GridCandidate> candidates) {
        return candidates.stream()
                .min((a, b) -> {
                    // 1. Higher primary metric: historical agreement DESC
                    int c1 = Double.compare(b.histAgreement(), a.histAgreement());
                    if (c1 != 0) return c1;

                    // 2. Higher secondary metric: on-time association DESC
                    int c2 = Double.compare(b.onTimeAssoc(), a.onTimeAssoc());
                    if (c2 != 0) return c2;

                    // 3. Smaller absolute weight delta ASC
                    int c3 = Double.compare(a.weightDelta(), b.weightDelta());
                    if (c3 != 0) return c3;

                    // 4. Higher fit weight DESC
                    int c4 = Double.compare(b.fitWeight(), a.fitWeight());
                    if (c4 != 0) return c4;

                    // 5. Stable numeric order ASC
                    return Integer.compare(a.index(), b.index());
                })
                .orElseThrow(() -> new IllegalStateException("Grid search candidate list is empty"));
    }

    private double round2(double val) {
        return Math.round(val * 100.0) / 100.0;
    }

    private AdaptiveWeightProposalDto mapToDto(AdaptiveWeightProposalEntity entity) {
        return new AdaptiveWeightProposalDto(
                entity.getProposalId(),
                entity.getStatus(),
                entity.getTargetHeuristicMode(),
                entity.getDatasetVersion(),
                entity.getDatasetHash(),
                entity.getDatasetSampleCount(),
                entity.getCurrentFitWeight(),
                entity.getCurrentLoadWeight(),
                entity.getCurrentPerformanceWeight(),
                entity.getCurrentAdaptiveFitWeight(),
                entity.getCurrentAdaptiveLoadWeight(),
                entity.getProposedFitWeight(),
                entity.getProposedLoadWeight(),
                entity.getProposedPerformanceWeight(),
                entity.getGridStep(),
                entity.getCurrentMetricHistoricalAgreement(),
                entity.getCurrentMetricOnTimeAssociation(),
                entity.getMetricHistoricalAgreement(),
                entity.getMetricOnTimeAssociation(),
                entity.getEvaluationWindowStart(),
                entity.getEvaluationWindowEnd(),
                entity.getAlgorithmVersion(),
                entity.getProposalVersion(),
                entity.getCreatedAt(),
                entity.getCreatedByUserId(),
                entity.getReviewedAt(),
                entity.getReviewedByUserId(),
                entity.getReviewNotes(),
                entity.getRejectionReason()
        );
    }
}
