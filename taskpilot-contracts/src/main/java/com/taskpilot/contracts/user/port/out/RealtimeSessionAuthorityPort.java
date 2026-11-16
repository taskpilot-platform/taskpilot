package com.taskpilot.contracts.user.port.out;

import com.taskpilot.contracts.realtime.RealtimeAccessDecision;
import com.taskpilot.contracts.user.dto.RealtimeAccessContext;

/**
 * Users-owned authority port validating current account lifecycle, token version,
 * durable session revocation, token blocklist, and expiry.
 */
public interface RealtimeSessionAuthorityPort {

    boolean isSessionCurrent(RealtimeAccessContext context);

    RealtimeAccessDecision evaluateSession(RealtimeAccessContext context);

    default RealtimeAccessDecision evaluateSessionForUpdate(RealtimeAccessContext context) {
        return evaluateSession(context);
    }

    default RealtimeAccessDecision evaluateSessionInNewTx(RealtimeAccessContext context) {
        return evaluateSessionForUpdate(context);
    }

    default boolean isAccountActiveInNewTx(long userId) {
        return true;
    }
}
