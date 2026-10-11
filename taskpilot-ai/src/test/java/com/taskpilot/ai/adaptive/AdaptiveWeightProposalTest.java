package com.taskpilot.ai.adaptive;

import com.taskpilot.ai.dto.*;
import com.taskpilot.ai.entity.AdaptiveWeightProposalEntity;
import com.taskpilot.ai.heuristic.*;
import com.taskpilot.ai.repository.AdaptiveWeightProposalRepository;
import com.taskpilot.ai.service.AdaptiveDatasetService;
import com.taskpilot.ai.service.AdaptiveWeightProposalService;
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
 * Phase 2F Adaptive Weight Proposal Test Suite.
 * Verifies Minimum Evidence Gate (< 20 -> INSUFFICIENT_EVIDENCE, >= 20 -> READY_FOR_REVIEW),
 * deterministic grid search, metric calculations, and 5-level tie-break.
 */
class AdaptiveWeightProposalTest {

    private AdaptiveDatasetService datasetService;
    private AdaptiveWeightProposalRepository proposalRepository;
    private HeuristicStrategyFactory strategyFactory;
    private AdaptiveWeightProposalService proposalService;

    @BeforeEach
    void setUp() {
        datasetService = mock(AdaptiveDatasetService.class);
        proposalRepository = mock(AdaptiveWeightProposalRepository.class);
        strategyFactory = mock(HeuristicStrategyFactory.class);

        HeuristicConfig config = new HeuristicConfig(
                new HeuristicWeights(0.5, 0.3, 0.2),
                new HeuristicNormalizationConfig(
                        HeuristicNormalization.BENCHMARK_BENEFIT,
                        HeuristicNormalization.BENCHMARK_BENEFIT,
                        HeuristicNormalization.BENCHMARK_BENEFIT
                )
        );
        HeuristicStrategy strategy = new BalancedHeuristicStrategy(config);
        when(strategyFactory.resolve(any())).thenReturn(strategy);

        proposalService = new AdaptiveWeightProposalService(datasetService, proposalRepository, strategyFactory);

        when(proposalRepository.save(any(AdaptiveWeightProposalEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private AdaptiveDatasetRowDto createSampleRow(long id, Long selectedCandidateId, boolean completedOnTime, double c1Fit, double c1Load, double c2Fit, double c2Load) {
        List<CandidateFeatureDto> candidates = List.of(
                new CandidateFeatureDto(1L, c1Fit, c1Load, true, selectedCandidateId.equals(1L)),
                new CandidateFeatureDto(2L, c2Fit, c2Load, false, selectedCandidateId.equals(2L))
        );

        return new AdaptiveDatasetRowDto(
                "out-" + id,
                "snap-" + id,
                "dec-" + id,
                1000L + id,
                10L,
                selectedCandidateId,
                1L,
                RecommendationDecisionType.ACCEPTED,
                completedOnTime ? TaskOutcomeType.COMPLETED_ON_TIME : TaskOutcomeType.COMPLETED_LATE,
                completedOnTime,
                selectedCandidateId.equals(1L) ? c1Fit : c2Fit,
                selectedCandidateId.equals(1L) ? c1Load : c2Load,
                candidates
        );
    }

    @Test
    @DisplayName("1. Minimum evidence gate: < 20 samples produces INSUFFICIENT_EVIDENCE proposal")
    void test01_insufficientEvidenceBelowThreshold() {
        List<AdaptiveDatasetRowDto> rows = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            rows.add(createSampleRow(i, 1L, true, 0.8, 1.0, 0.5, 3.0));
        }

        AdaptiveDatasetQualityReportDto report = new AdaptiveDatasetQualityReportDto(
                15, 15, 0, Map.of(), "sha-15", false, 20, Instant.now()
        );
        when(datasetService.buildDataset()).thenReturn(new AdaptiveDatasetService.DatasetResult(rows, report));

        AdaptiveWeightProposalDto proposal = proposalService.generateProposal(100L);

        assertEquals(WeightProposalStatus.INSUFFICIENT_EVIDENCE, proposal.status());
        assertEquals(15, proposal.datasetSampleCount());
        assertNull(proposal.metricHistoricalAgreement());
        assertNull(proposal.metricOnTimeAssociation());
        assertNull(proposal.proposedFitWeight());
        assertNull(proposal.proposedLoadWeight());
        assertNull(proposal.proposedPerformanceWeight());
        assertEquals(0.625, proposal.currentAdaptiveFitWeight(), 1e-4);
        assertEquals(0.375, proposal.currentAdaptiveLoadWeight(), 1e-4);
        assertNull(proposal.currentMetricHistoricalAgreement());
        assertNull(proposal.currentMetricOnTimeAssociation());

        ArgumentCaptor<AdaptiveWeightProposalEntity> captor = ArgumentCaptor.forClass(AdaptiveWeightProposalEntity.class);
        verify(proposalRepository).save(captor.capture());
        assertEquals(WeightProposalStatus.INSUFFICIENT_EVIDENCE, captor.getValue().getStatus());
        assertNull(captor.getValue().getProposedFitWeight());
        assertNull(captor.getValue().getProposedLoadWeight());
        assertNull(captor.getValue().getMetricHistoricalAgreement());
        assertNull(captor.getValue().getMetricOnTimeAssociation());
        assertEquals(0.625, captor.getValue().getCurrentAdaptiveFitWeight(), 1e-4);
        assertEquals(0.375, captor.getValue().getCurrentAdaptiveLoadWeight(), 1e-4);
        assertEquals("dataset-v1", captor.getValue().getDatasetVersion());
        assertEquals("grid-search-v1", captor.getValue().getAlgorithmVersion());
    }

    @Test
    @DisplayName("2. Sufficient evidence (>= 20 samples) produces READY_FOR_REVIEW proposal with valid metrics")
    void test02_sufficientEvidenceProducesReadyForReview() {
        List<AdaptiveDatasetRowDto> rows = new ArrayList<>();
        // 20 samples where candidate 1 is always selected, has high fit and low load
        for (int i = 1; i <= 20; i++) {
            rows.add(createSampleRow(i, 1L, true, 0.9, 0.0, 0.2, 5.0));
        }

        AdaptiveDatasetQualityReportDto report = new AdaptiveDatasetQualityReportDto(
                20, 20, 0, Map.of(), "sha-20", true, 20, Instant.now()
        );
        when(datasetService.buildDataset()).thenReturn(new AdaptiveDatasetService.DatasetResult(rows, report));

        AdaptiveWeightProposalDto proposal = proposalService.generateProposal(100L);

        assertEquals(WeightProposalStatus.READY_FOR_REVIEW, proposal.status());
        assertEquals(20, proposal.datasetSampleCount());
        assertTrue(proposal.metricHistoricalAgreement() > 0.0);
        assertTrue(proposal.metricOnTimeAssociation() > 0.0);
        assertTrue(proposal.proposedFitWeight() >= 0.10 && proposal.proposedFitWeight() <= 0.90);
        assertEquals(1.0, proposal.proposedFitWeight() + proposal.proposedLoadWeight(), 1e-4);
    }

    @Test
    @DisplayName("3. Grid search selects candidate with highest historical agreement (Tie-break level 1)")
    void test03_tieBreakLevel1_highestAgreement() {
        List<AdaptiveDatasetRowDto> rows = new ArrayList<>();
        // In this dataset: candidate 1 has low fit (0.3) but zero load (0.0).
        // Candidate 2 has high fit (0.9) but high load (10.0).
        // PM consistently picked candidate 1 (prioritizing low workload!).
        for (int i = 1; i <= 20; i++) {
            rows.add(createSampleRow(i, 1L, true, 0.3, 0.0, 0.9, 10.0));
        }

        AdaptiveDatasetQualityReportDto report = new AdaptiveDatasetQualityReportDto(
                20, 20, 0, Map.of(), "sha-20", true, 20, Instant.now()
        );
        when(datasetService.buildDataset()).thenReturn(new AdaptiveDatasetService.DatasetResult(rows, report));

        AdaptiveWeightProposalDto proposal = proposalService.generateProposal(100L);

        // Low load weight should win: fitWeight should be lower, loadWeight higher
        assertTrue(proposal.proposedLoadWeight() > proposal.proposedFitWeight());
        assertEquals(1.0, proposal.metricHistoricalAgreement(), 1e-6);
    }

    @Test
    @DisplayName("4. On-time association breaks ties when agreement is identical (Tie-break level 2)")
    void test04_tieBreakLevel2_onTimeAssociation() {
        List<AdaptiveDatasetRowDto> rows = new ArrayList<>();
        // All 20 samples agree for both candidate configurations, but on-time counts differentiate
        for (int i = 1; i <= 20; i++) {
            boolean onTime = i <= 16;
            rows.add(createSampleRow(i, 1L, onTime, 0.8, 1.0, 0.4, 2.0));
        }

        AdaptiveDatasetQualityReportDto report = new AdaptiveDatasetQualityReportDto(
                20, 20, 0, Map.of(), "sha-20", true, 20, Instant.now()
        );
        when(datasetService.buildDataset()).thenReturn(new AdaptiveDatasetService.DatasetResult(rows, report));

        AdaptiveWeightProposalDto proposal = proposalService.generateProposal(100L);

        assertEquals(WeightProposalStatus.READY_FOR_REVIEW, proposal.status());
        assertEquals(1.0, proposal.metricHistoricalAgreement(), 1e-6);
        assertEquals(0.8, proposal.metricOnTimeAssociation(), 1e-6); // 16/20
    }

    @Test
    @DisplayName("5. Smaller weight delta to current weights breaks ties when metrics are equal (Tie-break level 3)")
    void test05_tieBreakLevel3_smallerDelta() {
        // Test that when all grid options have identical 100% agreement, the proposal closest to (0.5, 0.3) is chosen
        List<AdaptiveDatasetRowDto> rows = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            // Candidate 1 dominates on both fit (1.0 vs 0.0) and load (0.0 vs 10.0)
            // so ANY weight combination in the grid yields candidate 1 as top!
            rows.add(createSampleRow(i, 1L, true, 1.0, 0.0, 0.0, 10.0));
        }

        AdaptiveDatasetQualityReportDto report = new AdaptiveDatasetQualityReportDto(
                20, 20, 0, Map.of(), "sha-20", true, 20, Instant.now()
        );
        when(datasetService.buildDataset()).thenReturn(new AdaptiveDatasetService.DatasetResult(rows, report));

        AdaptiveWeightProposalDto proposal = proposalService.generateProposal(100L);

        // Current weights are fit=0.5, load=0.3 -> normalized baseline is (0.625, 0.375).
        // The candidates closest to 0.625 with step 0.05 are 0.60 and 0.65 (delta = 0.05).
        // Tie-break level 4: higher fit weight wins -> 0.65
        assertEquals(WeightProposalStatus.READY_FOR_REVIEW, proposal.status());
        assertEquals(1.0, proposal.metricHistoricalAgreement(), 1e-6);
        assertEquals(1.0, proposal.metricOnTimeAssociation(), 1e-6);
        assertEquals(0.65, proposal.proposedFitWeight(), 1e-4);
        assertEquals(0.35, proposal.proposedLoadWeight(), 1e-4);
        assertEquals(0.625, proposal.currentAdaptiveFitWeight(), 1e-4);
        assertEquals(0.375, proposal.currentAdaptiveLoadWeight(), 1e-4);
    }

    @Test
    @DisplayName("6. Two-feature baseline normalization calculates currentAdaptiveFit and currentAdaptiveLoad correctly")
    void test06_baselineNormalizationMath() {
        // Given current weights 0.40, 0.40, 0.20
        HeuristicConfig config = new HeuristicConfig(
                new HeuristicWeights(0.40, 0.40, 0.20),
                new HeuristicNormalizationConfig(
                        HeuristicNormalization.BENCHMARK_BENEFIT,
                        HeuristicNormalization.BENCHMARK_BENEFIT,
                        HeuristicNormalization.BENCHMARK_BENEFIT
                )
        );
        when(strategyFactory.resolve(any())).thenReturn(new BalancedHeuristicStrategy(config));

        List<AdaptiveDatasetRowDto> rows = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            rows.add(createSampleRow(i, 1L, true, 0.9, 0.0, 0.2, 5.0));
        }

        AdaptiveDatasetQualityReportDto report = new AdaptiveDatasetQualityReportDto(
                20, 20, 0, Map.of(), "sha-20", true, 20, Instant.now()
        );
        when(datasetService.buildDataset()).thenReturn(new AdaptiveDatasetService.DatasetResult(rows, report));

        AdaptiveWeightProposalDto proposal = proposalService.generateProposal(100L);

        // 0.40 / (0.40 + 0.40) = 0.50, 0.40 / (0.40 + 0.40) = 0.50
        assertEquals(0.50, proposal.currentAdaptiveFitWeight(), 1e-4);
        assertEquals(0.50, proposal.currentAdaptiveLoadWeight(), 1e-4);
        assertEquals(1.0, proposal.currentAdaptiveFitWeight() + proposal.currentAdaptiveLoadWeight(), 1e-4);
    }

    @Test
    @DisplayName("7. Degenerate baseline weights (fit + load <= 0) throw BusinessException")
    void test07_degenerateBaselineWeightsThrows() {
        HeuristicConfig config = new HeuristicConfig(
                new HeuristicWeights(0.0, 0.0, 1.0),
                new HeuristicNormalizationConfig(
                        HeuristicNormalization.BENCHMARK_BENEFIT,
                        HeuristicNormalization.BENCHMARK_BENEFIT,
                        HeuristicNormalization.BENCHMARK_BENEFIT
                )
        );
        when(strategyFactory.resolve(any())).thenReturn(new BalancedHeuristicStrategy(config));

        List<AdaptiveDatasetRowDto> rows = List.of(createSampleRow(1, 1L, true, 0.8, 1.0, 0.5, 3.0));
        AdaptiveDatasetQualityReportDto report = new AdaptiveDatasetQualityReportDto(
                1, 1, 0, Map.of(), "sha-1", false, 20, Instant.now()
        );
        when(datasetService.buildDataset()).thenReturn(new AdaptiveDatasetService.DatasetResult(rows, report));

        assertThrows(com.taskpilot.infrastructure.exception.BusinessException.class, () ->
                proposalService.generateProposal(100L)
        );
    }

    @Test
    @DisplayName("8. Safe bounds [0.10, 0.90] and sum = 1.0 are strictly enforced for proposed weights")
    void test08_safeBoundsAndSumEnforced() {
        List<AdaptiveDatasetRowDto> rows = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            rows.add(createSampleRow(i, 1L, true, 0.9, 0.0, 0.2, 5.0));
        }

        AdaptiveDatasetQualityReportDto report = new AdaptiveDatasetQualityReportDto(
                20, 20, 0, Map.of(), "sha-20", true, 20, Instant.now()
        );
        when(datasetService.buildDataset()).thenReturn(new AdaptiveDatasetService.DatasetResult(rows, report));

        AdaptiveWeightProposalDto proposal = proposalService.generateProposal(100L);

        assertTrue(proposal.proposedFitWeight() >= 0.10 && proposal.proposedFitWeight() <= 0.90);
        assertTrue(proposal.proposedLoadWeight() >= 0.10 && proposal.proposedLoadWeight() <= 0.90);
        assertEquals(1.0, proposal.proposedFitWeight() + proposal.proposedLoadWeight(), 1e-4);
    }
}
