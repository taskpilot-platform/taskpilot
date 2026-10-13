package com.taskpilot.ai.adaptive;

import com.taskpilot.ai.dto.*;
import com.taskpilot.ai.entity.AdaptiveWeightProposalEntity;
import com.taskpilot.ai.heuristic.*;
import com.taskpilot.ai.repository.AdaptiveWeightProposalRepository;
import com.taskpilot.ai.service.AdaptiveDatasetService;
import com.taskpilot.ai.service.AdaptiveWeightProposalService;
import com.taskpilot.infrastructure.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

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

    private AdaptiveDatasetRowDto createThreeCandidateRow(long id, Long selectedCandidateId, boolean completedOnTime,
                                                           double c1Fit, double c1Load,
                                                           double c2Fit, double c2Load,
                                                           double c3Fit, double c3Load) {
        List<CandidateFeatureDto> candidates = List.of(
                new CandidateFeatureDto(1L, c1Fit, c1Load, true, selectedCandidateId.equals(1L)),
                new CandidateFeatureDto(2L, c2Fit, c2Load, false, selectedCandidateId.equals(2L)),
                new CandidateFeatureDto(3L, c3Fit, c3Load, false, selectedCandidateId.equals(3L))
        );

        double selectedFit = selectedCandidateId.equals(1L) ? c1Fit : (selectedCandidateId.equals(2L) ? c2Fit : c3Fit);
        double selectedLoad = selectedCandidateId.equals(1L) ? c1Load : (selectedCandidateId.equals(2L) ? c2Load : c3Load);

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
                selectedFit,
                selectedLoad,
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
        assertEquals("BALANCED", proposal.targetHeuristicMode());
        assertEquals("BALANCED", captor.getValue().getTargetHeuristicMode());
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
        assertEquals("BALANCED", proposal.targetHeuristicMode());
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

        // Workload-dominant evidence shifts proposal toward Workload:
        // loadWeight increases above baseline (0.55 > 0.375), fitWeight decreases below baseline (0.45 < 0.625)
        assertEquals(WeightProposalStatus.READY_FOR_REVIEW, proposal.status());
        assertTrue(proposal.proposedLoadWeight() > proposal.proposedFitWeight());
        assertTrue(proposal.proposedLoadWeight() > proposal.currentAdaptiveLoadWeight());
        assertTrue(proposal.proposedFitWeight() < proposal.currentAdaptiveFitWeight());
        assertEquals(0.45, proposal.proposedFitWeight(), 1e-4);
        assertEquals(0.55, proposal.proposedLoadWeight(), 1e-4);
        assertTrue(proposal.proposedFitWeight() >= 0.10 && proposal.proposedFitWeight() <= 0.90);
        assertTrue(proposal.proposedLoadWeight() >= 0.10 && proposal.proposedLoadWeight() <= 0.90);
        assertEquals(1.0, proposal.proposedFitWeight() + proposal.proposedLoadWeight(), 1e-4);
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

    @Test
    @DisplayName("9. Fit-dominant evidence shifts proposal toward Fit above baseline (0.75 > 0.625)")
    void test09_fitDominantEvidence_movesProposalTowardFit() {
        List<AdaptiveDatasetRowDto> rows = new ArrayList<>();
        // 20 samples where candidate 1 has high skill fit (0.95) despite higher load (3.0),
        // candidate 2 has moderate fit (0.72) and low load (1.0),
        // candidate 3 has low fit (0.10) and zero load (0.0).
        // PM consistently selects candidate 1, prioritizing Skill Fit over low workload.
        for (int i = 1; i <= 20; i++) {
            rows.add(createThreeCandidateRow(i, 1L, true, 0.95, 3.0, 0.72, 1.0, 0.10, 0.0));
        }

        AdaptiveDatasetQualityReportDto report = new AdaptiveDatasetQualityReportDto(
                20, 20, 0, Map.of(), "sha-fit-dominant-20", true, 20, Instant.now()
        );
        when(datasetService.buildDataset()).thenReturn(new AdaptiveDatasetService.DatasetResult(rows, report));

        AdaptiveWeightProposalDto proposal = proposalService.generateProposal(100L);

        // Verification of Fit-dominant sensitivity:
        assertEquals(WeightProposalStatus.READY_FOR_REVIEW, proposal.status());
        assertEquals(20, proposal.datasetSampleCount());

        // Baseline (0.625, 0.375) had agreement = 0.0 because candidate 2 won at baseline weights.
        // Proposed weights (0.75, 0.25) achieve 100% agreement with historical PM selections.
        assertEquals(0.0, proposal.currentMetricHistoricalAgreement(), 1e-6);
        assertEquals(1.0, proposal.metricHistoricalAgreement(), 1e-6);

        // Directional shift strictly toward Fit:
        assertTrue(proposal.proposedFitWeight() > proposal.currentAdaptiveFitWeight(),
                "proposedFitWeight (" + proposal.proposedFitWeight() + ") must be > baseline (" + proposal.currentAdaptiveFitWeight() + ")");
        assertTrue(proposal.proposedLoadWeight() < proposal.currentAdaptiveLoadWeight(),
                "proposedLoadWeight (" + proposal.proposedLoadWeight() + ") must be < baseline (" + proposal.currentAdaptiveLoadWeight() + ")");
        assertTrue(proposal.proposedFitWeight() > proposal.proposedLoadWeight());

        // Exact expected weights under deterministic grid search
        assertEquals(0.75, proposal.proposedFitWeight(), 1e-4);
        assertEquals(0.25, proposal.proposedLoadWeight(), 1e-4);

        // Invariant constraints
        assertTrue(proposal.proposedFitWeight() >= 0.10 && proposal.proposedFitWeight() <= 0.90);
        assertTrue(proposal.proposedLoadWeight() >= 0.10 && proposal.proposedLoadWeight() <= 0.90);
        assertEquals(1.0, proposal.proposedFitWeight() + proposal.proposedLoadWeight(), 1e-4);

        // Determinism: generating again with same dataset yields identical proposal
        AdaptiveWeightProposalDto rerunProposal = proposalService.generateProposal(100L);
        assertEquals(proposal.proposedFitWeight(), rerunProposal.proposedFitWeight());
        assertEquals(proposal.proposedLoadWeight(), rerunProposal.proposedLoadWeight());
        assertEquals(proposal.metricHistoricalAgreement(), rerunProposal.metricHistoricalAgreement());
    }

    /**
     * Cross-dataset sensitivity proof:
     * Demonstrates that distinct synthetic evidence sets produce distinct canonical dataset hashes,
     * distinct proposals, and opposing directional shifts relative to the normalized baseline (0.625, 0.375).
     *
     * NOTE ON SYNTHETIC DATA LIMITATIONS:
     * This proof validates algorithmic responsiveness and mathematical determinism under controlled synthetic inputs.
     * It proves that the offline shadow grid search shifts weights toward whichever feature PM history prioritizes.
     * It DOES NOT prove real-world causal improvement, task velocity enhancement, or that production recommendations
     * should automatically adopt these weights. Production heuristic weights remain strictly immutable unless
     * manually reconfigured by an authorized Global Admin.
     */
    @Test
    @DisplayName("10. Cross-dataset proof: distinct evidence produces distinct hashes and opposing directional shifts")
    void test10_crossDatasetProof_distinctEvidenceProducesOpposingProposals() {
        // --- Dataset A: Fit-dominant evidence ---
        List<AdaptiveDatasetRowDto> fitRows = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            fitRows.add(createThreeCandidateRow(i, 1L, true, 0.95, 3.0, 0.72, 1.0, 0.10, 0.0));
        }
        AdaptiveDatasetQualityReportDto fitReport = new AdaptiveDatasetQualityReportDto(
                20, 20, 0, Map.of(), "sha-fit-evidence-aaaa", true, 20, Instant.now()
        );

        when(datasetService.buildDataset()).thenReturn(new AdaptiveDatasetService.DatasetResult(fitRows, fitReport));
        AdaptiveWeightProposalDto fitProposal = proposalService.generateProposal(100L);

        // --- Dataset B: Workload-dominant evidence ---
        List<AdaptiveDatasetRowDto> loadRows = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            loadRows.add(createSampleRow(i, 1L, true, 0.3, 0.0, 0.9, 10.0));
        }
        AdaptiveDatasetQualityReportDto loadReport = new AdaptiveDatasetQualityReportDto(
                20, 20, 0, Map.of(), "sha-load-evidence-bbbb", true, 20, Instant.now()
        );

        when(datasetService.buildDataset()).thenReturn(new AdaptiveDatasetService.DatasetResult(loadRows, loadReport));
        AdaptiveWeightProposalDto loadProposal = proposalService.generateProposal(100L);

        // 1. Dataset hashes are distinct
        assertNotEquals(fitProposal.datasetHash(), loadProposal.datasetHash());

        // 2. Both proposals are valid and READY_FOR_REVIEW
        assertEquals(WeightProposalStatus.READY_FOR_REVIEW, fitProposal.status());
        assertEquals(WeightProposalStatus.READY_FOR_REVIEW, loadProposal.status());

        // 3. Proposed weights are distinct
        assertNotEquals(fitProposal.proposedFitWeight(), loadProposal.proposedFitWeight());
        assertNotEquals(fitProposal.proposedLoadWeight(), loadProposal.proposedLoadWeight());

        // 4. Opposing directional shifts relative to baseline (0.625, 0.375)
        double baselineFit = fitProposal.currentAdaptiveFitWeight(); // 0.625
        double baselineLoad = fitProposal.currentAdaptiveLoadWeight(); // 0.375

        // Fit-dominant moves UP on Fit, DOWN on Load:
        assertTrue(fitProposal.proposedFitWeight() > baselineFit,
                "Fit-dominant proposedFitWeight must be > baselineFit");
        assertTrue(fitProposal.proposedLoadWeight() < baselineLoad,
                "Fit-dominant proposedLoadWeight must be < baselineLoad");
        assertEquals(0.75, fitProposal.proposedFitWeight(), 1e-4);
        assertEquals(0.25, fitProposal.proposedLoadWeight(), 1e-4);

        // Workload-dominant moves DOWN on Fit, UP on Load:
        assertTrue(loadProposal.proposedFitWeight() < baselineFit,
                "Workload-dominant proposedFitWeight must be < baselineFit");
        assertTrue(loadProposal.proposedLoadWeight() > baselineLoad,
                "Workload-dominant proposedLoadWeight must be > baselineLoad");
        assertEquals(0.45, loadProposal.proposedFitWeight(), 1e-4);
        assertEquals(0.55, loadProposal.proposedLoadWeight(), 1e-4);

        // 5. Opposite dominance relationships
        assertTrue(fitProposal.proposedFitWeight() > fitProposal.proposedLoadWeight(),
                "Fit proposal must have proposedFitWeight > proposedLoadWeight");
        assertTrue(loadProposal.proposedLoadWeight() > loadProposal.proposedFitWeight(),
                "Load proposal must have proposedLoadWeight > proposedFitWeight");

        // 6. Both satisfy unit sum and bounds
        assertEquals(1.0, fitProposal.proposedFitWeight() + fitProposal.proposedLoadWeight(), 1e-4);
        assertEquals(1.0, loadProposal.proposedFitWeight() + loadProposal.proposedLoadWeight(), 1e-4);
        assertTrue(fitProposal.proposedFitWeight() >= 0.10 && fitProposal.proposedFitWeight() <= 0.90);
        assertTrue(loadProposal.proposedFitWeight() >= 0.10 && loadProposal.proposedFitWeight() <= 0.90);
    }

    @Test
    @DisplayName("11. Dynamic BALANCED baseline normalization: reflects runtime configuration without hardcoded overrides")
    void test11_dynamicBalancedBaselineNormalization() {
        List<AdaptiveDatasetRowDto> rows = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            rows.add(createSampleRow(i, 1L, true, 0.9, 0.0, 0.2, 5.0));
        }
        AdaptiveDatasetQualityReportDto report = new AdaptiveDatasetQualityReportDto(
                20, 20, 0, Map.of(), "sha-20", true, 20, Instant.now()
        );
        when(datasetService.buildDataset()).thenReturn(new AdaptiveDatasetService.DatasetResult(rows, report));

        // 1. Default BALANCED 0.50/0.30/0.20 normalizes to 0.625/0.375
        HeuristicStrategy defaultStrategy = mock(HeuristicStrategy.class);
        HeuristicWeights defaultWeights = new HeuristicWeights(0.50, 0.30, 0.20);
        when(defaultStrategy.weights()).thenReturn(defaultWeights);
        when(strategyFactory.resolve("BALANCED")).thenReturn(defaultStrategy);

        AdaptiveWeightProposalDto prop1 = proposalService.generateProposal(100L);
        assertEquals(0.625, prop1.currentAdaptiveFitWeight(), 1e-4);
        assertEquals(0.375, prop1.currentAdaptiveLoadWeight(), 1e-4);
        assertEquals(1.0, prop1.currentAdaptiveFitWeight() + prop1.currentAdaptiveLoadWeight(), 1e-6);

        // 2 & 3. Custom BALANCED 0.23/0.65/0.12 dynamically shifts baseline to ~0.26136 / ~0.73864
        HeuristicStrategy customStrategy = mock(HeuristicStrategy.class);
        HeuristicWeights customWeights = new HeuristicWeights(0.23, 0.65, 0.12);
        when(customStrategy.weights()).thenReturn(customWeights);
        when(strategyFactory.resolve("BALANCED")).thenReturn(customStrategy);

        AdaptiveWeightProposalDto prop2 = proposalService.generateProposal(100L);
        assertEquals(0.2613636364, prop2.currentAdaptiveFitWeight(), 1e-6);
        assertEquals(0.7386363636, prop2.currentAdaptiveLoadWeight(), 1e-6);
        assertEquals(1.0, prop2.currentAdaptiveFitWeight() + prop2.currentAdaptiveLoadWeight(), 1e-6);

        // 4. Baseline reflects runtime configuration changes without being overridden by a constant
        assertNotEquals(prop1.currentAdaptiveFitWeight(), prop2.currentAdaptiveFitWeight());
        assertNotEquals(prop1.currentAdaptiveLoadWeight(), prop2.currentAdaptiveLoadWeight());

        // 5. Runtime strategy weights remain unchanged
        assertEquals(0.50, defaultWeights.fitWeight(), 1e-6);
        assertEquals(0.30, defaultWeights.loadWeight(), 1e-6);
        assertEquals(0.20, defaultWeights.performanceWeight(), 1e-6);
        assertEquals(0.23, customWeights.fitWeight(), 1e-6);
        assertEquals(0.65, customWeights.loadWeight(), 1e-6);
        assertEquals(0.12, customWeights.performanceWeight(), 1e-6);

        // 6. Invalid Fit+Load denominator blocks proposal generation
        HeuristicStrategy invalidStrategy = mock(HeuristicStrategy.class);
        when(invalidStrategy.weights()).thenReturn(new HeuristicWeights(0.0, 0.0, 1.0));
        when(strategyFactory.resolve("BALANCED")).thenReturn(invalidStrategy);

        BusinessException ex = assertThrows(BusinessException.class, () -> proposalService.generateProposal(100L));
        assertEquals(HttpStatus.BAD_REQUEST.value(), ex.getStatus());
        assertTrue(ex.getMessage().contains("fitWeight + loadWeight must be positive and finite"));
    }
}
