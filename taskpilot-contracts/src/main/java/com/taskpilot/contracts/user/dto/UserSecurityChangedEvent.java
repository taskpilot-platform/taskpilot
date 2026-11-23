package com.taskpilot.contracts.user.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event emitted after commit when a security-relevant mutation increments {@code token_version}.
 */
public record UserSecurityChangedEvent(
        UUID eventId,
        long userId,
        long tokenVersion,
        Instant occurredAt,
        SecurityChangeReason reason
) {
}
