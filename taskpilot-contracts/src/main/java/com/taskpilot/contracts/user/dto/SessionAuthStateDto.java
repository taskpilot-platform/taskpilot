package com.taskpilot.contracts.user.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Authoritative durable session state backing access and refresh token validation.
 */
public record SessionAuthStateDto(
        UUID sessionId,
        long userId,
        long tokenVersion,
        Instant expiresAt,
        boolean revoked
) {
}
