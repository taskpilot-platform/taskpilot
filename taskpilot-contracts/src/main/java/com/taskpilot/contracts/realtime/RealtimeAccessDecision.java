package com.taskpilot.contracts.realtime;

/**
 * Authoritative decision returned by realtime session/project authorization checks.
 */
public record RealtimeAccessDecision(
        boolean allowed,
        RealtimeAccessDenyReason denyReason,
        String detail
) {

    private static final RealtimeAccessDecision ALLOWED = new RealtimeAccessDecision(true, null, null);

    public static RealtimeAccessDecision allow() {
        return ALLOWED;
    }

    public static RealtimeAccessDecision deny(RealtimeAccessDenyReason reason, String detail) {
        return new RealtimeAccessDecision(
                false,
                reason != null ? reason : RealtimeAccessDenyReason.SESSION_REVOKED,
                detail
        );
    }
}
