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
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.observability.TaskPilotMetrics;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.realtime.RealtimeSubscriptionRegistry;
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
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Pattern PROJECT_CHAT_TOPIC = Pattern.compile("^/topic/projects/(\\d{1,18})/chat$");
    private static final String ACTIVE_LIFECYCLE = "ACTIVE";

    private final JwtService jwtService;
    private final AccountAuthStatePort accountAuthStatePort;
    private final SessionAuthStatePort sessionAuthStatePort;
    private final ProjectSecurityService projectSecurityService;
    private final RealtimeSubscriptionRegistry subscriptionRegistry;
    private TaskPilotMetrics metrics = TaskPilotMetrics.noop();

    @Autowired(required = false)
    public void setMetrics(TaskPilotMetrics metrics) {
        this.metrics = metrics != null ? metrics : TaskPilotMetrics.noop();
    }

    @Autowired
    public StompAuthChannelInterceptor(
            JwtService jwtService,
            AccountAuthStatePort accountAuthStatePort,
            SessionAuthStatePort sessionAuthStatePort,
            ProjectSecurityService projectSecurityService,
            RealtimeSubscriptionRegistry subscriptionRegistry
    ) {
        this.jwtService = jwtService;
        this.accountAuthStatePort = accountAuthStatePort;
        this.sessionAuthStatePort = sessionAuthStatePort;
        this.projectSecurityService = projectSecurityService;
        this.subscriptionRegistry = subscriptionRegistry;
    }

    public StompAuthChannelInterceptor(
            JwtService jwtService,
            AccountAuthStatePort accountAuthStatePort,
            SessionAuthStatePort sessionAuthStatePort,
            ProjectSecurityService projectSecurityService
    ) {
        this(jwtService, accountAuthStatePort, sessionAuthStatePort, projectSecurityService, null);
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
                    Authentication auth = authenticate(authHeader);
                    acc.setUser(auth);
                    if (subscriptionRegistry != null && acc.getSessionId() != null) {
                        CurrentActorResolver.extractRealtimeContext(auth).ifPresent(
                                rt -> subscriptionRegistry.registerSession(acc.getSessionId(), rt));
                    }
                }
                case SUBSCRIBE -> {
                    Principal user = requireActiveUser(acc);
                    long projectId = authorizeSubscribe(user, acc.getDestination());
                    if (subscriptionRegistry != null && acc.getSessionId() != null && acc.getSubscriptionId() != null) {
                        subscriptionRegistry.registerSubscription(acc.getSessionId(), acc.getSubscriptionId(), projectId);
                    }
                }
                case UNSUBSCRIBE -> {
                    if (subscriptionRegistry != null && acc.getSessionId() != null && acc.getSubscriptionId() != null) {
                        subscriptionRegistry.removeSubscription(acc.getSessionId(), acc.getSubscriptionId());
                    }
                }
                case DISCONNECT -> {
                    if (subscriptionRegistry != null && acc.getSessionId() != null) {
                        subscriptionRegistry.removeSession(acc.getSessionId());
                    }
                }
                case SEND -> {
                    requireActiveUser(acc);
                    if (acc.getDestination() == null || !acc.getDestination().startsWith("/app/")) {
                        throw new AccessDeniedException("PROJECT_ACCESS_REVOKED: SEND is only allowed to /app/**");
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
            throw new AccessDeniedException("SESSION_REVOKED: Missing bearer token");
        }
        String jwt = header.substring(7);
        JwtService.JwtClaims claims = jwtService.parseAndValidate(jwt)
                .orElseThrow(() -> new AccessDeniedException("AUTH_EXPIRED: Invalid or expired token"));

        AccountAuthStateDto account = accountAuthStatePort.findById(claims.userId())
                .orElseThrow(() -> new AccessDeniedException("SESSION_REVOKED: Account unavailable"));
        if (!ACTIVE_LIFECYCLE.equals(account.lifecycle())
                || !account.emailVerified()
                || account.tokenVersion() != claims.tokenVersion()
                || account.role() == null
                || account.role().isBlank()) {
            throw new AccessDeniedException("SESSION_REVOKED: Account inactive or security epoch invalidated");
        }

        SessionAuthStateDto session = sessionAuthStatePort.findById(claims.sessionId())
                .orElseThrow(() -> new AccessDeniedException("SESSION_REVOKED: Session unavailable"));
        if (session.revoked()
                || session.userId() != account.userId()
                || session.tokenVersion() != claims.tokenVersion()) {
            throw new AccessDeniedException("SESSION_REVOKED: Session revoked");
        }
        if (session.expiresAt() == null || !session.expiresAt().isAfter(Instant.now())) {
            throw new AccessDeniedException("AUTH_EXPIRED: Session expired");
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

    private long authorizeSubscribe(Principal user, String destination) {
        Matcher m = PROJECT_CHAT_TOPIC.matcher(destination == null ? "" : destination);
        if (!m.matches()) {
            throw new AccessDeniedException("PROJECT_ACCESS_REVOKED: Subscription not allowed");
        }
        long userId = CurrentActorResolver.extract(user)
                .map(CurrentActor::userId)
                .orElseThrow(() -> new AccessDeniedException("SESSION_REVOKED: Unauthenticated STOMP session"));
        long projectId = Long.parseLong(m.group(1));
        try {
            projectSecurityService.validateMember(projectId, userId);
        } catch (BusinessException ex) {
            throw new AccessDeniedException("PROJECT_ACCESS_REVOKED: " + ex.getMessage());
        }
        return projectId;
    }

    private Principal requireActiveUser(StompHeaderAccessor acc) {
        Principal user = acc.getUser();
        if (user == null || CurrentActorResolver.extract(user).isEmpty()) {
            throw new AccessDeniedException("SESSION_REVOKED: Unauthenticated STOMP session");
        }
        Optional<RealtimeAccessContext> rtOpt = CurrentActorResolver.extractRealtimeContext(user);
        if (rtOpt.isEmpty()) {
            throw new AccessDeniedException("SESSION_REVOKED: Missing realtime session context");
        }
        if (rtOpt.isPresent()) {
            RealtimeAccessContext rt = rtOpt.get();
            Instant now = Instant.now();
            if (rt.expiresAt() == null || !rt.expiresAt().isAfter(now)) {
                throw new AccessDeniedException("AUTH_EXPIRED: Access token expired");
            }
            AccountAuthStateDto account = accountAuthStatePort.findById(rt.userId())
                    .orElseThrow(() -> new AccessDeniedException("SESSION_REVOKED: Account unavailable"));
            if (!ACTIVE_LIFECYCLE.equals(account.lifecycle()) || !account.emailVerified()
                    || account.tokenVersion() != rt.authVersion()) {
                throw new AccessDeniedException("SESSION_REVOKED: Session invalidated");
            }
            SessionAuthStateDto session = sessionAuthStatePort.findById(rt.sessionId())
                    .orElseThrow(() -> new AccessDeniedException("SESSION_REVOKED: Session unavailable"));
            if (session.revoked()
                    || session.userId() != rt.userId()
                    || session.tokenVersion() != rt.authVersion()) {
                throw new AccessDeniedException("SESSION_REVOKED: Session revoked");
            }
            if (session.expiresAt() == null || !session.expiresAt().isAfter(now)) {
                throw new AccessDeniedException("AUTH_EXPIRED: Session expired");
            }
        }
        return user;
    }
}
