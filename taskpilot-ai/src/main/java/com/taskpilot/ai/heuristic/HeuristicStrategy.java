package com.taskpilot.ai.heuristic;

public interface HeuristicStrategy {

    String mode();

    HeuristicWeights weights();

    HeuristicNormalizationConfig normalization();

    default NormalizedScores normalize(RawScores raw, ScoreRanges ranges) {
        HeuristicNormalizationConfig config = normalization();
        if (config != null && config.load() == HeuristicNormalization.BENCHMARK_COST) {
            throw new IllegalStateException(
                    "Invalid heuristic normalization: BENCHMARK_COST is incompatible with subtractive workload scoring (H-003)");
        }
        double fit = ranges.fit().normalize(raw.fit(), config.fit());
        double load = ranges.load().normalize(raw.load(), config.load());
        double perf = ranges.performance().normalize(raw.performance(), config.performance());
        return new NormalizedScores(fit, load, perf);
    }

    /**
     * Phase 1 Step A neutral normalization (H-003, H-004):
     * Fails closed if load == BENCHMARK_COST (H-003),
     * and maps equal-range criteria to 0.0 neutral contribution (H-004).
     */
    default NormalizedScores normalizeNeutral(RawScores raw, ScoreRanges ranges) {
        HeuristicNormalizationConfig config = normalization();
        if (config != null && config.load() == HeuristicNormalization.BENCHMARK_COST) {
            throw new IllegalStateException(
                    "Invalid heuristic normalization: BENCHMARK_COST is incompatible with subtractive workload scoring (H-003)");
        }
        double fit = ranges.fit().normalizeNeutral(raw.fit(), config.fit());
        double load = ranges.load().normalizeNeutral(raw.load(), config.load());
        double perf = ranges.performance().normalizeNeutral(raw.performance(), config.performance());
        return new NormalizedScores(fit, load, perf);
    }

    default double score(NormalizedScores normalized) {
        HeuristicWeights weights = weights();
        return (weights.fitWeight() * normalized.fit())
                - (weights.loadWeight() * normalized.load())
                + (weights.performanceWeight() * normalized.performance());
    }
}
