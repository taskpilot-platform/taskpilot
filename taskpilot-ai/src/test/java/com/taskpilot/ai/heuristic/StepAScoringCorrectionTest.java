package com.taskpilot.ai.heuristic;

import com.taskpilot.ai.dto.InternalCandidateRanking;
import com.taskpilot.ai.dto.MetricDataStatus;
import com.taskpilot.ai.dto.RecommendationDifferentiationStatus;
import com.taskpilot.ai.dto.RecommendationView;
import com.taskpilot.ai.dto.RecommendedCandidateView;
import com.taskpilot.ai.service.AutoAssignmentService;
import com.taskpilot.contracts.assignment.port.out.SystemSettingPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 Step A Tests: Scoring Correction, Fixed-Point Comparator, and Differentiation Semantics
 *
 * Verifies Gate 4, Gate 5, & Decisions H-002, H-003, H-004, H-012, H-013, H-014:
 * 1. Equal-range neutral normalization (H-004): returns 0.0 when max <= min.
 * 2. Unsafe inversion rejection (H-003, N-002): fails closed if load == BENCHMARK_COST.
 * 3. Workload monotonicity (H-002, N-001): workload(10) >= workload(50) >= workload(90).
 * 4. Fixed-point 1e9 quantization & border resolution (H-012, N-008, N-009).
 * 5. Deterministic multi-tier tie-breaking (H-012, N-010): rankingKey DESC -> rawFit DESC -> userId ASC.
 * 6. Input order independence (N-010): order invariant under input reversal.
 * 7. Full-tie differentiation semantics (H-013, N-011): INSUFFICIENT_TO_DIFFERENTIATE vs DIFFERENTIATED vs UNKNOWN.
 * 8. Model versioning (H-014): scoringModelVersion = "relative-neutral-fixed-point-v2".
 *
 * Rules:
 * - Pure unit tests (no Spring context, no live DB, no external LLM).
 * - Scoring and strategy classes directly instantiated.
 */
class StepAScoringCorrectionTest {

    private static final HeuristicWeights MIXED_WEIGHTS = new HeuristicWeights(0.230, 0.648, 0.122);
    private static final HeuristicWeights URGENT_WEIGHTS = new HeuristicWeights(0.474, 0.053, 0.474);
    private static final HeuristicWeights TRAINING_WEIGHTS = new HeuristicWeights(0.188, 0.731, 0.081);

    private static final HeuristicNormalizationConfig VALID_NORM = new HeuristicNormalizationConfig(
            HeuristicNormalization.BENCHMARK_BENEFIT,
            HeuristicNormalization.BENCHMARK_BENEFIT,
            HeuristicNormalization.BENCHMARK_BENEFIT
    );

    // =========================================================================
    // 1. Decision H-004: Equal-Range Neutral Normalization
    // =========================================================================

    @Test
    @DisplayName("H-004: normalizeNeutral returns 0.0 when max <= min across benefit and cost modes")
    void equalRange_normalizeNeutral_returnsZeroContribution() {
        ScoreRange equalRange = new ScoreRange(50.0, 50.0);
        assertTrue(equalRange.isEqualRange());

        assertEquals(0.0, equalRange.normalizeNeutral(50.0, HeuristicNormalization.BENCHMARK_BENEFIT), 1e-9);
        assertEquals(0.0, equalRange.normalizeNeutral(50.0, HeuristicNormalization.BENCHMARK_COST), 1e-9);

        ScoreRange zeroWorkloadRange = new ScoreRange(0.0, 0.0);
        assertTrue(zeroWorkloadRange.isEqualRange());
        assertEquals(0.0, zeroWorkloadRange.normalizeNeutral(0.0, HeuristicNormalization.BENCHMARK_BENEFIT), 1e-9);

        ScoreRange invertedRange = new ScoreRange(80.0, 20.0);
        assertTrue(invertedRange.isEqualRange());
        assertEquals(0.0, invertedRange.normalizeNeutral(50.0, HeuristicNormalization.BENCHMARK_BENEFIT), 1e-9);
    }

