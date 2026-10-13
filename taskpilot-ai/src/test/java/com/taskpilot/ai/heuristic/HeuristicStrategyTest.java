package com.taskpilot.ai.heuristic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0 Characterization Tests: HeuristicStrategy, Negative Scores, and 1e9 Scale Validation
 *
 * Covers:
 * - Item 5: Negative score rounding
 * - Item 6: Strategy differentiation using explicit fixtures (MIXED_FIXTURE, FIT_DOMINANT_FIXTURE, LOAD_DOMINANT_FIXTURE synthetic design-labeled fixtures, not runtime configuration)
 * - Item 9: Proposed fixed-point 1e9 key behavior and scale validation against oracles
 *
 * Rules:
 * - Pure unit tests (no Spring context, no live DB, no external LLM).
 * - HeuristicStrategy implementations are instantiated directly with explicit test weights.
 * - Do not mock HeuristicStrategy.
 * - Fail-closed: Validate the 1e9 scale against all accepted oracles.
 */
class HeuristicStrategyTest {

    // =========================================================================
    // Synthetic Fixtures by Mathematical Role
    // (Effective runtime weights are unknown because they are loaded only from system_settings["heuristic.weights"])
    // Note: FIT_DOMINANT_FIXTURE raw weights (0.474 + 0.053 + 0.474 = 1.001) represent synthetic design-labeled
    // fixture weights, not runtime configuration. Production HeuristicConfigProvider would normalize positive
    // supplied weights to sum to 1.0.
    // Tests use explicit mathematical fixtures without claiming runtime database equivalence.
    // =========================================================================
    private static final HeuristicWeights MIXED_FIXTURE = new HeuristicWeights(0.230, 0.648, 0.122);
    private static final HeuristicWeights FIT_DOMINANT_FIXTURE = new HeuristicWeights(0.474, 0.053, 0.474);
    private static final HeuristicWeights LOAD_DOMINANT_FIXTURE = new HeuristicWeights(0.188, 0.731, 0.081);

    private static final HeuristicNormalizationConfig BENEFIT_NORM = new HeuristicNormalizationConfig(
            HeuristicNormalization.BENCHMARK_BENEFIT,
            HeuristicNormalization.BENCHMARK_BENEFIT,
            HeuristicNormalization.BENCHMARK_BENEFIT
    );

    private BalancedHeuristicStrategy createBalancedStrategy() {
        return new BalancedHeuristicStrategy(new HeuristicConfig(MIXED_FIXTURE, BENEFIT_NORM));
    }

    private UrgentHeuristicStrategy createUrgentStrategy() {
        return new UrgentHeuristicStrategy(new HeuristicConfig(FIT_DOMINANT_FIXTURE, BENEFIT_NORM));
    }

    private TrainingHeuristicStrategy createTrainingStrategy() {
        return new TrainingHeuristicStrategy(new HeuristicConfig(LOAD_DOMINANT_FIXTURE, BENEFIT_NORM));
    }

    // =========================================================================
    // 5. Negative score rounding behavior
    // =========================================================================

    @Test
    @DisplayName("current-behavior characterization: subtractive workload produces negative score, and round2 truncates small negative differences")
    void currentBehavior_negativeScore_roundingBehavior() {
        BalancedHeuristicStrategy balanced = createBalancedStrategy();

        // Candidate with Fit = 0.0, Load = 1.0, Perf = 0.0
        // Score = (0.230 * 0.0) - (0.648 * 1.0) + (0.122 * 0.0) = -0.648
        NormalizedScores normalized = new NormalizedScores(0.0, 1.0, 0.0);
        double rawScore = balanced.score(normalized);

        assertEquals(-0.648, rawScore, 1e-9);

        // Display rounding: Math.round(-0.648 * 100.0) / 100.0 = -0.65
        double roundedScore = Math.round(rawScore * 100.0) / 100.0;
        assertEquals(-0.65, roundedScore, 1e-9);

        // Borderline negative scores near zero: -0.004 vs -0.006
        double scoreA = -0.004;
        double scoreB = -0.006;
        double roundedA = Math.round(scoreA * 100.0) / 100.0; // Math.round(-0.4) -> 0.0
        double roundedB = Math.round(scoreB * 100.0) / 100.0; // Math.round(-0.6) -> -0.01

        assertEquals(0.0, roundedA, 1e-9);
        assertEquals(-0.01, roundedB, 1e-9);
    }

