package com.taskpilot.users.auth.service;

import com.taskpilot.contracts.user.dto.SecurityChangeReason;
import com.taskpilot.contracts.user.dto.SessionRevocationReason;
import com.taskpilot.contracts.user.dto.UserSecurityChangedEvent;
import com.taskpilot.contracts.user.dto.UserSessionRevokedEvent;
import com.taskpilot.infrastructure.config.security.JwtService;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.auth.dto.AuthResponse;
import com.taskpilot.users.common.entity.RefreshTokenEntity;
import com.taskpilot.users.common.entity.RefreshTokenHistoryEntity;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.repository.PasswordResetTokenRepository;
import com.taskpilot.users.common.repository.RefreshTokenHistoryRepository;
import com.taskpilot.users.common.repository.RefreshTokenRepository;
import com.taskpilot.users.common.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Authoritative durable auth session lifecycle manager.
 * Enforces canonical {@code users -> refresh_tokens} lock ordering, single active session per user,
 * SHA-256 refresh hashing at rest, bounded consumed-token replay detection, {@code sid} logout,
 * and atomic {@code token_version} epoch increments.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserSessionService {

    private static final int REFRESH_TOKEN_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Value("${application.security.jwt.refresh-token.expiration:604800000}")
    private long refreshTokenDurationMs;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenHistoryRepository refreshTokenHistoryRepository;
    private final JwtService jwtService;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Transactional
    public AuthResponse issueSession(long userId) {
        UserEntity user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "User not found"));

        if (!user.isActiveAccount() || !Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Account is not active or email is unverified");
        }

        Instant now = Instant.now();
        List<RefreshTokenEntity> priorActive = refreshTokenRepository.findActiveByUserIdForUpdate(user.getId());
        for (RefreshTokenEntity active : priorActive) {
            revokeSessionEntity(active, now, SessionRevocationReason.REPLACED_BY_LOGIN);
        }
        if (!priorActive.isEmpty()) {
            refreshTokenRepository.flush();
        }

        UUID sessionId = UUID.randomUUID();
        String rawRefreshToken = generateOpaqueRefreshToken();
        long epoch = user.effectiveTokenVersion();

        RefreshTokenEntity created = RefreshTokenEntity.builder()
                .user(user)
                .sessionId(sessionId)
                .tokenHash(hashToken(rawRefreshToken))
                .tokenVersion(epoch)
                .expiryDate(now.plusMillis(refreshTokenDurationMs))
                .lastUsedAt(now)
                .build();
        refreshTokenRepository.saveAndFlush(created);

        String accessToken = jwtService.generateToken(
                user.getId(),
                epoch,
                sessionId,
                user.getRole().name()
        );
        return new AuthResponse(accessToken, rawRefreshToken, "Bearer", jwtService.getJwtExpiration());
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse rotateRefreshToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw unauthorized("Invalid or revoked refresh token");
        }

        String presentedHash = hashToken(rawRefreshToken.trim());
        Instant now = Instant.now();

        // Discover owner first so all writes acquire locks in canonical order: users -> refresh_tokens.
        Optional<Long> maybeUserId = refreshTokenRepository.findUserIdByTokenHash(presentedHash);
        if (maybeUserId.isEmpty()) {
            handleUnknownOrReplayedToken(presentedHash, now);
            throw unauthorized("Invalid or revoked refresh token");
        }

        UserEntity user = userRepository.findByIdForUpdate(maybeUserId.get())
                .orElseThrow(() -> unauthorized("Invalid or revoked refresh token"));

        Optional<RefreshTokenEntity> maybeSession = refreshTokenRepository.findByTokenHashForUpdate(presentedHash);
        if (maybeSession.isEmpty()) {
            handleUnknownOrReplayedToken(presentedHash, now);
            throw unauthorized("Invalid or revoked refresh token");
        }

        RefreshTokenEntity session = maybeSession.get();
        if (session.isRevoked()) {
            throw unauthorized("Refresh session has been revoked");
        }
        if (session.isExpired()) {
            revokeSessionEntity(session, now, SessionRevocationReason.LOGOUT);
            throw unauthorized("Refresh token was expired. Please make a new signin request");
        }
        if (!user.isActiveAccount() || !Boolean.TRUE.equals(user.getEmailVerified())) {
            revokeSessionEntity(session, now, SessionRevocationReason.SECURITY_VERSION_INCREMENT);
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Account is not active or email is unverified");
        }
        long currentEpoch = user.effectiveTokenVersion();
        long sessionEpoch = session.getTokenVersion() != null ? session.getTokenVersion() : 0L;
        if (sessionEpoch != currentEpoch) {
            revokeSessionEntity(session, now, SessionRevocationReason.SECURITY_VERSION_INCREMENT);
            throw unauthorized("Session security version is no longer valid");
        }

        // Prune already-expired replay history entries to keep table size bounded to original TTL.
        refreshTokenHistoryRepository.deleteByExpiresAtBefore(now);

        RefreshTokenHistoryEntity historyEntry = RefreshTokenHistoryEntity.builder()
                .sessionId(session.getSessionId())
                .userId(user.getId())
                .tokenHash(presentedHash)
                .consumedAt(now)
                .expiresAt(session.getExpiryDate())
                .build();
        refreshTokenHistoryRepository.saveAndFlush(historyEntry);

        String rotatedRawToken = generateOpaqueRefreshToken();
        session.setTokenHash(hashToken(rotatedRawToken));
        session.setLastUsedAt(now);
        refreshTokenRepository.saveAndFlush(session);

        String accessToken = jwtService.generateToken(
                user.getId(),
                currentEpoch,
                session.getSessionId(),
                user.getRole().name()
        );
        return new AuthResponse(accessToken, rotatedRawToken, "Bearer", jwtService.getJwtExpiration());
    }

    @Transactional
    public void logoutSession(String rawRefreshToken, String authorizationHeader) {
        String accessToken = extractBearerToken(authorizationHeader);
        JwtService.JwtClaims accessClaims = accessToken != null
                ? jwtService.parseAndValidate(accessToken).orElse(null)
                : null;

        if (accessToken != null) {
            jwtService.revokeToken(accessToken);
        }

        String presentedHash = (rawRefreshToken != null && !rawRefreshToken.isBlank())
                ? hashToken(rawRefreshToken.trim())
                : null;

        Optional<Long> maybeOwnerId = presentedHash != null
                ? refreshTokenRepository.findUserIdByTokenHash(presentedHash)
                : Optional.empty();
        if (maybeOwnerId.isEmpty() && accessClaims != null) {
            maybeOwnerId = Optional.of(accessClaims.userId());
        }
        if (maybeOwnerId.isEmpty()) {
            log.debug("Logout called without resolvable active session");
            return;
        }

        userRepository.findByIdForUpdate(maybeOwnerId.get());

        RefreshTokenEntity session = null;
        if (presentedHash != null) {
            session = refreshTokenRepository.findByTokenHashForUpdate(presentedHash).orElse(null);
        }
        if (session == null && accessClaims != null) {
            session = refreshTokenRepository.findBySessionIdForUpdate(accessClaims.sessionId()).orElse(null);
        }
        if (session == null) {
            return;
        }

        if (accessClaims != null && (accessClaims.userId() != session.getUser().getId()
                || !accessClaims.sessionId().equals(session.getSessionId()))) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Access token does not match refresh session");
        }

        if (!session.isRevoked()) {
            revokeSessionEntity(session, Instant.now(), SessionRevocationReason.LOGOUT);
        }
    }

    @Transactional
    public long invalidateAll(long userId, SecurityChangeReason reason) {
        UserEntity user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));

        long nextVersion = user.incrementTokenVersion();
        userRepository.saveAndFlush(user);

        Instant now = Instant.now();
        List<RefreshTokenEntity> activeSessions = refreshTokenRepository.findActiveByUserIdForUpdate(userId);
        for (RefreshTokenEntity active : activeSessions) {
            active.setRevokedAt(now);
            active.setRevocationReason(SessionRevocationReason.SECURITY_VERSION_INCREMENT.name());
            refreshTokenRepository.save(active);
        }
        if (!activeSessions.isEmpty()) {
            refreshTokenRepository.flush();
        }
        if (passwordResetTokenRepository != null) {
            passwordResetTokenRepository.invalidateAllActiveByUserId(userId, now);
        }

        publishAfterCommit(new UserSecurityChangedEvent(
                UUID.randomUUID(),
                userId,
                nextVersion,
                now,
                reason
        ));
        return nextVersion;
    }

    private void handleUnknownOrReplayedToken(String presentedHash, Instant now) {
        Optional<RefreshTokenHistoryEntity> historyOpt = refreshTokenHistoryRepository.findByTokenHash(presentedHash);
        if (historyOpt.isEmpty()) {
            return;
        }
        RefreshTokenHistoryEntity history = historyOpt.get();
        if (history.getExpiresAt() != null && !history.getExpiresAt().isAfter(now)) {
            return;
        }

        userRepository.findByIdForUpdate(history.getUserId());
        refreshTokenRepository.findBySessionIdForUpdate(history.getSessionId()).ifPresent(session -> {
            if (!session.isRevoked()) {
                revokeSessionEntity(session, now, SessionRevocationReason.REPLAY_DETECTED);
            }
        });
        log.warn("Detected consumed refresh token replay for userId={}, sessionId={}",
                history.getUserId(), history.getSessionId());
    }

    private void revokeSessionEntity(
            RefreshTokenEntity session,
            Instant now,
            SessionRevocationReason reason
    ) {
        session.setRevokedAt(now);
        session.setRevocationReason(reason.name());
        refreshTokenRepository.saveAndFlush(session);

        long sessionEpoch = session.getTokenVersion() != null ? session.getTokenVersion() : 0L;
        publishAfterCommit(new UserSessionRevokedEvent(
                UUID.randomUUID(),
                session.getUser().getId(),
                session.getSessionId(),
                sessionEpoch,
                now,
                reason
        ));
    }

    private void publishAfterCommit(Object event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publishEvent(event);
                }
            });
        } else {
            eventPublisher.publishEvent(event);
        }
    }

    public static String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash refresh token", e);
        }
    }

    private static String generateOpaqueRefreshToken() {
        byte[] randomBytes = new byte[REFRESH_TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private static String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        return authorizationHeader.substring(7);
    }

    private static BusinessException unauthorized(String message) {
        return new BusinessException(HttpStatus.UNAUTHORIZED.value(), message);
    }
}
