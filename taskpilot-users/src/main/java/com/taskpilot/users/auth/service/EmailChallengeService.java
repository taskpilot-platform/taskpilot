package com.taskpilot.users.auth.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.common.entity.PasswordResetTokenEntity;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.EmailTokenPurpose;
import com.taskpilot.users.common.repository.PasswordResetTokenRepository;
import com.taskpilot.users.common.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Shared single-use, hash-only, purpose/user/credential-version-bound challenge store.
 * Enforces canonical {@code users -> password_reset_tokens} lock ordering and atomic consumption.
 */
@Service
public class EmailChallengeService {

    public static final long DEFAULT_EMAIL_CHALLENGE_TTL_MS = Duration.ofMinutes(15).toMillis();
    public static final long SHORT_PROOF_TTL_MS = Duration.ofMinutes(5).toMillis();
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    @Autowired
    public EmailChallengeService(PasswordResetTokenRepository tokenRepository, UserRepository userRepository) {
        this(tokenRepository, userRepository, Clock.systemUTC());
    }

    public EmailChallengeService(
            PasswordResetTokenRepository tokenRepository,
            UserRepository userRepository,
            Clock clock) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public record IssuedChallenge(String rawToken, String tokenHash, Instant expiresAt) {}

    public record ConsumedChallenge(long userId, EmailTokenPurpose purpose, String targetProvider, String tokenHash) {}

    public record VerifiedIntentChallenge(long userId, String tokenHash, EmailTokenPurpose purpose, String targetProvider) {}

    @Transactional
    public IssuedChallenge issueForLockedUser(
            UserEntity lockedUser,
            EmailTokenPurpose purpose,
            long ttlMs,
            String targetProvider) {
        Instant now = clock.instant();
        tokenRepository.invalidateActiveByUserIdAndPurpose(lockedUser.getId(), purpose, now);

        String rawToken = generateToken();
        String tokenHash = sha256Hex(rawToken);
        Instant expiresAt = now.plusMillis(Math.max(60_000L, ttlMs));

        PasswordResetTokenEntity entity = PasswordResetTokenEntity.builder()
                .user(lockedUser)
                .token(null)
                .tokenHash(tokenHash)
                .purpose(purpose)
                .credentialVersion(lockedUser.effectiveTokenVersion())
                .targetProvider(targetProvider)
                .expiryDate(expiresAt)
                .used(false)
                .build();
        tokenRepository.saveAndFlush(entity);
        return new IssuedChallenge(rawToken, tokenHash, expiresAt);
    }

    @Transactional(readOnly = true)
    public VerifiedIntentChallenge verifyActiveIntent(
            String rawToken,
            EmailTokenPurpose expectedPurpose,
            String expectedProvider) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Missing verification proof token");
        }
        String tokenHash = sha256Hex(rawToken.trim());
        Long userId = tokenRepository.findUserIdByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid or expired proof token"));
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid or expired proof token"));
        PasswordResetTokenEntity entity = tokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid or expired proof token"));

        validateChallengeEntity(entity, user, expectedPurpose, expectedProvider);
        if (!user.isActiveAccount() || !Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Account must be active and verified");
        }
        return new VerifiedIntentChallenge(user.getId(), tokenHash, entity.getPurpose(), entity.getTargetProvider());
    }

    @Transactional
    public ConsumedChallenge consume(
            String rawToken,
            EmailTokenPurpose expectedPurpose,
            Long expectedUserId,
            String expectedProvider,
            Consumer<UserEntity> userValidatorAndMutator) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid or expired token");
        }
        String tokenHash = sha256Hex(rawToken.trim());
        Long userId = tokenRepository.findUserIdByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid or expired token"));

        if (expectedUserId != null && !expectedUserId.equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Token does not belong to current user");
        }

        // Canonical lock order: users -> password_reset_tokens
        UserEntity lockedUser = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid or expired token"));
        PasswordResetTokenEntity tokenEntity = tokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid or expired token"));

        validateChallengeEntity(tokenEntity, lockedUser, expectedPurpose, expectedProvider);

        Instant now = clock.instant();
        tokenEntity.setUsed(true);
        tokenEntity.setConsumedAt(now);
        tokenRepository.saveAndFlush(tokenEntity);

        if (userValidatorAndMutator != null) {
            userValidatorAndMutator.accept(lockedUser);
            userRepository.saveAndFlush(lockedUser);
        }

        return new ConsumedChallenge(
                lockedUser.getId(),
                tokenEntity.getPurpose(),
                tokenEntity.getTargetProvider(),
                tokenHash);
    }

    @Transactional
    public ConsumedChallenge consumeByHash(
            String tokenHash,
            EmailTokenPurpose expectedPurpose,
            Long expectedUserId,
            String expectedProvider,
            Consumer<UserEntity> userValidatorAndMutator) {
        if (tokenHash == null || tokenHash.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid or expired token");
        }
        Long userId = tokenRepository.findUserIdByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid or expired token"));
        if (expectedUserId != null && !expectedUserId.equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Token does not belong to current user");
        }
        UserEntity lockedUser = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid or expired token"));
        PasswordResetTokenEntity tokenEntity = tokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid or expired token"));

        validateChallengeEntity(tokenEntity, lockedUser, expectedPurpose, expectedProvider);

        Instant now = clock.instant();
        tokenEntity.setUsed(true);
        tokenEntity.setConsumedAt(now);
        tokenRepository.saveAndFlush(tokenEntity);

        if (userValidatorAndMutator != null) {
            userValidatorAndMutator.accept(lockedUser);
            userRepository.saveAndFlush(lockedUser);
        }
        return new ConsumedChallenge(
                lockedUser.getId(),
                tokenEntity.getPurpose(),
                tokenEntity.getTargetProvider(),
                tokenHash);
    }

    @Scheduled(fixedDelayString = "${application.security.password-reset.cleanup-interval-ms:300000}")
    @Transactional
    public int purgeExpiredChallenges() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(1));
        return tokenRepository.deleteExpiredBefore(cutoff);
    }

    private void validateChallengeEntity(
            PasswordResetTokenEntity tokenEntity,
            UserEntity user,
            EmailTokenPurpose expectedPurpose,
            String expectedProvider) {
        Instant now = clock.instant();
        if (tokenEntity.isConsumedOrInvalidated() || tokenEntity.isExpiredAt(now)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Token is invalid or expired");
        }
        if (tokenEntity.getPurpose() != expectedPurpose) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Token purpose mismatch");
        }
        long expectedVersion = user.effectiveTokenVersion();
        long tokenVersion = tokenEntity.getCredentialVersion() != null ? tokenEntity.getCredentialVersion() : 0L;
        if (tokenVersion != expectedVersion) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Token has been superseded");
        }
        if (expectedProvider != null && !expectedProvider.isBlank()) {
            if (!Objects.equals(expectedProvider.toUpperCase(),
                    tokenEntity.getTargetProvider() != null ? tokenEntity.getTargetProvider().toUpperCase() : null)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Token provider mismatch");
            }
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
