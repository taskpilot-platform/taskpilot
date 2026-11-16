package com.taskpilot.contracts.realtime;

/**
 * Typed access-loss reason codes shared across STOMP, SSE, and authorization ports.
 */
public enum RealtimeAccessDenyReason {
    AUTH_EXPIRED,
    SESSION_REVOKED,
    ACCOUNT_INACTIVE,
    PROJECT_ACCESS_REVOKED,
    UNAUTHENTICATED;

    public String toWireCode() {
        return switch (this) {
            case AUTH_EXPIRED -> "AUTH_EXPIRED";
            case PROJECT_ACCESS_REVOKED -> "PROJECT_ACCESS_REVOKED";
            case SESSION_REVOKED, ACCOUNT_INACTIVE, UNAUTHENTICATED -> "SESSION_REVOKED";
        };
    }
}
