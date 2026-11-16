package com.taskpilot.users.auth.adapter.out;

import com.taskpilot.contracts.user.dto.*;
import com.taskpilot.contracts.user.port.out.*;
import com.taskpilot.contracts.realtime.RealtimeAccessDenyReason;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class P4ReviewAuthorityTest {
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-11-17T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    @Test void verificationRevokedAccountCannotReceiveRealtimeOrAccountNotifications() {
        var accounts=mock(AccountAuthStatePort.class);var sessions=mock(SessionAuthStatePort.class);
        var clock=new MutableClock();var sid=UUID.randomUUID();
        when(accounts.findById(1L)).thenReturn(Optional.of(new AccountAuthStateDto(1L,"a@example.com","USER","ACTIVE",false,0L)));
        when(accounts.findByIdForUpdate(1L)).thenReturn(Optional.of(new AccountAuthStateDto(1L,"a@example.com","USER","ACTIVE",false,0L)));
        when(sessions.findById(sid)).thenReturn(Optional.of(new SessionAuthStateDto(sid,1L,0L,clock.now.plusSeconds(3600),false)));
        var guard=new RealtimeSessionAuthorityAdapter(accounts,sessions,null,clock);
        assertFalse(guard.evaluateSession(new RealtimeAccessContext(1L,sid,"jti",0L,clock.now.plusSeconds(60))).allowed());
        assertFalse(guard.isAccountActiveInNewTx(1L));
    }
    @Test void expiryWhileWaitingForAuthorityLocksIsRecheckedAfterTheWait() {
        var accounts=mock(AccountAuthStatePort.class);var sessions=mock(SessionAuthStatePort.class);
        var clock=new MutableClock();var sid=UUID.randomUUID();var expiry=clock.now.plusSeconds(60);
        when(accounts.findByIdForUpdate(1L)).thenAnswer(call->{clock.now=clock.now.plusSeconds(120);return Optional.of(new AccountAuthStateDto(1L,"a@example.com","USER","ACTIVE",true,0L));});
        when(sessions.findByIdForUpdate(sid)).thenReturn(Optional.of(new SessionAuthStateDto(sid,1L,0L,clock.now.plusSeconds(3600),false)));
        var guard=new RealtimeSessionAuthorityAdapter(accounts,sessions,null,clock);
        var decision=guard.evaluateSessionForUpdate(new RealtimeAccessContext(1L,sid,"jti",0L,expiry));
        assertFalse(decision.allowed());assertEquals(RealtimeAccessDenyReason.AUTH_EXPIRED,decision.denyReason());
    }
}
