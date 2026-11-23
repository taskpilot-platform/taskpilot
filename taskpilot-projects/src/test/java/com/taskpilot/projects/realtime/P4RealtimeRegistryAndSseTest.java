package com.taskpilot.projects.realtime;

import com.taskpilot.contracts.project.dto.AccessRevocationReason;
import com.taskpilot.contracts.project.dto.ProjectAccessRevokedEvent;
import com.taskpilot.contracts.realtime.RealtimeAccessDecision;
import com.taskpilot.contracts.realtime.RealtimeAccessDenyReason;
import com.taskpilot.contracts.realtime.RealtimeAccessPort;
import com.taskpilot.contracts.user.dto.RealtimeAccessContext;
import com.taskpilot.contracts.user.dto.SessionRevocationReason;
import com.taskpilot.contracts.user.dto.UserSessionRevokedEvent;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.chat.config.StompOutboundAuthorizationInterceptor;
import com.taskpilot.projects.chat.config.StompWebSocketSessionTracker;
import com.taskpilot.projects.tasks.dto.TaskCommentDto;
import com.taskpilot.projects.tasks.service.TaskCommentRealtimeService;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class P4RealtimeRegistryAndSseTest {

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        MutableClock(Instant initial) {
            this.instant = new AtomicReference<>(initial);
        }

        void advanceSeconds(long seconds) {
            instant.updateAndGet(cur -> cur.plusSeconds(seconds));
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }

    private static RealtimeAccessContext ctx(long userId, UUID sid, Instant exp) {
        return new RealtimeAccessContext(userId, sid, "jti-" + sid, 1L, exp);
    }

    @Test
    void stompRegistry_enforcesPerUserBoundExpirySweepAndSingleSessionRevoke() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-17T00:00:00Z"));
        StompWebSocketSessionTracker tracker = mock(StompWebSocketSessionTracker.class);
        RealtimeAccessPort accessPort = mock(RealtimeAccessPort.class);
        when(accessPort.evaluateSession(any())).thenReturn(RealtimeAccessDecision.allow());

        RealtimeSubscriptionRegistry registry = new RealtimeSubscriptionRegistry(tracker, accessPort, clock, 2, 2);
        UUID sid1 = UUID.randomUUID();
        UUID sid2 = UUID.randomUUID();
        UUID sid3 = UUID.randomUUID();

        registry.registerSession("ws-1", ctx(10L, sid1, clock.instant().plusSeconds(60)));
        registry.registerSession("ws-2", ctx(10L, sid2, clock.instant().plusSeconds(300)));
        assertThat(registry.activeSessionCountForUser(10L)).isEqualTo(2);

        // Exceeding maxSessionsPerUser=2 evicts oldest ws-1
        registry.registerSession("ws-3", ctx(10L, sid3, clock.instant().plusSeconds(300)));
        assertThat(registry.activeSessionCountForUser(10L)).isEqualTo(2);
        verify(tracker).closeSession("ws-1", RealtimeAccessDenyReason.SESSION_REVOKED);

        // Single session logout closes only ws-2, preserves ws-3
        registry.onUserSessionRevoked(new UserSessionRevokedEvent(
                UUID.randomUUID(), 10L, sid2, 1L, clock.instant(), SessionRevocationReason.LOGOUT));
        // Duplicate event is idempotent
        registry.onUserSessionRevoked(new UserSessionRevokedEvent(
                UUID.randomUUID(), 10L, sid2, 1L, clock.instant(), SessionRevocationReason.LOGOUT));
        verify(tracker, times(1)).closeSession("ws-2", RealtimeAccessDenyReason.SESSION_REVOKED);
        assertThat(registry.activeSessionCountForUser(10L)).isEqualTo(1);

        // Advancing injectable clock past sid3 expiry closes idle ws-3
        clock.advanceSeconds(301);
        registry.sweepExpired();
        verify(tracker).closeSession("ws-3", RealtimeAccessDenyReason.AUTH_EXPIRED);
        assertThat(registry.activeSessionCountForUser(10L)).isZero();
    }

    @Test
    void stompOutboundInterceptor_dropsUnauthorizedAndMissingRegistrations() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-17T00:00:00Z"));
        StompWebSocketSessionTracker tracker = mock(StompWebSocketSessionTracker.class);
        RealtimeAccessPort accessPort = mock(RealtimeAccessPort.class);

        RealtimeSubscriptionRegistry registry = new RealtimeSubscriptionRegistry(tracker, accessPort, clock, 4, 4);
        StompOutboundAuthorizationInterceptor outbound = new StompOutboundAuthorizationInterceptor(registry, accessPort);

        UUID sid = UUID.randomUUID();
        RealtimeAccessContext context = ctx(10L, sid, clock.instant().plusSeconds(300));
        registry.registerSession("ws-1", context);
        registry.registerSubscription("ws-1", "sub-1", 55L);

        SimpMessageHeaderAccessor acc = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        acc.setSessionId("ws-1");
        acc.setSubscriptionId("sub-1");
        acc.setDestination("/topic/projects/55/chat");
        Message<byte[]> msg = MessageBuilder.createMessage(new byte[]{1, 2}, acc.getMessageHeaders());

        when(accessPort.evaluateProjectAccess(55L, context)).thenReturn(RealtimeAccessDecision.allow());
        assertThat(outbound.preSend(msg, mock(MessageChannel.class))).isNotNull();

        // After revocation, outbound drops private MESSAGE (replaces with empty closed-session HEARTBEAT) and closes WS session
        when(accessPort.evaluateProjectAccess(55L, context))
                .thenReturn(RealtimeAccessDecision.deny(RealtimeAccessDenyReason.PROJECT_ACCESS_REVOKED, "Removed"));
        Message<?> afterRevoke = outbound.preSend(msg, mock(MessageChannel.class));
        assertThat(afterRevoke).isNotNull();
        assertThat(SimpMessageHeaderAccessor.getMessageType(afterRevoke.getHeaders())).isEqualTo(SimpMessageType.HEARTBEAT);
        assertThat((byte[]) afterRevoke.getPayload()).isEmpty();
        verify(tracker).closeSession("ws-1", RealtimeAccessDenyReason.PROJECT_ACCESS_REVOKED);

        // Subsequent message without active registration stays blocked
        Message<?> afterEvict = outbound.preSend(msg, mock(MessageChannel.class));
        assertThat(afterEvict).isNotNull();
        assertThat(SimpMessageHeaderAccessor.getMessageType(afterEvict.getHeaders())).isEqualTo(SimpMessageType.HEARTBEAT);
        assertThat((byte[]) afterEvict.getPayload()).isEmpty();
    }

    @Test
    void commentRealtimeService_checksInitialPayloadHeartbeatExpiryBoundsAndEvents() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-17T00:00:00Z"));
        RealtimeAccessPort accessPort = mock(RealtimeAccessPort.class);
        TaskCommentRealtimeService service = new TaskCommentRealtimeService(accessPort, clock, 2);

        UUID sid1 = UUID.randomUUID();
        UUID sid2 = UUID.randomUUID();
        UUID sid3 = UUID.randomUUID();
        RealtimeAccessContext c1 = ctx(10L, sid1, clock.instant().plusSeconds(60));
        RealtimeAccessContext c2 = ctx(10L, sid2, clock.instant().plusSeconds(120));
        RealtimeAccessContext c3 = ctx(10L, sid3, clock.instant().plusSeconds(120));

        // Deny before subscribe when unauthorized
        when(accessPort.evaluateProjectAccess(eq(5L), eq(c1)))
                .thenReturn(RealtimeAccessDecision.deny(RealtimeAccessDenyReason.PROJECT_ACCESS_REVOKED, "Forbidden"));
        assertThatThrownBy(() -> service.subscribe(5L, 99L, c1))
                .isInstanceOf(BusinessException.class);

        // Allow subscribe and check per-user bound=2
        when(accessPort.evaluateProjectAccess(anyLong(), any())).thenReturn(RealtimeAccessDecision.allow());
        SseEmitter e1 = service.subscribe(5L, 99L, c1);
        SseEmitter e2 = service.subscribe(5L, 99L, c2);
        assertThat(e1).isNotNull();
        assertThat(e2).isNotNull();
        assertThat(service.activeCountForUser(10L)).isEqualTo(2);

        // Adding 3rd evicts oldest (e1)
        service.subscribe(5L, 99L, c3);
        assertThat(service.activeCountForUser(10L)).isEqualTo(2);

        // Heartbeat rechecks current authority; if denied, removes emitter
        when(accessPort.evaluateProjectAccess(5L, c2))
                .thenReturn(RealtimeAccessDecision.deny(RealtimeAccessDenyReason.PROJECT_ACCESS_REVOKED, "Removed"));
        service.sendHeartbeats();
        assertThat(service.activeCountForUser(10L)).isEqualTo(1);

        // Revocation event closes remaining matching project emitter
        service.onProjectAccessRevoked(new ProjectAccessRevokedEvent(
                UUID.randomUUID(), 5L, 10L, 2L, clock.instant(), AccessRevocationReason.MEMBER_REMOVED));
        assertThat(service.activeCountForUser(10L)).isZero();
        assertThat(service.activeCountForTask(99L)).isZero();

        // Publish after removal checks nothing / sends nothing
        service.publishCreated(new TaskCommentDto(
                1L, 99L, null, null, "secret", List.of(), false, null, clock.instant(), clock.instant()));

        // Expiry sweep closes idle emitter past deadline
        RealtimeAccessContext c4 = ctx(11L, UUID.randomUUID(), clock.instant().plusSeconds(30));
        AtomicBoolean completed = new AtomicBoolean(false);
        SseEmitter e4 = service.subscribe(5L, 99L, c4);
        e4.onCompletion(() -> completed.set(true));
        clock.advanceSeconds(31);
        service.sweepExpired();
        assertThat(service.activeCountForUser(11L)).isZero();
    }
}
