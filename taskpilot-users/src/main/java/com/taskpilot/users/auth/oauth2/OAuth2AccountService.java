package com.taskpilot.users.auth.oauth2;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.auth.service.EmailChallengeService;
import com.taskpilot.users.auth.service.RestoreTicketService;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.entity.UserIdentityEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.AuthProvider;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.enums.UserStatus;
import com.taskpilot.users.common.repository.UserIdentityRepository;
import com.taskpilot.users.common.repository.UserRepository;
import com.taskpilot.users.profile.service.AvatarValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

/**
 * Resolves OAuth2 social identities without automatic email-based account merging.
 * Enforces lifecycle authority, verified-email provenance, and safe collision / restore outcomes.
 */
@Slf4j
@Service
public class OAuth2AccountService {

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final RestoreTicketService restoreTicketService;
    private final AvatarValidator avatarValidator;

    public OAuth2AccountService(
            UserRepository userRepository,
            UserIdentityRepository userIdentityRepository) {
        this(userRepository, userIdentityRepository, null, new AvatarValidator());
    }

    @Autowired
    public OAuth2AccountService(
            UserRepository userRepository,
            UserIdentityRepository userIdentityRepository,
            RestoreTicketService restoreTicketService,
            AvatarValidator avatarValidator) {
        this.userRepository = userRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.restoreTicketService = restoreTicketService;
        this.avatarValidator = avatarValidator != null ? avatarValidator : new AvatarValidator();
    }

    public sealed interface Resolution {
        record Authenticated(UserEntity user) implements Resolution {}
        record RestoreRequired(UserEntity user, String restoreTicket, long expiresInSeconds) implements Resolution {}
        record EmailVerificationRequired(UserEntity user, String message) implements Resolution {}
        record EmailCollision(String email, String provider, String message) implements Resolution {}
    }

