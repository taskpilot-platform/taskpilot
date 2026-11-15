package com.taskpilot.contracts.user.dto;

/**
 * Immutable representation of the currently authenticated TaskPilot actor, free of raw credentials or bearer tokens.
 */
public record CurrentActor(
        long userId,
        String currentEmail,
        String role,
        long tokenVersion
) {
}
