package com.taskpilot.users.notifications.service;

import com.taskpilot.contracts.realtime.RealtimeAccessDecision;
import com.taskpilot.contracts.realtime.RealtimeAccessDenyReason;
import com.taskpilot.contracts.user.dto.AccountAuthStateDto;
import com.taskpilot.contracts.user.dto.RealtimeAccessContext;
import com.taskpilot.contracts.user.dto.SecurityChangeReason;
import com.taskpilot.contracts.user.dto.SessionAuthStateDto;
import com.taskpilot.contracts.user.dto.SessionRevocationReason;
import com.taskpilot.contracts.user.dto.UserSecurityChangedEvent;
import com.taskpilot.contracts.user.dto.UserSessionRevokedEvent;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.contracts.user.port.out.RealtimeSessionAuthorityPort;
import com.taskpilot.contracts.user.port.out.RealtimeTokenBlocklistPort;
import com.taskpilot.contracts.user.port.out.SessionAuthStatePort;
import com.taskpilot.users.auth.adapter.out.RealtimeSessionAuthorityAdapter;
import com.taskpilot.users.common.enums.NotificationType;
import com.taskpilot.users.notifications.dto.NotificationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class P4NotificationRealtimeServiceTest {

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        MutableClock(Instant initial) {
            this.instant = new AtomicReference<>(initial);
        }

        void advanceSeconds(long seconds) {
            instant.updateAndGet(cur -> cur.plusSeconds(seconds));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }

    @Test
    void sessionAuthorityAdapter_enforcesExpiryBlocklistLifecycleVersionAndSessionRevocation() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-17T00:00:00Z"));
        AccountAuthStatePort accountPort = mock(AccountAuthStatePort.class);
        SessionAuthStatePort sessionPort = mock(SessionAuthStatePort.class);
        RealtimeTokenBlocklistPort blocklistPort = mock(RealtimeTokenBlocklistPort.class);
        RealtimeSessionAuthorityAdapter adapter = new RealtimeSessionAuthorityAdapter(
                accountPort, sessionPort, blocklistPort, clock);

        UUID sid = UUID.randomUUID();
        RealtimeAccessContext ctx = new RealtimeAccessContext(10L, sid, "jti-1", 2L, clock.instant().plusSeconds(120));

        assertThat(adapter.evaluateSession(null).denyReason()).isEqualTo(RealtimeAccessDenyReason.UNAUTHENTICATED);

        when(blocklistPort.isTokenRevoked("jti-1")).thenReturn(false);
        when(accountPort.findById(10L)).thenReturn(Optional.of(
                new AccountAuthStateDto(10L, "u@test.local", "USER", "ACTIVE", true, 2L)));
        when(sessionPort.findById(sid)).thenReturn(Optional.of(
                new SessionAuthStateDto(sid, 10L, 2L, clock.instant().plusSeconds(300), false)));
        assertThat(adapter.isSessionCurrent(ctx)).isTrue();

        // Blocklisted JTI fails closed
        when(blocklistPort.isTokenRevoked("jti-1")).thenReturn(true);
        assertThat(adapter.evaluateSession(ctx).denyReason()).isEqualTo(RealtimeAccessDenyReason.SESSION_REVOKED);
        when(blocklistPort.isTokenRevoked("jti-1")).thenReturn(false);

        // Inactive/suspended account fails closed
        when(accountPort.findById(10L)).thenReturn(Optional.of(
                new AccountAuthStateDto(10L, "u@test.local", "USER", "ADMIN_SUSPENDED", true, 2L)));
        assertThat(adapter.evaluateSession(ctx).denyReason()).isEqualTo(RealtimeAccessDenyReason.ACCOUNT_INACTIVE);

        // Token version mismatch fails closed
        when(accountPort.findById(10L)).thenReturn(Optional.of(
                new AccountAuthStateDto(10L, "u@test.local", "USER", "ACTIVE", true, 3L)));
        assertThat(adapter.evaluateSession(ctx).denyReason()).isEqualTo(RealtimeAccessDenyReason.SESSION_REVOKED);

        // Expired token fails with AUTH_EXPIRED
        when(accountPort.findById(10L)).thenReturn(Optional.of(
                new AccountAuthStateDto(10L, "u@test.local", "USER", "ACTIVE", true, 2L)));
        clock.advanceSeconds(121);
        assertThat(adapter.evaluateSession(ctx).denyReason()).isEqualTo(RealtimeAccessDenyReason.AUTH_EXPIRED);
    }

    @Test
    void notificationRealtimeService_preservesOtherSessionOnSingleLogoutAndEnforcesExpiryBounds() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-17T00:00:00Z"));
        RealtimeSessionAuthorityPort authorityPort = mock(RealtimeSessionAuthorityPort.class);
        when(authorityPort.evaluateSessionInNewTx(any())).thenReturn(RealtimeAccessDecision.allow());

        NotificationRealtimeService service = new NotificationRealtimeService(authorityPort, null, clock, 2);
        UUID sid1 = UUID.randomUUID();
        UUID sid2 = UUID.randomUUID();
        UUID sid3 = UUID.randomUUID();

        RealtimeAccessContext c1 = new RealtimeAccessContext(10L, sid1, "jti-1", 1L, clock.instant().plusSeconds(120));
        RealtimeAccessContext c2 = new RealtimeAccessContext(10L, sid2, "jti-2", 1L, clock.instant().plusSeconds(300));
        RealtimeAccessContext c3 = new RealtimeAccessContext(10L, sid3, "jti-3", 1L, clock.instant().plusSeconds(300));

        SseEmitter e1 = service.subscribe(c1, 1L);
        SseEmitter e2 = service.subscribe(c2, 1L);
        assertThat(e1).isNotNull();
        assertThat(e2).isNotNull();
        assertThat(service.activeCountForUser(10L)).isEqualTo(2);

        // Logout sid1 -> sid2 stays active
        service.onUserSessionRevoked(new UserSessionRevokedEvent(
                UUID.randomUUID(), 10L, sid1, 1L, clock.instant(), SessionRevocationReason.LOGOUT));
        assertThat(service.activeCountForSession(sid1)).isZero();
        assertThat(service.activeCountForSession(sid2)).isEqualTo(1);

        // Subscribe sid3 -> publish succeeds to both sid2 and sid3
        service.subscribe(c3, 1L);
        assertThat(service.activeCountForUser(10L)).isEqualTo(2);
        service.publishCreated(new NotificationResponse(
                1L, 10L, "Hi", "Body", NotificationType.SYSTEM, false, "/profile", clock.instant()), 2L);

        // Security epoch change closes remaining sessions with authVersion < 2
        service.onUserSecurityChanged(new UserSecurityChangedEvent(
                UUID.randomUUID(), 10L, 2L, clock.instant(), SecurityChangeReason.PASSWORD_RESET));
        assertThat(service.activeCountForUser(10L)).isZero();

        // Idle expiry closes emitter via clock sweep
        RealtimeAccessContext c4 = new RealtimeAccessContext(11L, UUID.randomUUID(), "jti-4", 1L, clock.instant().plusSeconds(30));
        service.subscribe(c4, 0L);
        assertThat(service.activeCountForUser(11L)).isEqualTo(1);
        clock.advanceSeconds(31);
        service.sweepExpired();
        assertThat(service.activeCountForUser(11L)).isZero();
    }
}
