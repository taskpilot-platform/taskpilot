package com.taskpilot.infrastructure.config.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Issues and verifies short-lived HMAC-signed stream tokens scoped to a specific
 * (projectId, fileId, userEmail) so browser &lt;video&gt; tags can perform native
 * HTTP Range requests against private backend-proxied storage without public sharing.
 * Uses a domain-separated derived HMAC key so stream tokens can NEVER be accepted by JwtService.
 */
@Service
public class FileStreamTokenService {

    private static final String TOKEN_TYPE = "FILE_STREAM";
    private static final String KEY_DOMAIN = ":taskpilot-file-stream-v1";
    private static final Duration DEFAULT_TTL = Duration.ofHours(2);

    public record StreamTokenClaims(String userEmail, Long projectId, Long fileId, Instant expiresAt) {}

    public record IssuedStreamToken(String token, Instant expiresAt) {}

    private final SecretKey signingKey;

    public FileStreamTokenService(@Value("${application.security.jwt.secret-key}") String secretKey) {
        this.signingKey = deriveDomainKey(secretKey, KEY_DOMAIN);
    }

    public IssuedStreamToken issueToken(String userEmail, Long projectId, Long fileId) {
        return issueToken(userEmail, projectId, fileId, DEFAULT_TTL);
    }

    public IssuedStreamToken issueToken(String userEmail, Long projectId, Long fileId, Duration ttl) {
        Duration effectiveTtl = (ttl != null && !ttl.isZero() && !ttl.isNegative()) ? ttl : DEFAULT_TTL;
        Instant now = Instant.now();
        Instant expiresAt = now.plus(effectiveTtl);

        String token = Jwts.builder()
                .subject(userEmail)
                .claim("type", TOKEN_TYPE)
                .claim("projectId", projectId)
                .claim("fileId", fileId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(getSigningKey())
                .compact();

        return new IssuedStreamToken(token, expiresAt);
    }

    public Optional<StreamTokenClaims> verifyToken(String token, Long expectedProjectId, Long expectedFileId) {
        if (token == null || token.isBlank() || expectedProjectId == null || expectedFileId == null) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            if (!TOKEN_TYPE.equals(claims.get("type", String.class))) {
                return Optional.empty();
            }

            Number projectIdNum = claims.get("projectId", Number.class);
            Number fileIdNum = claims.get("fileId", Number.class);
            String subject = claims.getSubject();
            Date expiration = claims.getExpiration();

            if (subject == null || subject.isBlank() || projectIdNum == null || fileIdNum == null || expiration == null) {
                return Optional.empty();
            }
            if (expiration.before(new Date())) {
                return Optional.empty();
            }
            if (projectIdNum.longValue() != expectedProjectId || fileIdNum.longValue() != expectedFileId) {
                return Optional.empty();
            }

            return Optional.of(new StreamTokenClaims(
                    subject,
                    projectIdNum.longValue(),
                    fileIdNum.longValue(),
                    expiration.toInstant()
            ));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private SecretKey getSigningKey() {
        return signingKey;
    }

    private static SecretKey deriveDomainKey(String masterSecret, String domain) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] derived = sha256.digest(((masterSecret != null ? masterSecret : "") + domain)
                    .getBytes(StandardCharsets.UTF_8));
            return Keys.hmacShaKeyFor(derived);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