    // =========================================================================
    // 6. Strategy differentiation across mathematical fixture roles
    // =========================================================================

    @Test
    @DisplayName("current-behavior characterization: MIXED_FIXTURE, FIT_DOMINANT_FIXTURE, and LOAD_DOMINANT_FIXTURE differentiate priority trade-offs (synthetic design-labeled fixtures, not runtime configuration)")
    void currentBehavior_strategyDifferentiation_mathematicalFixtureRoles() {
        BalancedHeuristicStrategy balanced = createBalancedStrategy();
        UrgentHeuristicStrategy urgent = createUrgentStrategy();
        TrainingHeuristicStrategy training = createTrainingStrategy();

        // Two contrasting candidates:
        // Candidate 1 (Overloaded Veteran): Fit = 0.95, Load = 0.90, Perf = 0.90
        // Candidate 2 (Available Junior):   Fit = 0.60, Load = 0.10, Perf = 0.50
        NormalizedScores veteran = new NormalizedScores(0.95, 0.90, 0.90);
        NormalizedScores junior = new NormalizedScores(0.60, 0.10, 0.50);

        // MIXED_FIXTURE (synthetic design-labeled BALANCED fixture, not runtime configuration): (fit=0.230, load=0.648, perf=0.122)
        // Veteran:  0.230*0.95 - 0.648*0.90 + 0.122*0.90 = 0.2185 - 0.5832 + 0.1098 = -0.2549
        // Junior:   0.230*0.60 - 0.648*0.10 + 0.122*0.50 = 0.1380 - 0.0648 + 0.0610 = +0.1342
        double balancedVeteran = balanced.score(veteran);
        double balancedJunior = balanced.score(junior);
        assertEquals(-0.2549, balancedVeteran, 1e-4);
        assertEquals(0.1342, balancedJunior, 1e-4);
        assertTrue(balancedJunior > balancedVeteran, "Under MIXED_FIXTURE (synthetic design-labeled BALANCED fixture, not runtime configuration), available junior ranks ahead of overloaded veteran");

        // FIT_DOMINANT_FIXTURE (synthetic design-labeled URGENT fixture, not runtime configuration): (fit=0.474, load=0.053, perf=0.474)
        // Veteran:  0.474*0.95 - 0.053*0.90 + 0.474*0.90 = 0.4503 - 0.0477 + 0.4266 = +0.8292
        // Junior:   0.474*0.60 - 0.053*0.10 + 0.474*0.50 = 0.2844 - 0.0053 + 0.2370 = +0.5161
        double urgentVeteran = urgent.score(veteran);
        double urgentJunior = urgent.score(junior);
        assertEquals(0.8292, urgentVeteran, 1e-4);
        assertEquals(0.5161, urgentJunior, 1e-4);
        assertTrue(urgentVeteran > urgentJunior, "Under FIT_DOMINANT_FIXTURE (synthetic design-labeled URGENT fixture, not runtime configuration), veteran ranks ahead because load penalty (0.053) is negligible");

        // LOAD_DOMINANT_FIXTURE (synthetic design-labeled TRAINING fixture, not runtime configuration): (fit=0.188, load=0.731, perf=0.081)
        // Veteran:  0.188*0.95 - 0.731*0.90 + 0.081*0.90 = 0.1786 - 0.6579 + 0.0729 = -0.4064
        // Junior:   0.188*0.60 - 0.731*0.10 + 0.081*0.50 = 0.1128 - 0.0731 + 0.0405 = +0.0802
        double trainingVeteran = training.score(veteran);
        double trainingJunior = training.score(junior);
        assertEquals(-0.4064, trainingVeteran, 1e-4);
        assertEquals(0.0802, trainingJunior, 1e-4);
        assertTrue(trainingJunior > trainingVeteran, "Under LOAD_DOMINANT_FIXTURE (synthetic design-labeled TRAINING fixture, not runtime configuration), high workload is severely penalized (0.731)");
    }

    // =========================================================================
    // 9. Proposed Fixed-Point 1e9 Key Validation (Mandatory Gate 1 Validation)
    // =========================================================================

