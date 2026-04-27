package com.taskpilot.ai.heuristic;

public class BalancedHeuristicStrategy implements HeuristicStrategy {
    private final HeuristicConfig config;

    public BalancedHeuristicStrategy(HeuristicConfig config) {
        this.config = config;
    }

    @Override
    public String mode() {
        return "BALANCED";
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
