package com.taskpilot.contracts.user.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Bound session/token metadata for realtime transports (STOMP/SSE) allowing disconnect on session or epoch revocation.
 */
public record RealtimeAccessContext(
        long userId,
        UUID sessionId,
        String tokenId,
        long authVersion,
        Instant expiresAt
) {
}