    /**
     * Helper computing the Phase 1 proposed fixed-point ranking key:
     * rankingKey = Math.round(fullPrecisionScore * 1_000_000_000L)
     */
    private long compute1e9RankingKey(double fullPrecisionScore) {
        return Math.round(fullPrecisionScore * 1_000_000_000L);
    }

    @Test
    @DisplayName("future-contract validation: 1e9 scale validates Workload Monotonicity Oracle N-001 across all three synthetic design-labeled fixtures, not runtime configuration")
    void validation_1e9Scale_workloadMonotonicity_oracleN001() {
        // Inputs: Equal Fit (0.80) and Performance (0.50)
        // Candidate A: workload = 10 -> normLoad = 0.10
        // Candidate B: workload = 50 -> normLoad = 0.50
        // Candidate C: workload = 90 -> normLoad = 0.90
        NormalizedScores candA = new NormalizedScores(0.80, 0.10, 0.50);
        NormalizedScores candB = new NormalizedScores(0.80, 0.50, 0.50);
        NormalizedScores candC = new NormalizedScores(0.80, 0.90, 0.50);

        List<HeuristicStrategy> strategies = List.of(
                createBalancedStrategy(),
                createUrgentStrategy(),
                createTrainingStrategy()
        );

        for (HeuristicStrategy strategy : strategies) {
            double scoreA = strategy.score(candA);
            double scoreB = strategy.score(candB);
            double scoreC = strategy.score(candC);

            long keyA = compute1e9RankingKey(scoreA);
            long keyB = compute1e9RankingKey(scoreB);
            long keyC = compute1e9RankingKey(scoreC);

            // N-001 Oracle:
            // score(A) >= score(B) >= score(C)
            // rankingKey(A) >= rankingKey(B) >= rankingKey(C)
            assertTrue(scoreA >= scoreB && scoreB >= scoreC,
                    "Strategy " + strategy.mode() + " violates full-precision score monotonicity");
            assertTrue(keyA >= keyB && keyB >= keyC,
                    "Strategy " + strategy.mode() + " violates 1e9 rankingKey monotonicity");
            assertTrue(keyA > keyB && keyB > keyC,
                    "Strategy " + strategy.mode() + " 1e9 scale should strictly separate distinct workloads");
        }
    }

    @Test
    @DisplayName("future-contract validation: 1e9 scale validates Fit Monotonicity Oracle N-005")
    void validation_1e9Scale_fitMonotonicity_oracleN005() {
        BalancedHeuristicStrategy strategy = createBalancedStrategy();

        // Fit(A) = 0.90, Fit(B) = 0.85, Fit(C) = 0.80; Load = 0.20, Perf = 0.50
        double scoreA = strategy.score(new NormalizedScores(0.90, 0.20, 0.50));
        double scoreB = strategy.score(new NormalizedScores(0.85, 0.20, 0.50));
        double scoreC = strategy.score(new NormalizedScores(0.80, 0.20, 0.50));

        long keyA = compute1e9RankingKey(scoreA);
        long keyB = compute1e9RankingKey(scoreB);
        long keyC = compute1e9RankingKey(scoreC);

        assertTrue(scoreA > scoreB && scoreB > scoreC);
        assertTrue(keyA > keyB && keyB > keyC, "1e9 scale strictly preserves fit monotonicity");
    }

    @Test
    @DisplayName("future-contract validation: 1e9 scale validates Performance Monotonicity Oracle N-006")
    void validation_1e9Scale_performanceMonotonicity_oracleN006() {
        BalancedHeuristicStrategy strategy = createBalancedStrategy();

        // Perf(A) = 0.90, Perf(B) = 0.50, Perf(C) = 0.20; Fit = 0.80, Load = 0.20
        double scoreA = strategy.score(new NormalizedScores(0.80, 0.20, 0.90));
        double scoreB = strategy.score(new NormalizedScores(0.80, 0.20, 0.50));
        double scoreC = strategy.score(new NormalizedScores(0.80, 0.20, 0.20));

        long keyA = compute1e9RankingKey(scoreA);
        long keyB = compute1e9RankingKey(scoreB);
        long keyC = compute1e9RankingKey(scoreC);

        assertTrue(scoreA > scoreB && scoreB > scoreC);
        assertTrue(keyA > keyB && keyB > keyC, "1e9 scale strictly preserves performance monotonicity");
    }

