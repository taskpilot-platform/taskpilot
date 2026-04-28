package com.taskpilot.ai.heuristic;

public class TrainingHeuristicStrategy implements HeuristicStrategy {
    private final HeuristicConfig config;

    public TrainingHeuristicStrategy(HeuristicConfig config) {
        this.config = config;
    }

    @Override
    public String mode() {
        return "TRAINING";
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