    @Transactional
    public UserEntity resolveUser(OAuth2UserInfo userInfo) {
        Resolution resolution = resolveLoginOrRegister(userInfo);
        if (resolution instanceof Resolution.Authenticated auth) {
            return auth.user();
        }
        if (resolution instanceof Resolution.EmailCollision collision) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "oauth_email_collision");
        }
        if (resolution instanceof Resolution.RestoreRequired) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "restore_required");
        }
        if (resolution instanceof Resolution.EmailVerificationRequired) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "email_verification_required");
        }
        throw new BusinessException(HttpStatus.FORBIDDEN.value(), "account_inactive");
    }

    @Transactional
    public Resolution resolveLoginOrRegister(OAuth2UserInfo userInfo) {
        if (userInfo == null || userInfo.provider() == null
                || userInfo.providerSubject() == null || userInfo.providerSubject().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "provider_error");
        }

        // 1. Existing provider subject always resolves the immutable bound uid.
        var existingIdentityOpt = userIdentityRepository.findByProviderAndProviderSubject(
                userInfo.provider(), userInfo.providerSubject());

        if (existingIdentityOpt.isPresent()) {
            UserIdentityEntity identity = existingIdentityOpt.get();
            UserEntity lockedUser = userRepository.findByIdForUpdate(identity.getUser().getId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN.value(), "account_inactive"));

            AccountLifecycle lifecycle = effectiveLifecycle(lockedUser);
            if (lifecycle == AccountLifecycle.SELF_DEACTIVATED) {
                if (restoreTicketService == null) {
                    throw new BusinessException(HttpStatus.FORBIDDEN.value(), "restore_required");
                }
                EmailChallengeService.IssuedChallenge ticket = restoreTicketService.issueRestoreTicket(lockedUser);
                log.info("OAuth2 restore ticket issued for self-deactivated userId={}, provider={}",
                        lockedUser.getId(), userInfo.provider());
                return new Resolution.RestoreRequired(
                        lockedUser, ticket.rawToken(), RestoreTicketService.RESTORE_TICKET_TTL_SECONDS);
            }

            if (lifecycle != AccountLifecycle.ACTIVE) {
                log.warn("OAuth2 login rejected for userId={}, lifecycle={}", lockedUser.getId(), lifecycle);
                throw new BusinessException(HttpStatus.FORBIDDEN.value(), "account_inactive");
            }

            if (!Boolean.TRUE.equals(lockedUser.getEmailVerified())) {
                // Conservative legacy rule: do not silently keep an unproven local password on an unverified mixed account.
                if (lockedUser.hasUsablePassword()) {
                    return new Resolution.EmailVerificationRequired(
                            lockedUser,
                            "Email verification is required before signing in. Please verify your mailbox.");
                }
                if (!userInfo.emailVerified()) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "email_unverified");
                }
                String provenance = verificationProvenance(userInfo.provider());
                lockedUser.setEmailVerified(Boolean.TRUE);
                lockedUser.setEmailVerifiedAt(Instant.now());
                lockedUser.setEmailVerificationProvenance(provenance);
                userRepository.save(lockedUser);
                identity.setEmailVerifiedAt(Instant.now());
                identity.setVerificationProvenance(provenance);
            }

            identity.setLastLoginAt(Instant.now());
            if (userInfo.email() != null && !userInfo.email().isBlank()) {
                identity.setEmail(userInfo.email().trim().toLowerCase(Locale.ROOT));
            }
            userIdentityRepository.save(identity);
            log.info("OAuth2 login: matched existing identity provider={}, userId={}",
                    userInfo.provider(), lockedUser.getId());
            return new Resolution.Authenticated(lockedUser);
        }

        // 2. New provider subject requires verified provider email evidence.
        if (!userInfo.emailVerified() || userInfo.email() == null || userInfo.email().isBlank()) {
            log.warn("OAuth2 login rejected: unverified or missing email from provider={}", userInfo.provider());
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "email_unverified");
        }

        String normalizedEmail = userInfo.email().trim().toLowerCase(Locale.ROOT);

        // 3. No automatic same-email linking/merging: existing account must prove credentials and link explicitly.
        var existingUserOpt = userRepository.findByEmail(normalizedEmail);
        if (existingUserOpt.isPresent()) {
            log.warn("OAuth2 email collision prevented automatic merge for provider={}", userInfo.provider());
            return new Resolution.EmailCollision(
                    normalizedEmail,
                    userInfo.provider().name().toLowerCase(Locale.ROOT),
                    "An account with this email already exists. Sign in with your existing credentials and link this provider from Profile Settings.");
        }

        // 4. Create new verified OAuth-only account.
        Instant now = Instant.now();
        String provenance = verificationProvenance(userInfo.provider());
        String safeAvatar = avatarValidator.sanitizeProviderAvatarUrl(userInfo.avatarUrl());
        String displayName = (userInfo.fullName() != null && !userInfo.fullName().isBlank())
                ? userInfo.fullName().trim()
                : normalizedEmail.split("@")[0];

        try {
            UserEntity newUser = UserEntity.builder()
                    .email(normalizedEmail)
                    .fullName(displayName)
                    .avatarUrl(safeAvatar)
                    .password(null)
                    .role(UserRole.USER)
                    .status(UserStatus.AVAILABLE)
                    .accountState(AccountLifecycle.ACTIVE)
                    .emailVerified(Boolean.TRUE)
                    .emailVerifiedAt(now)
                    .emailVerificationProvenance(provenance)
                    .tokenVersion(0L)
                    .currentWorkload(0)
                    .build();
            newUser = userRepository.saveAndFlush(newUser);

            UserIdentityEntity newIdentity = UserIdentityEntity.builder()
                    .user(newUser)
                    .provider(userInfo.provider())
                    .providerSubject(userInfo.providerSubject())
                    .email(normalizedEmail)
                    .emailVerifiedAt(now)
                    .verificationProvenance(provenance)
                    .lastLoginAt(now)
                    .build();
            userIdentityRepository.saveAndFlush(newIdentity);

            log.info("OAuth2 register: created new verified userId={}, provider={}",
                    newUser.getId(), userInfo.provider());
            return new Resolution.Authenticated(newUser);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Concurrent OAuth2 registration conflict for provider={}", userInfo.provider());
            return new Resolution.EmailCollision(
                    normalizedEmail,
                    userInfo.provider().name().toLowerCase(Locale.ROOT),
                    "An account with this email or provider identity already exists.");
        }
    }

    private static AccountLifecycle effectiveLifecycle(UserEntity user) {
        AccountLifecycle lifecycle = user.getAccountState() != null
                ? user.getAccountState()
                : AccountLifecycle.ACTIVE;
        if (user.getStatus() == UserStatus.DEACTIVATED && lifecycle == AccountLifecycle.ACTIVE) {
            return AccountLifecycle.LEGACY_DISABLED;
        }
        return lifecycle;
    }

    static String verificationProvenance(AuthProvider provider) {
        return provider == AuthProvider.GOOGLE
                ? "GOOGLE_VERIFIED_CLAIM"
                : "GITHUB_VERIFIED_EMAILS_API";
    }
}
