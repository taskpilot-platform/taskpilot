package com.taskpilot.contracts.project.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable/audited event emitted when a project has no effective active manager after admin suspension.
 */
public record ProjectManagementAttentionRequired(
        UUID eventId,
        long projectId,
        long suspendedUserId,
        Instant occurredAt,
        String reasonCode
) {
}