    @Test
    @DisplayName("H-004: normalizeNeutral preserves standard min-max mapping when max > min")
    void normalRange_normalizeNeutral_preservesStandardMapping() {
        ScoreRange normalRange = new ScoreRange(10.0, 90.0);
        assertFalse(normalRange.isEqualRange());

        assertEquals(0.0, normalRange.normalizeNeutral(10.0, HeuristicNormalization.BENCHMARK_BENEFIT), 1e-9);
        assertEquals(0.5, normalRange.normalizeNeutral(50.0, HeuristicNormalization.BENCHMARK_BENEFIT), 1e-9);
        assertEquals(1.0, normalRange.normalizeNeutral(90.0, HeuristicNormalization.BENCHMARK_BENEFIT), 1e-9);
    }

    // =========================================================================
    // 2. Decision H-003 & Oracle N-002: Unsafe Double Inversion Rejection
    // =========================================================================

    @Test
    @DisplayName("H-003: HeuristicStrategy fails closed when load normalization is BENCHMARK_COST across all active strategies")
    void heuristicStrategy_rejectsBenchmarkCostLoad() {
        HeuristicNormalizationConfig invalidNorm = new HeuristicNormalizationConfig(
                HeuristicNormalization.BENCHMARK_BENEFIT,
                HeuristicNormalization.BENCHMARK_COST,
                HeuristicNormalization.BENCHMARK_BENEFIT
        );
        List<HeuristicStrategy> strategies = List.of(
                new BalancedHeuristicStrategy(new HeuristicConfig(MIXED_WEIGHTS, invalidNorm)),
                new UrgentHeuristicStrategy(new HeuristicConfig(URGENT_WEIGHTS, invalidNorm)),
                new TrainingHeuristicStrategy(new HeuristicConfig(TRAINING_WEIGHTS, invalidNorm))
        );

        RawScores raw = new RawScores(0.80, 20.0, 0.50);
        ScoreRanges ranges = new ScoreRanges(
                new ScoreRange(0.50, 0.90),
                new ScoreRange(10.0, 50.0),
                new ScoreRange(0.30, 0.70)
        );

        for (HeuristicStrategy strategy : strategies) {
            assertThrows(IllegalStateException.class, () -> strategy.normalizeNeutral(raw, ranges),
                    "Runtime scoring must fail closed if load is configured as BENCHMARK_COST (H-003) for " + strategy.getClass().getSimpleName());
            assertThrows(IllegalStateException.class, () -> strategy.normalize(raw, ranges),
                    "Legacy normalize must also fail closed on BENCHMARK_COST (H-003) for " + strategy.getClass().getSimpleName());
        }
    }

    @Test
    @DisplayName("H-003: HeuristicConfigProvider fails closed at config read boundary when load is BENCHMARK_COST")
    void heuristicConfigProvider_rejectsBenchmarkCostLoadAtReadBoundary() throws Exception {
        SystemSettingPort mockSettingPort = new SystemSettingPort() {
            @Override
            public Optional<Map<String, Object>> findJsonObjectByKey(String key) {
                if ("heuristic.weights".equals(key)) {
                    return Optional.of(Map.of("BALANCED", Map.of("fit", 0.4, "load", 0.35, "perf", 0.25)));
                }
                if ("heuristic.normalization".equals(key)) {
                    return Optional.of(Map.of("BALANCED", Map.of("fit", "BENCHMARK_BENEFIT", "load", "BENCHMARK_COST", "perf", "BENCHMARK_BENEFIT")));
                }
                return Optional.empty();
            }
        };

        HeuristicConfigProvider provider = new HeuristicConfigProvider(mockSettingPort);

        // Invoking getConfig triggers refreshCacheIfNeeded which parses normalization
        BusinessException ex = assertThrows(BusinessException.class, () -> provider.getConfig("BALANCED"));
        assertTrue(ex.getMessage().contains("BENCHMARK_COST is incompatible"),
                "Config read boundary must reject BENCHMARK_COST with BusinessException (H-003)");
    }

