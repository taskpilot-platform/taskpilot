package com.taskpilot.projects.chat.config;

import com.taskpilot.contracts.user.dto.AccountAuthStateDto;
import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.contracts.user.dto.RealtimeAccessContext;
import com.taskpilot.contracts.user.dto.SessionAuthStateDto;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.contracts.user.port.out.SessionAuthStatePort;
import com.taskpilot.infrastructure.config.security.JwtService;
import com.taskpilot.infrastructure.config.security.TaskPilotPrincipal;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StompAuthChannelInterceptorTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private AccountAuthStatePort accountAuthStatePort;

    @Mock
    private SessionAuthStatePort sessionAuthStatePort;

    @Mock
    private ProjectSecurityService projectSecurityService;

    @Mock
    private MessageChannel channel;

    @InjectMocks
    private StompAuthChannelInterceptor interceptor;

    private static Message<byte[]> frame(StompHeaderAccessor acc) {
        acc.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], acc.getMessageHeaders());
    }

    private static UsernamePasswordAuthenticationToken actorPrincipal(long userId, long tokenVersion, UUID sid) {
        CurrentActor actor = new CurrentActor(userId, "alice@taskpilot.io", "USER", tokenVersion);
        RealtimeAccessContext realtime = new RealtimeAccessContext(userId, sid, "jti-1", tokenVersion, Instant.now().plusSeconds(300));
        return new UsernamePasswordAuthenticationToken(new TaskPilotPrincipal(actor, realtime), null, List.of());
    }

    @Test
    void connect_missingToken_throwsAccessDenied() {
        StompHeaderAccessor acc = StompHeaderAccessor.create(StompCommand.CONNECT);
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(frame(acc), channel));
    }

    @Test
    void connect_validToken_setsUidPrincipal() {
        UUID sid = UUID.randomUUID();
        JwtService.JwtClaims claims = new JwtService.JwtClaims(
                42L, 3L, sid, "jti-1", "USER", Instant.now(), Instant.now().plusSeconds(300), JwtService.TOKEN_SCHEMA_V2);
        when(jwtService.parseAndValidate("good-token")).thenReturn(Optional.of(claims));
        when(accountAuthStatePort.findById(42L))
                .thenReturn(Optional.of(new AccountAuthStateDto(42L, "alice@taskpilot.io", "USER", "ACTIVE", false, 3L)));
        when(sessionAuthStatePort.findById(sid))
                .thenReturn(Optional.of(new SessionAuthStateDto(sid, 42L, 3L, Instant.now().plusSeconds(3600), false)));

        StompHeaderAccessor acc = StompHeaderAccessor.create(StompCommand.CONNECT);
        acc.setNativeHeader(HttpHeaders.AUTHORIZATION, "Bearer good-token");

        interceptor.preSend(frame(acc), channel);

        assertNotNull(acc.getUser());
        assertEquals("42", acc.getUser().getName());
    }

    @Test
    void subscribe_validProjectChat_validatesMember() {
        UUID sid = UUID.randomUUID();
        when(accountAuthStatePort.findById(42L))
                .thenReturn(Optional.of(new AccountAuthStateDto(42L, "alice@taskpilot.io", "USER", "ACTIVE", false, 3L)));
        when(sessionAuthStatePort.findById(sid))
                .thenReturn(Optional.of(new SessionAuthStateDto(sid, 42L, 3L, Instant.now().plusSeconds(3600), false)));

        StompHeaderAccessor acc = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        acc.setUser(actorPrincipal(42L, 3L, sid));
        acc.setDestination("/topic/projects/15/chat");

        interceptor.preSend(frame(acc), channel);

        verify(projectSecurityService).validateMember(15L, 42L);
    }

    @Test
    void subscribe_wildcardTopic_throwsAccessDenied() {
        UUID sid = UUID.randomUUID();
        when(accountAuthStatePort.findById(42L))
                .thenReturn(Optional.of(new AccountAuthStateDto(42L, "alice@taskpilot.io", "USER", "ACTIVE", false, 3L)));
        when(sessionAuthStatePort.findById(sid))
                .thenReturn(Optional.of(new SessionAuthStateDto(sid, 42L, 3L, Instant.now().plusSeconds(3600), false)));

        StompHeaderAccessor acc = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        acc.setUser(actorPrincipal(42L, 3L, sid));
        acc.setDestination("/topic/**");

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(frame(acc), channel));
        verifyNoInteractions(projectSecurityService);
    }

    @Test
    void send_directToTopic_throwsAccessDenied() {
        UUID sid = UUID.randomUUID();
        when(accountAuthStatePort.findById(42L))
                .thenReturn(Optional.of(new AccountAuthStateDto(42L, "alice@taskpilot.io", "USER", "ACTIVE", false, 3L)));
        when(sessionAuthStatePort.findById(sid))
                .thenReturn(Optional.of(new SessionAuthStateDto(sid, 42L, 3L, Instant.now().plusSeconds(3600), false)));

        StompHeaderAccessor acc = StompHeaderAccessor.create(StompCommand.SEND);
        acc.setUser(actorPrincipal(42L, 3L, sid));
        acc.setDestination("/topic/projects/5/chat");

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(frame(acc), channel));
    }
}
