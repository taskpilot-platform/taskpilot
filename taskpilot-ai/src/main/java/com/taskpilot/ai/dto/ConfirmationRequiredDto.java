package com.taskpilot.ai.dto;

import java.time.Instant;
import java.util.Map;

public record ConfirmationRequiredDto(
        boolean confirmationRequired,
        String actionId,
        String toolName,
        String summary,
        Map<String, Object> arguments,
        Object preview,
        Instant expiresAt) {
}