    // =========================================================================
    // 3. Decision H-002 & Oracle N-001: Workload Monotonicity
    // =========================================================================

    @Test
    @DisplayName("H-002 / N-001: Workload monotonicity is strictly preserved across all modes under normalizeNeutral")
    void workloadMonotonicity_preservedAcrossAllModes() {
        List<HeuristicStrategy> strategies = List.of(
                new BalancedHeuristicStrategy(new HeuristicConfig(MIXED_WEIGHTS, VALID_NORM)),
                new UrgentHeuristicStrategy(new HeuristicConfig(URGENT_WEIGHTS, VALID_NORM)),
                new TrainingHeuristicStrategy(new HeuristicConfig(TRAINING_WEIGHTS, VALID_NORM))
        );

        ScoreRanges ranges = new ScoreRanges(
                new ScoreRange(0.80, 0.80), // equal range fit
                new ScoreRange(10.0, 90.0), // distinct load [10, 90]
                new ScoreRange(0.50, 0.50)  // equal range perf
        );

        RawScores candA = new RawScores(0.80, 10.0, 0.50);
        RawScores candB = new RawScores(0.80, 50.0, 0.50);
        RawScores candC = new RawScores(0.80, 90.0, 0.50);

        for (HeuristicStrategy strategy : strategies) {
            NormalizedScores normA = strategy.normalizeNeutral(candA, ranges);
            NormalizedScores normB = strategy.normalizeNeutral(candB, ranges);
            NormalizedScores normC = strategy.normalizeNeutral(candC, ranges);

            double scoreA = strategy.score(normA);
            double scoreB = strategy.score(normB);
            double scoreC = strategy.score(normC);

            long keyA = Math.round(scoreA * 1_000_000_000L);
            long keyB = Math.round(scoreB * 1_000_000_000L);
            long keyC = Math.round(scoreC * 1_000_000_000L);

            assertTrue(scoreA >= scoreB && scoreB >= scoreC,
                    "Monotonicity score(A) >= score(B) >= score(C) violated in " + strategy.mode());
            assertTrue(keyA >= keyB && keyB >= keyC,
                    "Monotonicity key(A) >= key(B) >= key(C) violated in " + strategy.mode());
            assertTrue(keyA > keyB && keyB > keyC,
                    "Distinct workloads must produce strictly decreasing ranking keys in " + strategy.mode());
        }
    }

    // =========================================================================
    // 4. Decision H-012, Oracles N-008, N-009, N-010: Multi-Tier Fixed-Point Comparator
    // =========================================================================

    @Test
    @DisplayName("H-012 / N-008: Tier 1 resolves borderline candidate scores collapsed by two-decimal display rounding")
    void tier1_resolvesBorderlineRoundingCollapse() {
        // Borderline candidates: 0.844 vs 0.836 both round to 0.84
        long keyA = Math.round(0.844 * 1_000_000_000L); // 844_000_000L
        long keyB = Math.round(0.836 * 1_000_000_000L); // 836_000_000L

        InternalCandidateRanking candA = InternalCandidateRanking.builder()
                .userId(101L).fullName("Alice").fullPrecisionScore(0.844).rankingKey(keyA).rankingRawFit(0.85).build();
        InternalCandidateRanking candB = InternalCandidateRanking.builder()
                .userId(102L).fullName("Bob").fullPrecisionScore(0.836).rankingKey(keyB).rankingRawFit(0.85).build();

        int comp = InternalCandidateRanking.STEP_A_COMPARATOR.compare(candA, candB);
        assertTrue(comp < 0, "Candidate A (0.844) must strictly precede Candidate B (0.836)");

        List<InternalCandidateRanking> list = new ArrayList<>(List.of(candB, candA));
        list.sort(InternalCandidateRanking.STEP_A_COMPARATOR);
        assertEquals(101L, list.get(0).userId(), "Alice must rank first");
    }

