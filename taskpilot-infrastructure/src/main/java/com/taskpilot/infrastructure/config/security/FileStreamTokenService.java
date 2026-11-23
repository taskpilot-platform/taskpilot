package com.taskpilot.infrastructure.config.security;

import com.taskpilot.contracts.user.dto.AccountAuthStateDto;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
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
import java.util.Objects;
import java.util.Optional;

/**
 * Issues and verifies short-lived stream tokens scoped to a project, file and immutable user ID.
 * Account security state is checked on issuance and every verification, so account revocation
 * takes effect before token expiry. A purpose-derived signing key separates these tokens from login JWTs.
 */
@Service
public class FileStreamTokenService {

    private static final String TOKEN_TYPE = "FILE_STREAM";
    private static final String KEY_DOMAIN = ":taskpilot-file-stream-v1";
    private static final Duration DEFAULT_TTL = Duration.ofHours(2);

    public record StreamTokenClaims(long userId, Long projectId, Long fileId, Instant expiresAt) {}

    public record IssuedStreamToken(String token, Instant expiresAt) {}

    private final SecretKey signingKey;
    private final AccountAuthStatePort accountAuthStatePort;

    public FileStreamTokenService(@Value("${application.security.jwt.secret-key}") String secretKey,
                                  AccountAuthStatePort accountAuthStatePort) {
        this.signingKey = deriveDomainKey(Objects.requireNonNull(secretKey, "secretKey must not be null"), KEY_DOMAIN);
        this.accountAuthStatePort = Objects.requireNonNull(accountAuthStatePort, "accountAuthStatePort must not be null");
    }

    public IssuedStreamToken issueToken(long userId, Long projectId, Long fileId) {
        return issueToken(userId, projectId, fileId, DEFAULT_TTL);
    }

    public IssuedStreamToken issueToken(long userId, Long projectId, Long fileId, Duration ttl) {
        if (userId <= 0 || projectId == null || projectId <= 0 || fileId == null || fileId <= 0) {
            throw new IllegalArgumentException("Stream token requires positive user, project and file IDs");
        }
        AccountAuthStateDto account = accountAuthStatePort.findById(userId)
                .filter(state -> isEligibleAccount(state, userId))
                .orElseThrow(() -> new IllegalArgumentException("Stream token requires an active verified account"));

        Duration effectiveTtl = (ttl != null && !ttl.isZero() && !ttl.isNegative()) ? ttl : DEFAULT_TTL;
        Instant now = Instant.now();
        Instant expiresAt = now.plus(effectiveTtl);

        String token = Jwts.builder()
                .subject(Long.toString(userId))
                .claim("type", TOKEN_TYPE)
                .claim("projectId", projectId)
                .claim("fileId", fileId)
                .claim("token_version", account.tokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();

        return new IssuedStreamToken(token, expiresAt);
    }

    public Optional<StreamTokenClaims> verifyToken(String token, Long expectedProjectId, Long expectedFileId) {
        if (token == null || token.isBlank() || expectedProjectId == null || expectedProjectId <= 0
                || expectedFileId == null || expectedFileId <= 0) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            if (!TOKEN_TYPE.equals(claims.get("type", String.class))) {
                return Optional.empty();
            }

            long userId = Long.parseLong(claims.getSubject());
            long projectId = integralClaim(claims, "projectId");
            long fileId = integralClaim(claims, "fileId");
            long tokenVersion = integralClaim(claims, "token_version");
            Date expiration = claims.getExpiration();

            if (userId <= 0 || tokenVersion < 0 || expiration == null
                    || !expiration.toInstant().isAfter(Instant.now())
                    || projectId != expectedProjectId || fileId != expectedFileId) {
                return Optional.empty();
            }

            Optional<AccountAuthStateDto> account = accountAuthStatePort.findById(userId);
            if (account.isEmpty() || !isEligibleAccount(account.get(), userId)
                    || account.get().tokenVersion() != tokenVersion) {
                return Optional.empty();
            }

            return Optional.of(new StreamTokenClaims(userId, projectId, fileId, expiration.toInstant()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static boolean isEligibleAccount(AccountAuthStateDto account, long userId) {
        return account.userId() == userId && "ACTIVE".equals(account.lifecycle())
                && account.emailVerified() && account.tokenVersion() >= 0;
    }

    private static long integralClaim(Claims claims, String name) {
        Number value = claims.get(name, Number.class);
        if (value == null) {
            throw new IllegalArgumentException("Missing stream token claim");
        }
        return Long.parseLong(value.toString());
    }

    private static SecretKey deriveDomainKey(String masterSecret, String domain) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] derived = sha256.digest((masterSecret + domain).getBytes(StandardCharsets.UTF_8));
            return Keys.hmacShaKeyFor(derived);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
