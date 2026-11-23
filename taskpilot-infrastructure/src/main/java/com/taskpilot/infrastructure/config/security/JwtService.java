package com.taskpilot.infrastructure.config.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pure JWT v2 signing and single-parse validation codec.
 * Enforces numeric {@code uid} subject, {@code token_schema=2}, {@code ver}, {@code sid}, {@code jti},
 * configured {@code iss}/{@code aud}, expiration, and {@code jti} revocation blocklist.
 */
@Service
public class JwtService {

    public static final int TOKEN_SCHEMA_V2 = 2;

    private final JwtProperties jwtProperties;
    private final JwtTokenBlocklistService tokenBlocklistService;
    private final SecretKey signingKey;

    public record JwtClaims(
            long userId,
            long tokenVersion,
            UUID sessionId,
            String tokenId,
            String role,
            Instant issuedAt,
            Instant expiresAt,
            int tokenSchema
    ) {
    }

    public JwtService(JwtProperties jwtProperties, JwtTokenBlocklistService tokenBlocklistService) {
        this.jwtProperties = Objects.requireNonNull(jwtProperties, "jwtProperties must not be null");
        this.tokenBlocklistService = Objects.requireNonNull(tokenBlocklistService, "tokenBlocklistService must not be null");
        jwtProperties.validate();
        this.signingKey = Keys.hmacShaKeyFor(
                jwtProperties.getNormalizedSecretKey().getBytes(StandardCharsets.UTF_8));
    }

    public long getJwtExpiration() {
        return jwtProperties.getExpiration();
    }

    public String generateToken(long userId, long tokenVersion, UUID sessionId, String role) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (tokenVersion < 0) {
            throw new IllegalArgumentException("tokenVersion must be non-negative");
        }
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plusMillis(jwtProperties.getExpiration());
        String jti = UUID.randomUUID().toString();

        return Jwts.builder()
                .issuer(jwtProperties.getNormalizedIssuer())
                .audience().add(jwtProperties.getNormalizedAudience()).and()
                .subject(Long.toString(userId))
                .id(jti)
                .claim("token_schema", TOKEN_SCHEMA_V2)
                .claim("ver", tokenVersion)
                .claim("sid", sessionId.toString())
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }

    public Optional<JwtClaims> parseAndValidate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(jwtProperties.getNormalizedIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Set<String> audience = claims.getAudience();
            if (audience == null || !audience.contains(jwtProperties.getNormalizedAudience())) {
                return Optional.empty();
            }

            Number schemaNum = claims.get("token_schema", Number.class);
            if (schemaNum == null || schemaNum.intValue() != TOKEN_SCHEMA_V2) {
                return Optional.empty();
            }

            long userId = parsePositiveUserId(claims.getSubject());
            if (userId <= 0) {
                return Optional.empty();
            }

            Number versionNum = claims.get("ver", Number.class);
            if (versionNum == null || versionNum.longValue() < 0) {
                return Optional.empty();
            }

            String sidRaw = claims.get("sid", String.class);
            if (sidRaw == null || sidRaw.isBlank()) {
                return Optional.empty();
            }
            UUID sessionId = UUID.fromString(sidRaw.trim());

            String tokenId = claims.getId();
            if (tokenId == null || tokenId.isBlank()) {
                return Optional.empty();
            }

            Date issuedAt = claims.getIssuedAt();
            Date expiration = claims.getExpiration();
            if (issuedAt == null || expiration == null) {
                return Optional.empty();
            }
            Instant expInstant = expiration.toInstant();
            if (!expInstant.isAfter(Instant.now())) {
                return Optional.empty();
            }

            if (tokenBlocklistService.isJtiRevoked(tokenId)) {
                return Optional.empty();
            }

            String role = claims.get("role", String.class);
            return Optional.of(new JwtClaims(
                    userId,
                    versionNum.longValue(),
                    sessionId,
                    tokenId,
                    role,
                    issuedAt.toInstant(),
                    expInstant,
                    TOKEN_SCHEMA_V2
            ));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public boolean isTokenValid(String token) {
        return parseAndValidate(token).isPresent();
    }

    public void revokeToken(String token) {
        parseAndValidate(token).ifPresent(claims ->
                tokenBlocklistService.revokeJti(claims.tokenId(), claims.expiresAt()));
    }

    private static long parsePositiveUserId(String subject) {
        if (subject == null || subject.isBlank()) {
            return -1L;
        }
        String trimmed = subject.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                return -1L;
            }
        }
        long parsed = Long.parseLong(trimmed);
        return parsed > 0 ? parsed : -1L;
    }
}
