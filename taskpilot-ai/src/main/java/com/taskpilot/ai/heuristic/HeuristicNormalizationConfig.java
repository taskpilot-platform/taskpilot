package com.taskpilot.ai.heuristic;

public record HeuristicNormalizationConfig(
        HeuristicNormalization fit,
        HeuristicNormalization load,
        HeuristicNormalization performance) {
}
