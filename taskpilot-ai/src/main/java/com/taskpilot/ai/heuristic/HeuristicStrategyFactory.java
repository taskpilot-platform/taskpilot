package com.taskpilot.ai.heuristic;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class HeuristicStrategyFactory {

    private final HeuristicConfigProvider configProvider;

    public HeuristicStrategy resolve(String mode) {
        String normalized = mode == null ? "BALANCED" : mode.trim().toUpperCase(Locale.ROOT);
        HeuristicConfig config = configProvider.getConfig(normalized);

        return switch (normalized) {
            case "URGENT" -> new UrgentHeuristicStrategy(config);
            case "TRAINING" -> new TrainingHeuristicStrategy(config);
            default -> new BalancedHeuristicStrategy(config);
        };
    }
}
