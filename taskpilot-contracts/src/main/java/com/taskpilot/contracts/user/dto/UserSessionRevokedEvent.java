package com.taskpilot.contracts.user.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event emitted after commit when an individual session ({@code sid}) is revoked.
 */
public record UserSessionRevokedEvent(
        UUID eventId,
        long userId,
        UUID sessionId,
        long tokenVersion,
        Instant occurredAt,
        SessionRevocationReason reason
) {
}
