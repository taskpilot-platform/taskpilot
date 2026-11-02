package com.taskpilot.projects.chat.config;

import com.taskpilot.contracts.user.dto.UserIdentityDto;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.infrastructure.config.security.JwtService;
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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StompAuthChannelInterceptorTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private UserIdentityPort userIdentityPort;

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

    @Test
    void connect_missingToken_throwsAccessDenied() {
        StompHeaderAccessor acc = StompHeaderAccessor.create(StompCommand.CONNECT);
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(frame(acc), channel));
    }

    @Test
    void connect_validToken_setsUserPrincipal() {
        when(jwtService.isTokenValid("good-token")).thenReturn(true);
        when(jwtService.extractEmail("good-token")).thenReturn("alice@taskpilot.io");
        when(jwtService.extractRole("good-token")).thenReturn("USER");

        StompHeaderAccessor acc = StompHeaderAccessor.create(StompCommand.CONNECT);
        acc.setNativeHeader(HttpHeaders.AUTHORIZATION, "Bearer good-token");

        interceptor.preSend(frame(acc), channel);

        assertNotNull(acc.getUser());
        assertEquals("alice@taskpilot.io", acc.getUser().getName());
    }

    @Test
    void subscribe_validProjectChat_validatesMember() {
        when(userIdentityPort.findByEmail("alice@taskpilot.io"))
                .thenReturn(Optional.of(new UserIdentityDto(42L, "alice@taskpilot.io")));

        StompHeaderAccessor acc = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        acc.setUser(new UsernamePasswordAuthenticationToken("alice@taskpilot.io", null, List.of()));
        acc.setDestination("/topic/projects/15/chat");

        interceptor.preSend(frame(acc), channel);

        verify(projectSecurityService).validateMember(15L, 42L);
    }

    @Test
    void subscribe_wildcardTopic_throwsAccessDenied() {
        StompHeaderAccessor acc = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        acc.setUser(new UsernamePasswordAuthenticationToken("alice@taskpilot.io", null, List.of()));
        acc.setDestination("/topic/**");

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(frame(acc), channel));
        verifyNoInteractions(projectSecurityService);
    }

    @Test
    void send_directToTopic_throwsAccessDenied() {
        StompHeaderAccessor acc = StompHeaderAccessor.create(StompCommand.SEND);
        acc.setUser(new UsernamePasswordAuthenticationToken("alice@taskpilot.io", null, List.of()));
        acc.setDestination("/topic/projects/5/chat");

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(frame(acc), channel));
    }
}
