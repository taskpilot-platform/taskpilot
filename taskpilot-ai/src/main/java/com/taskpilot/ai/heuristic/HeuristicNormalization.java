package com.taskpilot.ai.heuristic;

import com.taskpilot.infrastructure.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.Locale;

public enum HeuristicNormalization {
    BENCHMARK_BENEFIT,
    BENCHMARK_COST;

    public static HeuristicNormalization fromString(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    "Missing heuristic normalization: " + fieldName);
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "BENCHMARK_BENEFIT", "BENEFIT", "THUAN" -> BENCHMARK_BENEFIT;
            case "BENCHMARK_COST", "COST", "NGHICH" -> BENCHMARK_COST;
            default -> throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    "Invalid heuristic normalization: " + fieldName + "=" + value);
        };
    }
}
