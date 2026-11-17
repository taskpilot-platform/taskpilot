package com.taskpilot.infrastructure.config.security;

import com.taskpilot.infrastructure.config.security.oauth2.OAuth2Properties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorsConfigurationTest {

    @Test
    void hostileOriginIsRejectedEvenWhenFeOriginIsConfigured() {
        CorsOriginProperties corsProps = new CorsOriginProperties("https://app.taskpilot.test", "http://localhost:5173");
        SecurityConfig securityConfig = new SecurityConfig(corsProps, Mockito.mock(JwtAuthenticationFilter.class));

        CorsConfiguration cors = securityConfig.corsConfigurationSource()
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/projects"));

        assertThat(cors).isNotNull();
        assertThat(cors.checkOrigin("https://evil.attacker.invalid")).isNull();
        assertThat(cors.checkOrigin("https://app.taskpilot.test")).isEqualTo("https://app.taskpilot.test");
    }

    @Test
    void wildcardOriginPatternWithCredentialsIsNeverConfigured() {
        CorsOriginProperties corsProps = new CorsOriginProperties("", "http://localhost:5173,http://127.0.0.1:5173");
        SecurityConfig securityConfig = new SecurityConfig(corsProps, Mockito.mock(JwtAuthenticationFilter.class));

        CorsConfiguration cors = securityConfig.corsConfigurationSource()
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/projects"));

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedOriginPatterns()).isNullOrEmpty();
        assertThat(cors.getAllowCredentials()).isNotEqualTo(Boolean.TRUE);
        assertThat(cors.getAllowedOrigins()).containsExactly("http://localhost:5173", "http://127.0.0.1:5173");
    }

    @Test
    void wildcardOrMalformedConfiguredOriginsFailStartup() {
        assertThatThrownBy(() -> new CorsOriginProperties("", "http://localhost:*"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Wildcard");
        assertThatThrownBy(() -> new CorsOriginProperties("", "*"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Wildcard");
        assertThatThrownBy(() -> new CorsOriginProperties("", "https://app.example/path"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("path");
    }

    @Test
    void oauth2RedirectOriginsAreValidatedIndependentlyFromCors() {
        OAuth2Properties oauth2 = new OAuth2Properties();
        oauth2.setAllowedRedirectOrigins("https://app.taskpilot.test");
        oauth2.setSuccessRedirectUri("https://evil.attacker.invalid/auth/callback");

        assertThatThrownBy(oauth2::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-redirect-origins");

        oauth2.setSuccessRedirectUri("https://app.taskpilot.test/auth/callback");
        oauth2.validate();
        assertThat(oauth2.getAllowedRedirectOriginList()).containsExactly("https://app.taskpilot.test");
    }

    @Test
    void idempotencyKeyHeaderIsAllowedInCorsPreflight() {
        CorsOriginProperties corsProps = new CorsOriginProperties("", "http://127.0.0.1:4173");
        SecurityConfig securityConfig = new SecurityConfig(corsProps, Mockito.mock(JwtAuthenticationFilter.class));

        CorsConfiguration cors = securityConfig.corsConfigurationSource()
                .getCorsConfiguration(new MockHttpServletRequest("OPTIONS", "/api/v1/tasks/1/workflow/submit"));

        assertThat(cors).isNotNull();
        assertThat(cors.checkHeaders(java.util.List.of("Authorization", "Content-Type", "Idempotency-Key")))
                .containsExactly("Authorization", "Content-Type", "Idempotency-Key");
    }
}
