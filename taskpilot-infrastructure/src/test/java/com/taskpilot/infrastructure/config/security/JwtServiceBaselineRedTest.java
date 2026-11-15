package com.taskpilot.infrastructure.config.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Baseline RED test for P1.2 JWT v2 claims & validation (target module: taskpilot-infrastructure).
 * Reconciled with P1.1 JwtProperties constructor injection.
 */
@ExtendWith(MockitoExtension.class)
class JwtServiceBaselineRedTest {

    private static final String TEST_SECRET =
            "taskpilot-p12-jwt-signing-hmac-key-0123456789abcdef0123456789abcdef";
    private static final long DEFAULT_15_MIN_MILLIS = 900_000L;

    @Mock
    private ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    private JwtService jwtService;
    private SecretKey signingKey;

    @BeforeEach
    void setUp() {
        JwtTokenBlocklistService blocklistService = new JwtTokenBlocklistService(redisTemplateProvider);
        JwtProperties properties = new JwtProperties();
        properties.setSecretKey(TEST_SECRET);
        properties.setExpiration(DEFAULT_15_MIN_MILLIS);
        jwtService = new JwtService(properties, blocklistService);
        signingKey = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void generatedAccessToken_mustCarryNumericSubjectAndJwtV2AuthorityClaims() {
        UUID sessionId = UUID.randomUUID();
        String token = jwtService.generateToken(42L, 1L, sessionId, "USER");

        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(claims.getSubject())
                    .as("sub must be immutable numeric uid, not mutable email")
                    .isEqualTo("42")
                    .matches("^\\d+$")
                    .isNotEqualTo("alice@taskpilot.io");
            softly.assertThat(claims.get("token_schema", Integer.class))
                    .as("token_schema claim must equal 2")
                    .isEqualTo(2);
            softly.assertThat(claims.get("ver", Number.class))
                    .as("ver claim must exist and be >= 1")
                    .satisfies(ver -> {
                        assertThat(ver).isNotNull();
                        assertThat(ver.longValue()).isGreaterThanOrEqualTo(1L);
                    });
            softly.assertThat(claims.get("sid", String.class))
                    .as("sid claim must be a valid session UUID")
                    .isEqualTo(sessionId.toString());
            softly.assertThat(claims.getId())
                    .as("jti claim must be a valid UUID")
                    .satisfies(jti -> {
                        assertThat(jti).isNotNull();
                        UUID.fromString(jti);
                    });
            softly.assertThat(claims.getIssuer())
                    .as("iss claim must match configured issuer 'taskpilot'")
                    .isEqualTo("taskpilot");
            softly.assertThat(claims.getAudience())
                    .as("aud claim must contain configured audience 'taskpilot-api'")
                    .containsExactly("taskpilot-api");
        });
    }

    @Test
    void isTokenValid_mustRejectLegacyEmailSubjectTokenWithoutV2ClaimsAndIssuerAudience() {
        Instant now = Instant.now();
        String legacyToken = Jwts.builder()
                .subject("alice@taskpilot.io")
                .claim("role", "USER")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(600)))
                .signWith(signingKey)
                .compact();

        assertThat(jwtService.isTokenValid(legacyToken))
                .as("Legacy token lacking token_schema=2, numeric uid, iss, aud, sid, ver, jti must fail validation")
                .isFalse();
    }
}
