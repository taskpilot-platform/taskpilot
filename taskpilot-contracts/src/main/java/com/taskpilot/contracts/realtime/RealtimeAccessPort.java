package com.taskpilot.contracts.realtime;

import com.taskpilot.contracts.user.dto.RealtimeAccessContext;

/**
 * Fail-closed port verifying current session/account and project membership authority
 * for STOMP and SSE subscriptions and outbound deliveries.
 */
public interface RealtimeAccessPort {

    boolean isSessionCurrent(RealtimeAccessContext context);

    boolean canReceiveProject(long projectId, RealtimeAccessContext context);

    RealtimeAccessDecision evaluateSession(RealtimeAccessContext context);

    RealtimeAccessDecision evaluateProjectAccess(long projectId, RealtimeAccessContext context);
}