    @Test
    @DisplayName("H-012 / N-010: Tier 2 breaks rankingKey tie using raw Skill Fit descending")
    void tier2_breaksRankingKeyTieUsingRawSkillFit() {
        long tiedKey = 850_000_000L;
        InternalCandidateRanking candHighFit = InternalCandidateRanking.builder()
                .userId(201L).fullName("High Fit").rankingKey(tiedKey).rankingRawFit(0.90).build();
        InternalCandidateRanking candLowFit = InternalCandidateRanking.builder()
                .userId(202L).fullName("Low Fit").rankingKey(tiedKey).rankingRawFit(0.70).build();

        int comp = InternalCandidateRanking.STEP_A_COMPARATOR.compare(candHighFit, candLowFit);
        assertTrue(comp < 0, "High Fit candidate must strictly precede Low Fit candidate when keys tie");

        List<InternalCandidateRanking> list = new ArrayList<>(List.of(candLowFit, candHighFit));
        list.sort(InternalCandidateRanking.STEP_A_COMPARATOR);
        assertEquals(201L, list.get(0).userId(), "High Fit candidate must rank first");
    }

    @Test
    @DisplayName("H-012 / N-010: Tier 3 breaks full key and fit tie deterministically using userId ascending")
    void tier3_breaksFullTieUsingUserIdAscending() {
        long tiedKey = 850_000_000L;
        double tiedFit = 0.85;

        InternalCandidateRanking user9 = InternalCandidateRanking.builder()
                .userId(9L).fullName("User 9").rankingKey(tiedKey).rankingRawFit(tiedFit).build();
        InternalCandidateRanking user3 = InternalCandidateRanking.builder()
                .userId(3L).fullName("User 3").rankingKey(tiedKey).rankingRawFit(tiedFit).build();

        int comp = InternalCandidateRanking.STEP_A_COMPARATOR.compare(user3, user9);
        assertTrue(comp < 0, "Smaller userId 3 must strictly precede userId 9 when keys and fit tie");

        List<InternalCandidateRanking> list = new ArrayList<>(List.of(user9, user3));
        list.sort(InternalCandidateRanking.STEP_A_COMPARATOR);
        assertEquals(3L, list.get(0).userId(), "User 3 must rank first deterministically");
    }

    @Test
    @DisplayName("H-012 / N-010: Sorting produces identical order regardless of input collection order (order independence)")
    void inputOrderIndependence_underReversal() {
        InternalCandidateRanking c1 = InternalCandidateRanking.builder()
                .userId(1L).fullName("C1").rankingKey(900_000_000L).rankingRawFit(0.90).build();
        InternalCandidateRanking c2 = InternalCandidateRanking.builder()
                .userId(2L).fullName("C2").rankingKey(850_000_000L).rankingRawFit(0.85).build();
        InternalCandidateRanking c3 = InternalCandidateRanking.builder()
                .userId(3L).fullName("C3").rankingKey(850_000_000L).rankingRawFit(0.80).build();
        InternalCandidateRanking c4 = InternalCandidateRanking.builder()
                .userId(4L).fullName("C4").rankingKey(850_000_000L).rankingRawFit(0.80).build();

        List<InternalCandidateRanking> forward = new ArrayList<>(List.of(c1, c2, c3, c4));
        forward.sort(InternalCandidateRanking.STEP_A_COMPARATOR);

        List<InternalCandidateRanking> reverse = new ArrayList<>(List.of(c4, c3, c2, c1));
        reverse.sort(InternalCandidateRanking.STEP_A_COMPARATOR);

        List<Long> forwardIds = forward.stream().map(InternalCandidateRanking::userId).toList();
        List<Long> reverseIds = reverse.stream().map(InternalCandidateRanking::userId).toList();

        assertEquals(forwardIds, reverseIds, "Sorted result must be completely order-independent");
        assertEquals(List.of(1L, 2L, 3L, 4L), forwardIds);
    }

    // =========================================================================
    // 5. Decision H-013 & Oracle N-011: Differentiation Status Semantics
    // =========================================================================

