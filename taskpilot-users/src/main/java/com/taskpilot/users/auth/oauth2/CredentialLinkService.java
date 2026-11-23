package com.taskpilot.users.auth.oauth2;

import com.taskpilot.infrastructure.config.security.oauth2.OAuth2AuthorizationRequestStore.Purpose;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2LinkIntentVerifier;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.auth.dto.BeginCredentialLinkResponse;
import com.taskpilot.users.auth.service.EmailChallengeService;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.entity.UserIdentityEntity;
import com.taskpilot.users.common.enums.AuthProvider;
import com.taskpilot.users.common.enums.EmailTokenPurpose;
import com.taskpilot.users.common.repository.UserIdentityRepository;
import com.taskpilot.users.common.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

/**
 * Coordinates explicit authenticated OAuth provider linking and recent re-authentication proofs.
 * Enforces proof of both the existing account credential and the fresh provider callback.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialLinkService implements OAuth2LinkIntentVerifier {

    public static final long LINK_INTENT_TTL_SECONDS = 300L;
    private static final long LINK_INTENT_TTL_MS = LINK_INTENT_TTL_SECONDS * 1000L;

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailChallengeService emailChallengeService;

    @Transactional
    public BeginCredentialLinkResponse beginLink(
            long userId,
            String rawProvider,
            String currentPassword,
            String reauthTicket) {
        AuthProvider provider = parseProvider(rawProvider);
        UserEntity lockedUser = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));

        if (!lockedUser.isActiveAccount() || !Boolean.TRUE.equals(lockedUser.getEmailVerified())) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Account must be active and email-verified before linking providers");
        }
        if (userIdentityRepository.existsByUserIdAndProvider(userId, provider)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Provider " + provider.name().toLowerCase(Locale.ROOT) + " is already linked to your account");
        }

        verifyExistingCredentialProof(lockedUser, currentPassword, reauthTicket);

        EmailChallengeService.IssuedChallenge issued = emailChallengeService.issueForLockedUser(
                lockedUser,
                EmailTokenPurpose.CREDENTIAL_LINK,
                LINK_INTENT_TTL_MS,
                provider.name());

        return new BeginCredentialLinkResponse(
                issued.rawToken(),
                provider.name().toLowerCase(Locale.ROOT),
                LINK_INTENT_TTL_SECONDS);
    }

    @Transactional
    public BeginCredentialLinkResponse beginReauth(long userId, String rawProvider) {
        AuthProvider provider = parseProvider(rawProvider);
        UserEntity lockedUser = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));

        if (!lockedUser.isActiveAccount() || !Boolean.TRUE.equals(lockedUser.getEmailVerified())) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Account must be active and email-verified");
        }
        if (!userIdentityRepository.existsByUserIdAndProvider(userId, provider)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Provider is not linked to your account");
        }

        EmailChallengeService.IssuedChallenge issued = emailChallengeService.issueForLockedUser(
                lockedUser,
                EmailTokenPurpose.CREDENTIAL_LINK,
                LINK_INTENT_TTL_MS,
                provider.name());

        return new BeginCredentialLinkResponse(
                issued.rawToken(),
                provider.name().toLowerCase(Locale.ROOT),
                LINK_INTENT_TTL_SECONDS);
    }

    @Override
    @Transactional
    public VerifiedIntent verifyIntent(Purpose purpose, String provider, String rawIntentToken) {
        if (purpose != Purpose.LINK && purpose != Purpose.REAUTH) {
            throw new IllegalArgumentException("Unsupported intent purpose: " + purpose);
        }
        EmailChallengeService.VerifiedIntentChallenge verified = emailChallengeService.verifyActiveIntent(
                rawIntentToken, EmailTokenPurpose.CREDENTIAL_LINK, provider);
        return new VerifiedIntent(verified.userId(), verified.tokenHash());
    }

    @Transactional
    public void completeLink(long targetUserId, String linkTokenHash, OAuth2UserInfo userInfo) {
        if (userInfo == null || userInfo.provider() == null
                || userInfo.providerSubject() == null || userInfo.providerSubject().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "provider_error");
        }
        if (!userInfo.emailVerified() || userInfo.email() == null || userInfo.email().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "email_unverified");
        }

        emailChallengeService.consumeByHash(
                linkTokenHash,
                EmailTokenPurpose.CREDENTIAL_LINK,
                targetUserId,
                userInfo.provider().name(),
                lockedUser -> {
                    if (!lockedUser.isActiveAccount() || !Boolean.TRUE.equals(lockedUser.getEmailVerified())) {
                        throw new BusinessException(HttpStatus.FORBIDDEN.value(), "account_inactive");
                    }
                    var existingSubject = userIdentityRepository.findByProviderAndProviderSubject(
                            userInfo.provider(), userInfo.providerSubject());
                    if (existingSubject.isPresent()) {
                        if (!existingSubject.get().getUser().getId().equals(lockedUser.getId())) {
                            throw new BusinessException(HttpStatus.CONFLICT.value(), "provider_already_bound");
                        }
                        throw new BusinessException(HttpStatus.CONFLICT.value(), "provider_already_linked");
                    }
                    if (userIdentityRepository.existsByUserIdAndProvider(lockedUser.getId(), userInfo.provider())) {
                        throw new BusinessException(HttpStatus.CONFLICT.value(), "provider_already_linked");
                    }

                    Instant now = Instant.now();
                    UserIdentityEntity linked = UserIdentityEntity.builder()
                            .user(lockedUser)
                            .provider(userInfo.provider())
                            .providerSubject(userInfo.providerSubject())
                            .email(userInfo.email().trim().toLowerCase(Locale.ROOT))
                            .emailVerifiedAt(now)
                            .verificationProvenance(OAuth2AccountService.verificationProvenance(userInfo.provider()))
                            .lastLoginAt(now)
                            .build();
                    try {
                        userIdentityRepository.saveAndFlush(linked);
                    } catch (DataIntegrityViolationException ex) {
                        throw new BusinessException(HttpStatus.CONFLICT.value(), "provider_already_bound");
                    }
                });

        log.info("Explicit OAuth2 link completed for userId={}, provider={}", targetUserId, userInfo.provider());
    }

    @Transactional
    public String completeReauth(long targetUserId, String linkTokenHash, OAuth2UserInfo userInfo) {
        if (userInfo == null || userInfo.provider() == null
                || userInfo.providerSubject() == null || userInfo.providerSubject().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "provider_error");
        }

        final String[] issuedRaw = new String[1];
        emailChallengeService.consumeByHash(
                linkTokenHash,
                EmailTokenPurpose.CREDENTIAL_LINK,
                targetUserId,
                userInfo.provider().name(),
                lockedUser -> {
                    if (!lockedUser.isActiveAccount() || !Boolean.TRUE.equals(lockedUser.getEmailVerified())) {
                        throw new BusinessException(HttpStatus.FORBIDDEN.value(), "account_inactive");
                    }
                    UserIdentityEntity identity = userIdentityRepository.findByProviderAndProviderSubject(
                                    userInfo.provider(), userInfo.providerSubject())
                            .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN.value(), "provider_reauth_mismatch"));
                    if (!identity.getUser().getId().equals(lockedUser.getId())) {
                        throw new BusinessException(HttpStatus.FORBIDDEN.value(), "provider_reauth_mismatch");
                    }
                    EmailChallengeService.IssuedChallenge reauthProof = emailChallengeService.issueForLockedUser(
                            lockedUser,
                            EmailTokenPurpose.REAUTH_PROOF,
                            LINK_INTENT_TTL_MS,
                            userInfo.provider().name());
                    issuedRaw[0] = reauthProof.rawToken();
                });
        return issuedRaw[0];
    }

    public void verifyExistingCredentialProof(UserEntity lockedUser, String currentPassword, String reauthTicket) {
        if (lockedUser.hasUsablePassword()) {
            if (currentPassword != null && !currentPassword.isBlank()
                    && passwordEncoder.matches(currentPassword, lockedUser.getPassword())) {
                return;
            }
            if (reauthTicket != null && !reauthTicket.isBlank()) {
                consumeReauthProof(lockedUser.getId(), reauthTicket);
                return;
            }
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Valid current password or recent re-authentication proof is required");
        }
        if (reauthTicket == null || reauthTicket.isBlank()) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Recent re-authentication with your linked provider is required");
        }
        consumeReauthProof(lockedUser.getId(), reauthTicket);
    }

    private void consumeReauthProof(long userId, String reauthTicket) {
        emailChallengeService.consume(
                reauthTicket,
                EmailTokenPurpose.REAUTH_PROOF,
                userId,
                null,
                null);
    }

    private static AuthProvider parseProvider(String rawProvider) {
        if (rawProvider == null || rawProvider.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Provider is required");
        }
        try {
            return AuthProvider.valueOf(rawProvider.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Unsupported provider: " + rawProvider);
        }
    }
}
