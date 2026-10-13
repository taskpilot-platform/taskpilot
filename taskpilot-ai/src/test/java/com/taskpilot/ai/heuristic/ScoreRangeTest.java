package com.taskpilot.ai.heuristic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 0 Characterization Tests: ScoreRange and Normalization
 * Characterizes current ScoreRange behavior under benefit, cost, equal-range, and single-candidate conditions.
 *
 * Rules:
 * - Pure unit tests (no Spring context, no external dependencies).
 * - ScoreRange is instantiated directly (never mocked).
 */
class ScoreRangeTest {

    // =========================================================================
    // 1. Current ScoreRange benefit and cost behavior
    // =========================================================================

    @Test
    @DisplayName("current-behavior characterization: benefit normalization maps min to 0.0, mid to 0.5, max to 1.0, and clamps outliers")
    void currentBehavior_benefitNormalization_directionalCorrectness() {
        ScoreRange range = new ScoreRange(10.0, 90.0);

        // Min boundary maps to 0.0
        assertEquals(0.0, range.normalize(10.0, HeuristicNormalization.BENCHMARK_BENEFIT), 1e-9);

        // Midpoint maps to 0.5
        assertEquals(0.5, range.normalize(50.0, HeuristicNormalization.BENCHMARK_BENEFIT), 1e-9);

        // Max boundary maps to 1.0
        assertEquals(1.0, range.normalize(90.0, HeuristicNormalization.BENCHMARK_BENEFIT), 1e-9);

        // Below min is clamped to 0.0
        assertEquals(0.0, range.normalize(0.0, HeuristicNormalization.BENCHMARK_BENEFIT), 1e-9);

        // Above max is clamped to 1.0
        assertEquals(1.0, range.normalize(100.0, HeuristicNormalization.BENCHMARK_BENEFIT), 1e-9);
    }

    @Test
    @DisplayName("current-behavior characterization: cost normalization maps min to 1.0, mid to 0.5, max to 0.0, and clamps outliers")
    void currentBehavior_costNormalization_directionalCorrectness() {
        ScoreRange range = new ScoreRange(10.0, 90.0);

        // Min boundary maps to 1.0 (lowest workload = lowest cost = highest benefit)
        assertEquals(1.0, range.normalize(10.0, HeuristicNormalization.BENCHMARK_COST), 1e-9);

        // Midpoint maps to 0.5
        assertEquals(0.5, range.normalize(50.0, HeuristicNormalization.BENCHMARK_COST), 1e-9);

        // Max boundary maps to 0.0 (highest workload = highest cost = lowest benefit)
        assertEquals(0.0, range.normalize(90.0, HeuristicNormalization.BENCHMARK_COST), 1e-9);

        // Below min is clamped to 1.0
        assertEquals(1.0, range.normalize(0.0, HeuristicNormalization.BENCHMARK_COST), 1e-9);

        // Above max is clamped to 0.0
        assertEquals(0.0, range.normalize(100.0, HeuristicNormalization.BENCHMARK_COST), 1e-9);
    }

    // =========================================================================
    // 2. Current equal-range behavior (Defect HDEF-001)
    // =========================================================================

    @Test
    @DisplayName("current-behavior characterization: equal range (max <= min) defectively returns 1.0 for both benefit and cost modes (HDEF-001)")
    void currentBehavior_equalRange_returnsOnePointZeroDefect() {
        ScoreRange equalRange = new ScoreRange(50.0, 50.0);

        // In current production code (ScoreRange.java:6-8):
        // if (max <= min) { return 1.0; }
        // For BENCHMARK_BENEFIT, equal range returns 1.0 instead of neutral contribution
        assertEquals(1.0, equalRange.normalize(50.0, HeuristicNormalization.BENCHMARK_BENEFIT), 1e-9);

        // For BENCHMARK_COST, equal range returns 1.0 (maximum cost!)
        assertEquals(1.0, equalRange.normalize(50.0, HeuristicNormalization.BENCHMARK_COST), 1e-9);
    }

    @Test
    @DisplayName("current-behavior characterization: equal zero workload returns 1.0 under BENCHMARK_COST, penalizing idle members (HDEF-001)")
    void currentBehavior_equalZeroWorkload_returnsOnePointZeroDefect() {
        // When all candidates have workload = 0, range is [0.0, 0.0]
        ScoreRange zeroWorkloadRange = new ScoreRange(0.0, 0.0);

        // Cost normalization defectively returns 1.0 (treated as fully loaded!)
        assertEquals(1.0, zeroWorkloadRange.normalize(0.0, HeuristicNormalization.BENCHMARK_COST), 1e-9);
    }

    @Test
    @DisplayName("current-behavior characterization: inverted range (max < min) returns 1.0")
    void currentBehavior_invertedRange_returnsOnePointZero() {
        ScoreRange invertedRange = new ScoreRange(80.0, 20.0);

        assertEquals(1.0, invertedRange.normalize(50.0, HeuristicNormalization.BENCHMARK_BENEFIT), 1e-9);
        assertEquals(1.0, invertedRange.normalize(50.0, HeuristicNormalization.BENCHMARK_COST), 1e-9);
    }

    // =========================================================================
    // 3. Current single-candidate behavior (Defect HDEF-009)
    // =========================================================================

    @Test
    @DisplayName("current-behavior characterization: single candidate produces equal ranges where all dimensions collapse to 1.0 (HDEF-009)")
    void currentBehavior_singleCandidate_allScoresBecomeOnePointZeroDefect() {
        // Single candidate with raw scores: fit = 0.80, load = 0.0, performance = 0.50
        RawScores singleCandidateRaw = new RawScores(0.80, 0.0, 0.50);
        ScoreRanges ranges = ScoreRanges.from(List.of(singleCandidateRaw));

        // ScoreRanges.from constructs [0.8, 0.8], [0.0, 0.0], [0.5, 0.5]
        assertEquals(0.80, ranges.fit().min(), 1e-9);
        assertEquals(0.80, ranges.fit().max(), 1e-9);
        assertEquals(0.0, ranges.load().min(), 1e-9);
        assertEquals(0.0, ranges.load().max(), 1e-9);
        assertEquals(0.50, ranges.performance().min(), 1e-9);
        assertEquals(0.50, ranges.performance().max(), 1e-9);

        // Because max <= min for all ranges, current normalize() returns 1.0 for every dimension
        double normFit = ranges.fit().normalize(0.80, HeuristicNormalization.BENCHMARK_BENEFIT);
        double normLoad = ranges.load().normalize(0.0, HeuristicNormalization.BENCHMARK_COST);
        double normPerf = ranges.performance().normalize(0.50, HeuristicNormalization.BENCHMARK_BENEFIT);

        assertEquals(1.0, normFit, "Fit collapsed to artificial 1.0");
        assertEquals(1.0, normLoad, "Load collapsed to artificial 1.0");
        assertEquals(1.0, normPerf, "Performance collapsed to artificial 1.0");
    }
}
