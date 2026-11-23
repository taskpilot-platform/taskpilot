package com.taskpilot.contracts.project.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Canonical after-commit event published whenever a user loses project access or management role.
 */
public record ProjectAccessRevokedEvent(
        UUID eventId,
        long projectId,
        long userId,
        long rosterVersion,
        Instant occurredAt,
        AccessRevocationReason reason
) {
}
