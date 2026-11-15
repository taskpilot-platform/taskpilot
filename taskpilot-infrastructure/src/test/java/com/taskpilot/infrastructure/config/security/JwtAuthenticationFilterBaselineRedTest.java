package com.taskpilot.infrastructure.config.security;

import com.taskpilot.contracts.user.dto.AccountAuthStateDto;
import com.taskpilot.contracts.user.dto.SessionAuthStateDto;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.contracts.user.port.out.SessionAuthStatePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Verifies P1.2 JwtAuthenticationFilter DB-backed principal, lifecycle, session, version, and role checks.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterBaselineRedTest {

    private static final String TEST_SECRET =
            "taskpilot-p12-jwt-signing-hmac-key-0123456789abcdef0123456789abcdef";

    @Mock
    private ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    @Mock
    private AccountAuthStatePort accountAuthStatePort;

    @Mock
    private SessionAuthStatePort sessionAuthStatePort;

    private JwtService jwtService;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecretKey(TEST_SECRET);
        properties.setExpiration(900_000L);
        jwtService = new JwtService(properties, new JwtTokenBlocklistService(redisTemplateProvider));
        filter = new JwtAuthenticationFilter(jwtService, accountAuthStatePort, sessionAuthStatePort);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilter_withValidBearerToken_mustAuthenticateTypedUidPrincipalAndUseAuthoritativeDbRole() throws Exception {
        UUID sid = UUID.randomUUID();
        // Deliberately mint stale ADMIN claim inside token while DB currently holds USER role.
        String token = jwtService.generateToken(42L, 3L, sid, "ADMIN");

        when(accountAuthStatePort.findById(42L)).thenReturn(Optional.of(
                new AccountAuthStateDto(42L, "current@taskpilot.io", "USER", "ACTIVE", true, 3L)));
        when(sessionAuthStatePort.findById(sid)).thenReturn(Optional.of(
                new SessionAuthStateDto(sid, 42L, 3L, Instant.now().plusSeconds(3600), false)));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName())
                .as("Authentication.getName() must be immutable numeric uid, not mutable email")
                .isEqualTo("42")
                .matches("^\\d+$")
                .isNotEqualTo("current@taskpilot.io");
        assertThat(auth.getPrincipal()).isInstanceOf(TaskPilotPrincipal.class);
        TaskPilotPrincipal principal = (TaskPilotPrincipal) auth.getPrincipal();
        assertThat(principal.currentEmail()).isEqualTo("current@taskpilot.io");
        assertThat(principal.role()).isEqualTo("USER");
        assertThat(auth.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    void doFilter_mustFailClosedWhenAccountInactiveOrVersionMismatchOrSessionRevoked() throws Exception {
        UUID sid = UUID.randomUUID();
        String token = jwtService.generateToken(42L, 3L, sid, "USER");

        // Inactive lifecycle
        when(accountAuthStatePort.findById(42L)).thenReturn(Optional.of(
                new AccountAuthStateDto(42L, "alice@taskpilot.io", "USER", "ADMIN_SUSPENDED", true, 3L)));
        MockHttpServletRequest suspendedReq = new MockHttpServletRequest("GET", "/api/v1/projects");
        suspendedReq.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        filter.doFilter(suspendedReq, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        // Version mismatch
        when(accountAuthStatePort.findById(42L)).thenReturn(Optional.of(
                new AccountAuthStateDto(42L, "alice@taskpilot.io", "USER", "ACTIVE", true, 4L)));
        MockHttpServletRequest staleVersionReq = new MockHttpServletRequest("GET", "/api/v1/projects");
        staleVersionReq.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        filter.doFilter(staleVersionReq, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        // Revoked session
        when(accountAuthStatePort.findById(42L)).thenReturn(Optional.of(
                new AccountAuthStateDto(42L, "alice@taskpilot.io", "USER", "ACTIVE", true, 3L)));
        when(sessionAuthStatePort.findById(sid)).thenReturn(Optional.of(
                new SessionAuthStateDto(sid, 42L, 3L, Instant.now().plusSeconds(3600), true)));
        MockHttpServletRequest revokedSessionReq = new MockHttpServletRequest("GET", "/api/v1/projects");
        revokedSessionReq.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        filter.doFilter(revokedSessionReq, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
