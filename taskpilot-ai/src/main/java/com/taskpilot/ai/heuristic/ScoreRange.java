package com.taskpilot.ai.heuristic;

public record ScoreRange(double min, double max) {

    public double normalize(double value, HeuristicNormalization mode) {
        if (max <= min) {
            return 1.0;
        }

        double normalized = mode == HeuristicNormalization.BENCHMARK_COST
                ? (max - value) / (max - min)
                : (value - min) / (max - min);

        return clamp01(normalized);
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
