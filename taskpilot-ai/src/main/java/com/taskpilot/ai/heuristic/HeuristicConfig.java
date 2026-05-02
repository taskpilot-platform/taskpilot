package com.taskpilot.ai.heuristic;

public record HeuristicConfig(
        HeuristicWeights weights,
        HeuristicNormalizationConfig normalization) {
}