    @Test
    @DisplayName("future-contract validation: 1e9 scale separates borderline scores (Oracle N-008 & N-009) where 2-decimal rounding collapses")
    void validation_1e9Scale_separatesBorderlineDifferences() {
        // Difference Delta = 0.008 (collapsed by 2-decimal rounding to 0.84)
        double scoreA = 0.844;
        double scoreB = 0.836;

        long keyA = compute1e9RankingKey(scoreA); // 844_000_000L
        long keyB = compute1e9RankingKey(scoreB); // 836_000_000L

        assertEquals(844_000_000L, keyA);
        assertEquals(836_000_000L, keyB);
        assertTrue(keyA > keyB, "1e9 scale separates 0.844 from 0.836 by 8,000,000 discrete integer units");

        // Sub-decimal difference Delta = 1e-8 (e.g. 0.84400001 vs 0.84400000)
        double fineScoreA = 0.84400001;
        double fineScoreB = 0.84400000;

        long fineKeyA = compute1e9RankingKey(fineScoreA); // 844_000_010L
        long fineKeyB = compute1e9RankingKey(fineScoreB); // 844_000_000L

        assertEquals(844_000_010L, fineKeyA);
        assertEquals(844_000_000L, fineKeyB);
        assertTrue(fineKeyA > fineKeyB, "1e9 scale cleanly resolves differences down to 1e-8 without precision loss");
    }

    @Test
    @DisplayName("future-contract validation: 1e9 scale cleanly handles negative score range without sign distortion or overflow")
    void validation_1e9Scale_handlesNegativeScoresSafely() {
        // Extreme negative bound: Fit = 0.0, Load = 1.0, Perf = 0.0 with weight = 1.0 -> -1.0
        double minScore = -1.0;
        double maxScore = 1.0;

        long minKey = compute1e9RankingKey(minScore);
        long maxKey = compute1e9RankingKey(maxScore);

        assertEquals(-1_000_000_000L, minKey);
        assertEquals(1_000_000_000L, maxKey);

        // Verifies no integer overflow in 64-bit signed long
        assertTrue(minKey > Long.MIN_VALUE);
        assertTrue(maxKey < Long.MAX_VALUE);

        // Relative comparison preserved for negative numbers
        double negScoreA = -0.648001;
        double negScoreB = -0.648002;
        long negKeyA = compute1e9RankingKey(negScoreA);
        long negKeyB = compute1e9RankingKey(negScoreB);

        assertTrue(negKeyA > negKeyB, "Negative ranking keys preserve strict greater-than order");
    }

    @Test
    @DisplayName("future-contract validation: 1e9 scale validates zero, midpoint, and near-boundary quantization thresholds (0.0, 0.500000001 vs 0.500000000, and negative counterparts)")
    void validation_1e9Scale_zeroAndQuantizationBoundaries() {
        // Zero boundary: 0.0 -> 0L
        assertEquals(0L, compute1e9RankingKey(0.0));
        assertEquals(0L, compute1e9RankingKey(-0.0));

        // Quantization boundary at 1e-9 step: 0.500000001 vs 0.500000000
        double posA = 0.500000001;
        double posB = 0.500000000;
        long keyPosA = compute1e9RankingKey(posA);
        long keyPosB = compute1e9RankingKey(posB);

        assertEquals(500_000_001L, keyPosA);
        assertEquals(500_000_000L, keyPosB);
        assertTrue(keyPosA > keyPosB, "1e9 scale separates 0.500000001 from 0.500000000 by exactly 1 unit");

        // Corresponding negative near-boundary: -0.500000000 vs -0.500000001
        double negA = -0.500000000;
        double negB = -0.500000001;
        long keyNegA = compute1e9RankingKey(negA);
        long keyNegB = compute1e9RankingKey(negB);

        assertEquals(-500_000_000L, keyNegA);
        assertEquals(-500_000_001L, keyNegB);
        assertTrue(keyNegA > keyNegB, "Negative near-boundary preserves strict greater-than (-500_000_000L > -500_000_001L)");
    }
}
