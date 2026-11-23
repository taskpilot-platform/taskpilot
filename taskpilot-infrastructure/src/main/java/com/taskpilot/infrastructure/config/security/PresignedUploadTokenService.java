package com.taskpilot.infrastructure.config.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Cryptographically binds a server-generated Backblaze B2 object key, content-type,
 * projectId, meetingId, and uploader userId into a short-lived signed token.
 * Prevents clients from submitting arbitrary bucket keys during complete-upload.
 */
@Service
public class PresignedUploadTokenService {

    private static final String TOKEN_TYPE = "B2_RECORDING_UPLOAD";
    private static final Duration MAX_TTL = Duration.ofMinutes(30);

    public record UploadTokenClaims(
            Long projectId,
            Long meetingId,
            Long userId,
            String objectKey,
            String contentType,
            String fileName,
            Instant expiresAt
    ) {}

    private final String secretKey;

    public PresignedUploadTokenService(@Value("${application.security.jwt.secret-key}") String secretKey) {
        this.secretKey = secretKey;
    }

    public String issueToken(
            Long projectId,
            Long meetingId,
            Long userId,
            String objectKey,
            String contentType,
            String fileName,
            Duration ttl) {
        Duration effectiveTtl = (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_TTL) > 0)
                ? Duration.ofMinutes(20)
                : ttl;
        Instant now = Instant.now();
        Instant expiresAt = now.plus(effectiveTtl);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("type", TOKEN_TYPE)
                .claim("projectId", projectId)
                .claim("meetingId", meetingId)
                .claim("objectKey", objectKey)
                .claim("contentType", contentType)
                .claim("fileName", fileName)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(getSigningKey())
                .compact();
    }

    public Optional<UploadTokenClaims> verifyToken(
            String token,
            Long expectedProjectId,
            Long expectedMeetingId,
            Long expectedUserId) {
        if (token == null || token.isBlank()
                || expectedProjectId == null
                || expectedMeetingId == null
                || expectedUserId == null) {
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
            Number meetingIdNum = claims.get("meetingId", Number.class);
            String subject = claims.getSubject();
            String objectKey = claims.get("objectKey", String.class);
            String contentType = claims.get("contentType", String.class);
            String fileName = claims.get("fileName", String.class);
            Date expiration = claims.getExpiration();

            if (projectIdNum == null || meetingIdNum == null || subject == null
                    || objectKey == null || objectKey.isBlank()
                    || contentType == null || contentType.isBlank()
                    || expiration == null || expiration.before(new Date())) {
                return Optional.empty();
            }

            long tokenUserId = Long.parseLong(subject);
            if (projectIdNum.longValue() != expectedProjectId
                    || meetingIdNum.longValue() != expectedMeetingId
                    || tokenUserId != expectedUserId) {
                return Optional.empty();
            }

            String expectedPrefix = "recordings/" + expectedProjectId + "/" + expectedMeetingId + "/";
            if (!objectKey.startsWith(expectedPrefix) || objectKey.contains("..")) {
                return Optional.empty();
            }

            return Optional.of(new UploadTokenClaims(
                    projectIdNum.longValue(),
                    meetingIdNum.longValue(),
                    tokenUserId,
                    objectKey,
                    contentType,
                    fileName != null ? fileName : "recording.webm",
                    expiration.toInstant()
            ));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
    }
}