    @Test
    @DisplayName("H-013 / N-011: Single candidate returns differentiationStatus UNKNOWN")
    void singleCandidate_returnsUnknownDifferentiationStatus() {
        InternalCandidateRanking cand = InternalCandidateRanking.builder()
                .userId(1L).rankingKey(800_000_000L).rankingRawFit(0.80).fitStatus(MetricDataStatus.MEASURED).build();
        assertEquals(RecommendationDifferentiationStatus.UNKNOWN,
                AutoAssignmentService.evaluateDifferentiationStatus(List.of(cand)));
        assertEquals(RecommendationDifferentiationStatus.UNKNOWN,
                AutoAssignmentService.evaluateDifferentiationStatus(Collections.emptyList()));
    }

    @Test
    @DisplayName("H-013 / N-011: Candidates missing measured fit return INSUFFICIENT_TO_DIFFERENTIATE")
    void missingMeasuredFit_returnsInsufficientToDifferentiate() {
        InternalCandidateRanking cand1 = InternalCandidateRanking.builder()
                .userId(1L).rankingKey(850_000_000L).rankingRawFit(0.85).fitStatus(MetricDataStatus.MEASURED).build();
        InternalCandidateRanking cand2 = InternalCandidateRanking.builder()
                .userId(2L).rankingKey(800_000_000L).rankingRawFit(0.80).fitStatus(MetricDataStatus.INSUFFICIENT_DATA).build();

        assertEquals(RecommendationDifferentiationStatus.INSUFFICIENT_TO_DIFFERENTIATE,
                AutoAssignmentService.evaluateDifferentiationStatus(List.of(cand1, cand2)));
    }

    @Test
    @DisplayName("H-013 / N-011: Differing workloads with equal measured fit return INSUFFICIENT_TO_DIFFERENTIATE (unverified evidence)")
    void differingWorkloadEqualMeasuredFit_returnsInsufficientToDifferentiate() {
        InternalCandidateRanking cand1 = InternalCandidateRanking.builder()
                .userId(1L).rankingKey(900_000_000L).rankingRawFit(0.80).storedWorkloadValue(10)
                .fitStatus(MetricDataStatus.MEASURED).workloadStatus(MetricDataStatus.UNVERIFIED).build();
        InternalCandidateRanking cand2 = InternalCandidateRanking.builder()
                .userId(2L).rankingKey(700_000_000L).rankingRawFit(0.80).storedWorkloadValue(50)
                .fitStatus(MetricDataStatus.MEASURED).workloadStatus(MetricDataStatus.UNVERIFIED).build();

        assertEquals(RecommendationDifferentiationStatus.INSUFFICIENT_TO_DIFFERENTIATE,
                AutoAssignmentService.evaluateDifferentiationStatus(List.of(cand1, cand2)));
    }

    @Test
    @DisplayName("H-013 / N-011: Candidates with distinct measured raw Fit return DIFFERENTIATED")
    void distinctMeasuredFit_returnsDifferentiated() {
        InternalCandidateRanking cand1 = InternalCandidateRanking.builder()
                .userId(1L).rankingKey(850_000_000L).rankingRawFit(0.85).fitStatus(MetricDataStatus.MEASURED).build();
        InternalCandidateRanking cand2 = InternalCandidateRanking.builder()
                .userId(2L).rankingKey(800_000_000L).rankingRawFit(0.80).fitStatus(MetricDataStatus.MEASURED).build();

        assertEquals(RecommendationDifferentiationStatus.DIFFERENTIATED,
                AutoAssignmentService.evaluateDifferentiationStatus(List.of(cand1, cand2)));
    }

    // =========================================================================
    // 6. Decision H-014: Scoring Model Versioning
    // =========================================================================

    @Test
    @DisplayName("H-014: RecommendationView sets scoringModelVersion to relative-neutral-fixed-point-v2")
    void recommendationView_scoringModelVersion_relativeNeutralFixedPointV2() {
        RecommendationView view = RecommendationView.builder()
                .projectId(10L)
                .build();

        assertEquals("allowlisted-view-v1", view.presentationContractVersion());
        assertEquals("relative-neutral-fixed-point-v2", view.scoringModelVersion(),
                "Step A must set scoringModelVersion to relative-neutral-fixed-point-v2 (H-014)");
    }
}
