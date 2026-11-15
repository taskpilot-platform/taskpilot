package com.taskpilot.projects.chat.config;

import com.taskpilot.contracts.user.dto.AccountAuthStateDto;
import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.contracts.user.dto.RealtimeAccessContext;
import com.taskpilot.contracts.user.dto.SessionAuthStateDto;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.contracts.user.port.out.SessionAuthStatePort;
import com.taskpilot.infrastructure.config.security.CurrentActorResolver;
import com.taskpilot.infrastructure.config.security.JwtService;
import com.taskpilot.infrastructure.config.security.TaskPilotPrincipal;
import com.taskpilot.infrastructure.observability.TaskPilotMetrics;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Pattern PROJECT_CHAT_TOPIC = Pattern.compile("^/topic/projects/(\\d{1,18})/chat$");
    private static final String ACTIVE_LIFECYCLE = "ACTIVE";

    private final JwtService jwtService;
    private final AccountAuthStatePort accountAuthStatePort;
    private final SessionAuthStatePort sessionAuthStatePort;
    private final ProjectSecurityService projectSecurityService;
    private TaskPilotMetrics metrics = TaskPilotMetrics.noop();

    @Autowired(required = false)
    public void setMetrics(TaskPilotMetrics metrics) {
        this.metrics = metrics != null ? metrics : TaskPilotMetrics.noop();
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (acc == null || acc.getCommand() == null) {
            return message;
        }

        try {
            switch (acc.getCommand()) {
                case CONNECT, STOMP -> {
                    String authHeader = acc.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
                    if (authHeader == null) {
                        authHeader = acc.getFirstNativeHeader("authorization");
                    }
                    acc.setUser(authenticate(authHeader));
                }
                case SUBSCRIBE -> {
                    Principal user = requireActiveUser(acc);
                    authorizeSubscribe(user, acc.getDestination());
                }
                case SEND -> {
                    requireActiveUser(acc);
                    if (acc.getDestination() == null || !acc.getDestination().startsWith("/app/")) {
                        throw new AccessDeniedException("SEND is only allowed to /app/**");
                    }
                }
                default -> {
                }
            }
            return message;
        } catch (AccessDeniedException ex) {
            metrics.recordWebSocketMessage("auth_denied");
            throw ex;
        }
    }

    private Authentication authenticate(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            throw new AccessDeniedException("Missing bearer token");
        }
        String jwt = header.substring(7);
        JwtService.JwtClaims claims = jwtService.parseAndValidate(jwt)
                .orElseThrow(() -> new AccessDeniedException("Invalid or expired token"));

        AccountAuthStateDto account = accountAuthStatePort.findById(claims.userId())
                .orElseThrow(() -> new AccessDeniedException("Account unavailable"));
        if (!ACTIVE_LIFECYCLE.equals(account.lifecycle())
                || account.tokenVersion() != claims.tokenVersion()
                || account.role() == null
                || account.role().isBlank()) {
            throw new AccessDeniedException("Account inactive or security epoch invalidated");
        }

        SessionAuthStateDto session = sessionAuthStatePort.findById(claims.sessionId())
                .orElseThrow(() -> new AccessDeniedException("Session unavailable"));
        if (session.revoked()
                || session.userId() != account.userId()
                || session.tokenVersion() != claims.tokenVersion()
                || session.expiresAt() == null
                || !session.expiresAt().isAfter(Instant.now())) {
            throw new AccessDeniedException("Session revoked or expired");
        }

        CurrentActor actor = new CurrentActor(
                account.userId(),
                account.email(),
                account.role(),
                account.tokenVersion()
        );
        RealtimeAccessContext realtimeContext = new RealtimeAccessContext(
                account.userId(),
                claims.sessionId(),
                claims.tokenId(),
                account.tokenVersion(),
                claims.expiresAt()
        );
        TaskPilotPrincipal principal = new TaskPilotPrincipal(actor, realtimeContext);
        String authority = account.role().startsWith("ROLE_") ? account.role() : "ROLE_" + account.role();

        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority(authority))
        );
    }

    private void authorizeSubscribe(Principal user, String destination) {
        Matcher m = PROJECT_CHAT_TOPIC.matcher(destination == null ? "" : destination);
        if (!m.matches()) {
            throw new AccessDeniedException("Subscription not allowed");
        }
        long userId = CurrentActorResolver.extract(user)
                .map(CurrentActor::userId)
                .orElseThrow(() -> new AccessDeniedException("Unauthenticated STOMP session"));
        projectSecurityService.validateMember(Long.valueOf(m.group(1)), userId);
    }

    private Principal requireActiveUser(StompHeaderAccessor acc) {
        Principal user = acc.getUser();
        if (user == null || CurrentActorResolver.extract(user).isEmpty()) {
            throw new AccessDeniedException("Unauthenticated STOMP session");
        }
        Optional<RealtimeAccessContext> rtOpt = CurrentActorResolver.extractRealtimeContext(user);
        if (rtOpt.isPresent()) {
            RealtimeAccessContext rt = rtOpt.get();
            AccountAuthStateDto account = accountAuthStatePort.findById(rt.userId())
                    .orElseThrow(() -> new AccessDeniedException("Account unavailable"));
            if (!ACTIVE_LIFECYCLE.equals(account.lifecycle()) || account.tokenVersion() != rt.authVersion()) {
                throw new AccessDeniedException("Session invalidated");
            }
            SessionAuthStateDto session = sessionAuthStatePort.findById(rt.sessionId())
                    .orElseThrow(() -> new AccessDeniedException("Session unavailable"));
            if (session.revoked()
                    || session.userId() != rt.userId()
                    || session.tokenVersion() != rt.authVersion()
                    || session.expiresAt() == null
                    || !session.expiresAt().isAfter(Instant.now())) {
                throw new AccessDeniedException("Session revoked or expired");
            }
        }
        return user;
    }
}
