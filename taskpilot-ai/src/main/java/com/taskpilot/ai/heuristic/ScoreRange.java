package com.taskpilot.ai.heuristic;

public record ScoreRange(double min, double max) {

    public boolean isEqualRange() {
        return max <= min;
    }

    public double normalize(double value, HeuristicNormalization mode) {
        if (max <= min) {
            return 1.0;
        }

        double normalized = mode == HeuristicNormalization.BENCHMARK_COST
                ? (max - value) / (max - min)
                : (value - min) / (max - min);

        return clamp01(normalized);
    }

    /**
     * Decision H-004: Neutral normalization for Phase 1 Step A.
     * When max <= min (no variance across candidate set), the ranking contribution is neutral (0.0).
     */
    public double normalizeNeutral(double value, HeuristicNormalization mode) {
        if (max <= min) {
            return 0.0;
        }
        return normalize(value, mode);
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
