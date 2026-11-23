package com.taskpilot.ai.dto;

import java.util.Locale;

public enum RecommendationDecisionType {
    ACCEPTED,
    OVERRIDDEN,
    REJECTED,
    CANCELED,
    EXPIRED;

    public static RecommendationDecisionType fromInput(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("decisionType is required");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ACCEPT", "ACCEPTED" -> ACCEPTED;
            case "OVERRIDE", "OVERRIDDEN" -> OVERRIDDEN;
            case "REJECT", "REJECTED" -> REJECTED;
            case "CANCEL", "CANCELED" -> CANCELED;
            case "EXPIRED" -> EXPIRED;
            default -> throw new IllegalArgumentException("Unsupported decisionType: " + raw);
        };
    }
}
