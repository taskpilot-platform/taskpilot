package com.taskpilot.ai.heuristic;

public class UrgentHeuristicStrategy implements HeuristicStrategy {
    private final HeuristicConfig config;

    public UrgentHeuristicStrategy(HeuristicConfig config) {
        this.config = config;
    }

    @Override
    public String mode() {
        return "URGENT";
    }

    @Override
    public HeuristicWeights weights() {
        return config.weights();
    }

    @Override
    public HeuristicNormalizationConfig normalization() {
        return config.normalization();
    }
}
