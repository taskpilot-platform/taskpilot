package com.taskpilot.projects.chat.config;

import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.infrastructure.config.security.JwtService;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import lombok.RequiredArgsConstructor;
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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Pattern PROJECT_CHAT_TOPIC = Pattern.compile("^/topic/projects/(\\d{1,18})/chat$");

    private final JwtService jwtService;
    private final UserIdentityPort userIdentityPort;
    private final ProjectSecurityService projectSecurityService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (acc == null || acc.getCommand() == null) {
            return message;
        }

        switch (acc.getCommand()) {
            case CONNECT, STOMP -> {
                String authHeader = acc.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
                if (authHeader == null) {
                    authHeader = acc.getFirstNativeHeader("authorization");
                }
                acc.setUser(authenticate(authHeader));
            }
            case SUBSCRIBE -> authorizeSubscribe(requireUser(acc), acc.getDestination());
            case SEND -> {
                requireUser(acc);
                if (acc.getDestination() == null || !acc.getDestination().startsWith("/app/")) {
                    throw new AccessDeniedException("SEND is only allowed to /app/**");
                }
            }
            default -> {
            }
        }
        return message;
    }

    private Authentication authenticate(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            throw new AccessDeniedException("Missing bearer token");
        }
        String jwt = header.substring(7);
        if (!jwtService.isTokenValid(jwt)) {
            throw new AccessDeniedException("Invalid or expired token");
        }
        String role = jwtService.extractRole(jwt);
        List<SimpleGrantedAuthority> authorities = (role != null && !role.isBlank())
                ? List.of(new SimpleGrantedAuthority("ROLE_" + role))
                : List.of();
        return new UsernamePasswordAuthenticationToken(
                jwtService.extractEmail(jwt),
                null,
                authorities
        );
    }

    private void authorizeSubscribe(Principal user, String destination) {
        Matcher m = PROJECT_CHAT_TOPIC.matcher(destination == null ? "" : destination);
        if (!m.matches()) {
            throw new AccessDeniedException("Subscription not allowed");
        }
        Long userId = userIdentityPort.findByEmail(user.getName())
                .orElseThrow(() -> new AccessDeniedException("Unknown user"))
                .id();
        projectSecurityService.validateMember(Long.valueOf(m.group(1)), userId);
    }

    private static Principal requireUser(StompHeaderAccessor acc) {
        if (acc.getUser() == null) {
            throw new AccessDeniedException("Unauthenticated STOMP session");
        }
        return acc.getUser();
    }
}
