package com.taskpilot.projects.chat.config;

import com.taskpilot.contracts.user.dto.AccountAuthStateDto;
import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.contracts.user.dto.RealtimeAccessContext;
import com.taskpilot.contracts.user.dto.SessionAuthStateDto;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.contracts.user.port.out.SessionAuthStatePort;
import com.taskpilot.infrastructure.config.security.JwtProperties;
import com.taskpilot.infrastructure.config.security.JwtService;
import com.taskpilot.infrastructure.config.security.JwtTokenBlocklistService;
import com.taskpilot.infrastructure.config.security.TaskPilotPrincipal;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * STOMP principal conversion verification for P1.2.
 */
@ExtendWith(MockitoExtension.class)
class StompPrincipalBaselineRedTest {

    private static final String TEST_SECRET =
            "taskpilot-p12-jwt-signing-hmac-key-0123456789abcdef0123456789abcdef";

    @Mock
    private ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    @Mock
    private AccountAuthStatePort accountAuthStatePort;
    @Mock
    private SessionAuthStatePort sessionAuthStatePort;
    @Mock
    private ProjectSecurityService projectSecurityService;
    @Mock
    private MessageChannel messageChannel;

    private JwtService jwtService;
    private StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecretKey(TEST_SECRET);
        properties.setExpiration(900_000L);
        properties.setIssuer("taskpilot");
        properties.setAudience("taskpilot-api");
        jwtService = new JwtService(properties, new JwtTokenBlocklistService(redisTemplateProvider));
        interceptor = new StompAuthChannelInterceptor(
                jwtService, accountAuthStatePort, sessionAuthStatePort, projectSecurityService);
    }

    @Test
    void connect_mustBindImmutableUidPrincipalNameInsteadOfMutableEmail() {
        UUID sid = UUID.randomUUID();
        String token = jwtService.generateToken(42L, 3L, sid, "USER");
        when(accountAuthStatePort.findById(42L))
                .thenReturn(Optional.of(new AccountAuthStateDto(42L, "alice@taskpilot.io", "USER", "ACTIVE", false, 3L)));
        when(sessionAuthStatePort.findById(sid))
                .thenReturn(Optional.of(new SessionAuthStateDto(sid, 42L, 3L, Instant.now().plusSeconds(3600), false)));

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        interceptor.preSend(message, messageChannel);

        assertThat(accessor.getUser()).isNotNull();
        assertThat(accessor.getUser().getName())
                .as("STOMP Principal.getName() must be immutable numeric uid, never raw email")
                .isEqualTo("42");
    }

    @Test
    void subscribe_withNumericUidPrincipal_mustAuthorizeMembershipWithoutEmailLookup() {
        UUID sid = UUID.randomUUID();
        when(accountAuthStatePort.findById(42L))
                .thenReturn(Optional.of(new AccountAuthStateDto(42L, "alice@taskpilot.io", "USER", "ACTIVE", false, 3L)));
        when(sessionAuthStatePort.findById(sid))
                .thenReturn(Optional.of(new SessionAuthStateDto(sid, 42L, 3L, Instant.now().plusSeconds(3600), false)));

        CurrentActor actor = new CurrentActor(42L, "alice@taskpilot.io", "USER", 3L);
        RealtimeAccessContext realtime = new RealtimeAccessContext(42L, sid, "jti-1", 3L, Instant.now().plusSeconds(300));
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/projects/100/chat");
        accessor.setUser(new UsernamePasswordAuthenticationToken(new TaskPilotPrincipal(actor, realtime), null, List.of()));
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        interceptor.preSend(message, messageChannel);

        verify(projectSecurityService).validateMember(100L, 42L);
        verify(accountAuthStatePort).findById(42L);
        verify(sessionAuthStatePort).findById(sid);
    }
}
