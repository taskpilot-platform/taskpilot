package com.taskpilot.ai.heuristic;

public interface HeuristicStrategy {

    String mode();

    HeuristicWeights weights();

    HeuristicNormalizationConfig normalization();

    default NormalizedScores normalize(RawScores raw, ScoreRanges ranges) {
        HeuristicNormalizationConfig config = normalization();
        double fit = ranges.fit().normalize(raw.fit(), config.fit());
        double load = ranges.load().normalize(raw.load(), config.load());
        double perf = ranges.performance().normalize(raw.performance(), config.performance());
        return new NormalizedScores(fit, load, perf);
    }

    default double score(NormalizedScores normalized) {
        HeuristicWeights weights = weights();
        return (weights.fitWeight() * normalized.fit())
                - (weights.loadWeight() * normalized.load())
                + (weights.performanceWeight() * normalized.performance());
    }
}
