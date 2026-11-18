package com.taskpilot.ai.heuristic;

public record NormalizedScores(
        double fit,
        double load,
        double performance) {
    public static final String PROJECT_RANKING_VERSION = "normalize-v2";
    private static final double WORKLOAD_HALF_SATURATION = 5.0;

    public static NormalizedScores forProjectTasks(double fit, long activeCount, double performance) {
        return new NormalizedScores(fit, activeCount / (activeCount + WORKLOAD_HALF_SATURATION), performance);
    }
}
