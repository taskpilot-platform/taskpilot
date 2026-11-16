package com.taskpilot.infrastructure.config.security;

import com.taskpilot.contracts.user.dto.AccountAuthStateDto;
import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.contracts.user.dto.RealtimeAccessContext;
import com.taskpilot.contracts.user.dto.SessionAuthStateDto;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.contracts.user.port.out.SessionAuthStatePort;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates incoming Bearer JWT v2 requests against authoritative database account
 * and session state, failing closed on inactive accounts, version mismatches, or revoked sessions.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String ACTIVE_LIFECYCLE = "ACTIVE";

    private final JwtService jwtService;
    private final AccountAuthStatePort accountAuthStatePort;
    private final SessionAuthStatePort sessionAuthStatePort;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        final String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                final String jwt = authHeader.substring(7);
                authenticateBearerToken(jwt, request);
            }
        } catch (Exception e) {
            SecurityContextHolder.clearContext();
        }
        filterChain.doFilter(request, response);
    }

    private void authenticateBearerToken(String jwt, HttpServletRequest request) {
        Optional<JwtService.JwtClaims> maybeClaims = jwtService.parseAndValidate(jwt);
        if (maybeClaims.isEmpty()) {
            return;
        }

        JwtService.JwtClaims claims = maybeClaims.get();
        Optional<AccountAuthStateDto> maybeAccount = accountAuthStatePort.findById(claims.userId());
        if (maybeAccount.isEmpty()) {
            return;
        }
        AccountAuthStateDto account = maybeAccount.get();
        if (!ACTIVE_LIFECYCLE.equals(account.lifecycle())
                || !account.emailVerified()
                || account.tokenVersion() != claims.tokenVersion()
                || account.role() == null
                || account.role().isBlank()) {
            return;
        }

        Optional<SessionAuthStateDto> maybeSession = sessionAuthStatePort.findById(claims.sessionId());
        if (maybeSession.isEmpty()) {
            return;
        }
        SessionAuthStateDto session = maybeSession.get();
        if (session.revoked()
                || session.userId() != account.userId()
                || session.tokenVersion() != claims.tokenVersion()
                || session.expiresAt() == null
                || !session.expiresAt().isAfter(Instant.now())) {
            return;
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

        String roleAuthority = account.role().startsWith("ROLE_")
                ? account.role()
                : "ROLE_" + account.role();
        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority(roleAuthority))
        );
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }
}
