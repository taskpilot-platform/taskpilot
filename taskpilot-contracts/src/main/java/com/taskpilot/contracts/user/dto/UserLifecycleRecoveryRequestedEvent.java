package com.taskpilot.contracts.user.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Canonical domain event contract published when an eligible account begins lifecycle recovery (consumed in P2).
 */
public record UserLifecycleRecoveryRequestedEvent(
        UUID eventId,
        long userId,
        long tokenVersion,
        String lifecycle,
        Instant occurredAt
) {
}
