package com.taskpilot.infrastructure.config.security;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPropertiesTest {

    private static final String VALID_PROD_KEY = "prod-signing-key-with-plenty-of-entropy-1234567890abcdef";
    private static final String DEV_MARKER_KEY = "taskpilot-dev-only-local-loopback-jwt-signing-key-2026-01";
    private static final String TEST_MARKER_KEY = "taskpilot-test-only-migration-jwt-signing-key-2026-32bytes";

    @Test
    void rejectsMissingBlankSampleShortAndLowEntropyKeys() {
        for (String bad : new String[] {
                null,
                "",
                "   ",
                JwtProperties.KNOWN_PUBLIC_SAMPLE_KEY,
                JwtProperties.KNOWN_PUBLIC_SAMPLE_KEY.toLowerCase(),
                "  " + JwtProperties.KNOWN_PUBLIC_SAMPLE_KEY + "  ",
                "too-short-key",
                "   too-short-key" + " ".repeat(40),
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        }) {
            JwtProperties props = new JwtProperties();
            props.setSecretKey(bad);
            assertThatThrownBy(() -> props.validateForProfiles("dev"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rejectsDevAndTestKeysInProdOrCommonProfilesWhileAllowingInDevAndTest() {
        JwtProperties devProps = new JwtProperties();
        devProps.setSecretKey(DEV_MARKER_KEY);
        devProps.validateForProfiles("dev");

        assertThatThrownBy(() -> devProps.validateForProfiles("prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(DEV_MARKER_KEY);
        assertThatThrownBy(() -> devProps.validateForProfiles())
                .isInstanceOf(IllegalStateException.class);

        JwtProperties testProps = new JwtProperties();
        testProps.setSecretKey(TEST_MARKER_KEY);
        testProps.validateForProfiles("test");
        assertThatThrownBy(() -> testProps.validateForProfiles("prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(TEST_MARKER_KEY);
    }

    @Test
    void defaultExpirationIsFifteenMinutesAndRejectsOutOfBoundsTtl() {
        JwtProperties props = new JwtProperties();
        props.setSecretKey(VALID_PROD_KEY);
        assertThat(props.getExpiration()).isEqualTo(900_000L);
        props.validateForProfiles("prod");

        props.setExpiration(86_400_000L);
        assertThatThrownBy(() -> props.validateForProfiles("prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT expiration");

        props.setExpiration(0L);
        assertThatThrownBy(() -> props.validateForProfiles("prod"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void jwtServiceUsesValidatedKeyAndFifteenMinuteExpiration() {
        JwtProperties props = new JwtProperties();
        props.setSecretKey(VALID_PROD_KEY);
        JwtService jwtService = new JwtService(props, Mockito.mock(JwtTokenBlocklistService.class));

        assertThat(props.getNormalizedIssuer()).isEqualTo("taskpilot");
        assertThat(props.getNormalizedAudience()).isEqualTo("taskpilot-api");
        assertThat(jwtService.getJwtExpiration()).isEqualTo(900_000L);
        java.util.UUID sessionId = java.util.UUID.randomUUID();
        String token = jwtService.generateToken(42L, 1L, sessionId, "MEMBER");
        assertThat(jwtService.isTokenValid(token)).isTrue();
        var claims = jwtService.parseAndValidate(token).orElseThrow();
        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.tokenVersion()).isEqualTo(1L);
        assertThat(claims.sessionId()).isEqualTo(sessionId);

        JwtProperties paddedProps = new JwtProperties();
        paddedProps.setSecretKey("  " + VALID_PROD_KEY + "  ");
        JwtService paddedService = new JwtService(paddedProps, Mockito.mock(JwtTokenBlocklistService.class));
        assertThat(paddedService.isTokenValid(token)).isTrue();
        assertThat(jwtService.isTokenValid(paddedService.generateToken(42L, 1L, sessionId, "MEMBER"))).isTrue();
    }
}
