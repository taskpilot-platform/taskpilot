package com.taskpilot.contracts.user.dto;

/**
 * Authoritative account security state loaded from persistence during authentication and authorization checks.
 */
public record AccountAuthStateDto(
        long userId,
        String email,
        String role,
        String lifecycle,
        boolean emailVerified,
        long tokenVersion
) {
}
