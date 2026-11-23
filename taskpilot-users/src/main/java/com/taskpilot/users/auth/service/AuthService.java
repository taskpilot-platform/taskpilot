package com.taskpilot.users.auth.service;

import com.taskpilot.contracts.user.dto.SecurityChangeReason;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2OneTimeCodeStore;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2OneTimeCodeStore.ExchangePayload;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2Properties;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.auth.dto.*;
import com.taskpilot.users.auth.service.email.EmailService;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.EmailTokenPurpose;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.enums.UserStatus;
import com.taskpilot.users.common.repository.PasswordResetTokenRepository;
import com.taskpilot.users.common.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@Slf4j
public class AuthService {

    private static final String DUMMY_BCRYPT_HASH =
            "$2a$10$7EqJtq98hPqEX7fNZaFWoOHiH5l1sB/5jD6d2uE7hX1dY5eN6zU5W";

    @Value("${application.security.password-reset.expiration:900000}")
    private long passwordResetExpirationMs = EmailChallengeService.DEFAULT_EMAIL_CHALLENGE_TTL_MS;

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserSessionService userSessionService;
    private final EmailService emailService;
    private final PasswordResetThrottleService passwordResetThrottleService;
    private final OAuth2OneTimeCodeStore oAuth2OneTimeCodeStore;
    private final OAuth2Properties oAuth2Properties;
    private final PasswordPolicy passwordPolicy;
    private final EmailChallengeService emailChallengeService;
    private final AuthAttemptThrottleService authAttemptThrottleService;
    private final RestoreTicketService restoreTicketService;

    public AuthService(
            UserRepository userRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordEncoder passwordEncoder,
            UserSessionService userSessionService,
            EmailService emailService,
            PasswordResetThrottleService passwordResetThrottleService,
            OAuth2OneTimeCodeStore oAuth2OneTimeCodeStore,
            OAuth2Properties oAuth2Properties) {
        this(
                userRepository,
                passwordResetTokenRepository,
                passwordEncoder,
                userSessionService,
                emailService,
                passwordResetThrottleService,
                oAuth2OneTimeCodeStore,
                oAuth2Properties,
                new PasswordPolicy(),
                new EmailChallengeService(passwordResetTokenRepository, userRepository),
                new AuthAttemptThrottleService(new InMemoryAuthAttemptStore()),
                null);
    }

    @Autowired
    public AuthService(
            UserRepository userRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordEncoder passwordEncoder,
            UserSessionService userSessionService,
            EmailService emailService,
            PasswordResetThrottleService passwordResetThrottleService,
            OAuth2OneTimeCodeStore oAuth2OneTimeCodeStore,
            OAuth2Properties oAuth2Properties,
            PasswordPolicy passwordPolicy,
            EmailChallengeService emailChallengeService,
            AuthAttemptThrottleService authAttemptThrottleService,
            RestoreTicketService restoreTicketService) {
        this.userRepository = userRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.userSessionService = userSessionService;
        this.emailService = emailService;
        this.passwordResetThrottleService = passwordResetThrottleService;
        this.oAuth2OneTimeCodeStore = oAuth2OneTimeCodeStore;
        this.oAuth2Properties = oAuth2Properties;
        this.passwordPolicy = passwordPolicy != null ? passwordPolicy : new PasswordPolicy();
        this.emailChallengeService = emailChallengeService != null
                ? emailChallengeService
                : new EmailChallengeService(passwordResetTokenRepository, userRepository);
        this.authAttemptThrottleService = authAttemptThrottleService != null
                ? authAttemptThrottleService
                : new AuthAttemptThrottleService(new InMemoryAuthAttemptStore());
        this.restoreTicketService = restoreTicketService != null
                ? restoreTicketService
                : new RestoreTicketService(this.emailChallengeService, userSessionService);
    }

    @Transactional
    public void register(RegisterRequest request) {
        passwordPolicy.validate(request.password());
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Email is already registered!");
        }

        // Store pending record only; pre-registration password is never activated merely by clicking an email link.
        UserEntity newUser = UserEntity.builder()
                .email(email)
                .password(passwordEncoder.encode(request.password()))
                .fullName(request.fullName().trim())
                .role(UserRole.USER)
                .status(UserStatus.AVAILABLE)
                .accountState(AccountLifecycle.PENDING_EMAIL)
                .emailVerified(Boolean.FALSE)
                .emailVerificationProvenance("PENDING_SIGNUP")
                .tokenVersion(0L)
                .build();

        newUser = userRepository.save(newUser);
        if (newUser.getId() != null) {
            EmailChallengeService.IssuedChallenge challenge = emailChallengeService.issueForLockedUser(
                    newUser, EmailTokenPurpose.EMAIL_VERIFY, passwordResetExpirationMs, null);
            sendEmailSafely(() -> emailService.sendEmailVerificationEmail(
                    email, challenge.rawToken(), passwordResetExpirationMs), newUser.getId());
        }
    }

    @Transactional
    public void resendVerification(ResendVerificationRequest request, String clientIp) {
        String email = normalizeEmail(request.email());
        authAttemptThrottleService.checkAndConsumeChallengeRequest("EMAIL_VERIFY", email, clientIp);
        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.getAccountState() == AccountLifecycle.PENDING_EMAIL
                    || (user.getAccountState() == AccountLifecycle.ACTIVE
                    && !Boolean.TRUE.equals(user.getEmailVerified()))) {
                UserEntity locked = userRepository.findByIdForUpdate(user.getId()).orElse(user);
                EmailChallengeService.IssuedChallenge challenge = emailChallengeService.issueForLockedUser(
                        locked, EmailTokenPurpose.EMAIL_VERIFY, passwordResetExpirationMs, null);
                sendEmailSafely(() -> emailService.sendEmailVerificationEmail(
                        locked.getEmail(), challenge.rawToken(), passwordResetExpirationMs), locked.getId());
            }
        });
    }

    @Transactional
    public void verifyEmailAndChoosePassword(VerifyEmailRequest request, String clientIp) {
        authAttemptThrottleService.checkAndConsumeTokenAttempt("VERIFY_CONSUME", clientIp);
        passwordPolicy.validate(request.newPassword());

        EmailChallengeService.ConsumedChallenge consumed = emailChallengeService.consume(
                request.token(),
                EmailTokenPurpose.EMAIL_VERIFY,
                null,
                null,
                user -> {
                    AccountLifecycle lifecycle = effectiveLifecycle(user);
                    if (lifecycle == AccountLifecycle.SELF_DEACTIVATED
                            || lifecycle == AccountLifecycle.ADMIN_SUSPENDED
                            || lifecycle == AccountLifecycle.LEGACY_DISABLED) {
                        throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                                "Disabled or suspended accounts cannot be activated via email verification");
                    }
                    // Mailbox owner sets final password, replacing any attacker-supplied pending hash.
                    user.setPassword(passwordEncoder.encode(request.newPassword()));
                    user.setAccountState(AccountLifecycle.ACTIVE);
                    user.setEmailVerified(Boolean.TRUE);
                    user.setEmailVerifiedAt(Instant.now());
                    user.setEmailVerificationProvenance("EMAIL_CHALLENGE");
                    user.setLifecycleChangedAt(Instant.now());
                    user.setLifecycleReason("EMAIL_VERIFIED");
                    user.setLifecycleChangedBy(user.getId());
                });

        userSessionService.invalidateAll(consumed.userId(), SecurityChangeReason.EMAIL_VERIFIED);
    }

    public AuthResponse login(LoginRequest request) {
        return login(request, "unknown");
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse login(LoginRequest request, String clientIp) {
        String email = normalizeEmail(request.email());
        authAttemptThrottleService.checkLoginAllowed(email, clientIp);

        UserEntity user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !user.hasUsablePassword()) {
            passwordEncoder.matches(request.password(), DUMMY_BCRYPT_HASH);
            authAttemptThrottleService.recordLoginFailure(email, clientIp);
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Invalid email or password");
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            authAttemptThrottleService.recordLoginFailure(email, clientIp);
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Invalid email or password");
        }

        authAttemptThrottleService.clearLoginFailures(email, clientIp);

        AccountLifecycle lifecycle = effectiveLifecycle(user);
        if (lifecycle == AccountLifecycle.SELF_DEACTIVATED) {
            UserEntity locked = userRepository.findByIdForUpdate(user.getId()).orElse(user);
            EmailChallengeService.IssuedChallenge ticket = restoreTicketService.issueRestoreTicket(locked);
            return AuthResponse.restoreRequired(
                    locked.getEmail(), "local", ticket.rawToken(), RestoreTicketService.RESTORE_TICKET_TTL_SECONDS);
        }

        if (lifecycle == AccountLifecycle.PENDING_EMAIL
                || (lifecycle == AccountLifecycle.ACTIVE && !Boolean.TRUE.equals(user.getEmailVerified()))) {
            return AuthResponse.emailVerificationRequired(
                    user.getEmail(), "local",
                    "Please verify your email address and set your final password before signing in.");
        }

        if (lifecycle != AccountLifecycle.ACTIVE) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Account is not active");
        }

        return userSessionService.issueSession(user.getId());
    }

    @Transactional
    public AuthResponse confirmRestore(RestoreAccountRequest request, String clientIp) {
        authAttemptThrottleService.checkAndConsumeTokenAttempt("RESTORE_CONFIRM", clientIp);
        return restoreTicketService.confirmRestore(request.ticket());
    }

    @Transactional
    public void cancelRestore(RestoreAccountRequest request) {
        restoreTicketService.cancelRestore(request.ticket());
    }

    @Transactional
    public void requestPasswordSetup(long actorUserId, String clientIp) {
        UserEntity locked = userRepository.findByIdForUpdate(actorUserId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));
        if (!locked.isActiveAccount() || !Boolean.TRUE.equals(locked.getEmailVerified())) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Account must be active and verified");
        }
        if (locked.hasUsablePassword()) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Account already has a password. Use change-password instead.");
        }
        authAttemptThrottleService.checkAndConsumeChallengeRequest("PASSWORD_SETUP", locked.getEmail(), clientIp);
        EmailChallengeService.IssuedChallenge challenge = emailChallengeService.issueForLockedUser(
                locked, EmailTokenPurpose.PASSWORD_SETUP, passwordResetExpirationMs, null);
        sendEmailSafely(() -> emailService.sendPasswordSetupEmail(
                locked.getEmail(), challenge.rawToken(), passwordResetExpirationMs), locked.getId());
    }

    @Transactional
    public void completePasswordSetup(CompletePasswordSetupRequest request, String clientIp) {
        authAttemptThrottleService.checkAndConsumeTokenAttempt("SETUP_CONSUME", clientIp);
        passwordPolicy.validate(request.newPassword());

        EmailChallengeService.ConsumedChallenge consumed = emailChallengeService.consume(
                request.token(),
                EmailTokenPurpose.PASSWORD_SETUP,
                null,
                null,
                user -> {
                    if (!user.isActiveAccount() || !Boolean.TRUE.equals(user.getEmailVerified())) {
                        throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Account is not active or verified");
                    }
                    if (user.hasUsablePassword()) {
                        throw new BusinessException(HttpStatus.CONFLICT.value(),
                                "Account already has a password. Use password reset or change-password.");
                    }
                    user.setPassword(passwordEncoder.encode(request.newPassword()));
                });

        userSessionService.invalidateAll(consumed.userId(), SecurityChangeReason.PASSWORD_CHANGED);
    }

    @Transactional
    public AuthResponse exchangeOAuth2Code(String code) {
        ExchangePayload payload = oAuth2OneTimeCodeStore.consumePayload(code)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "Invalid or expired exchange code"));

        return switch (payload.outcome()) {
            case AUTHENTICATED -> userSessionService.issueSession(payload.userId());
            case RESTORE_REQUIRED -> AuthResponse.restoreRequired(
                    payload.email(), payload.provider(), payload.restoreTicket(),
                    payload.restoreExpiresIn() != null ? payload.restoreExpiresIn() : RestoreTicketService.RESTORE_TICKET_TTL_SECONDS);
            case EMAIL_VERIFICATION_REQUIRED -> AuthResponse.emailVerificationRequired(
                    payload.email(), payload.provider(), payload.message());
            case OAUTH_EMAIL_COLLISION -> AuthResponse.oauthEmailCollision(
                    payload.email(), payload.provider(), payload.message());
            case LINK_COMPLETED -> AuthResponse.linkCompleted(payload.provider());
            case REAUTH_COMPLETED -> AuthResponse.reauthCompleted(
                    payload.provider(), payload.reauthTicket(),
                    payload.reauthExpiresIn() != null ? payload.reauthExpiresIn() : 300L);
        };
    }

    public List<String> getEnabledOAuth2Providers() {
        List<String> providers = new ArrayList<>();
        if (oAuth2Properties.getGoogle().isConfigured()) {
            providers.add("google");
        }
        if (oAuth2Properties.getGithub().isConfigured()) {
            providers.add("github");
        }
        return providers;
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        return userSessionService.rotateRefreshToken(request.refreshToken());
    }

    @Transactional
    public void logout(RefreshTokenRequest request, String authorizationHeader) {
        userSessionService.logoutSession(request != null ? request.refreshToken() : null, authorizationHeader);
    }

    @Transactional
    public void forgotPassword(ForgotPasswordRequest request, String clientIp) {
        passwordResetThrottleService.checkAndConsume(request.email(), clientIp);
        userRepository.findByEmail(normalizeEmail(request.email()))
                .ifPresent(user -> {
                    AccountLifecycle lifecycle = effectiveLifecycle(user);
                    // Only ACTIVE verified accounts can reset password; never send reset for ADMIN_SUSPENDED/LEGACY_DISABLED.
                    if (lifecycle == AccountLifecycle.ACTIVE && Boolean.TRUE.equals(user.getEmailVerified())) {
                        UserEntity locked = userRepository.findByIdForUpdate(user.getId()).orElse(user);
                        EmailChallengeService.IssuedChallenge challenge = emailChallengeService.issueForLockedUser(
                                locked, EmailTokenPurpose.PASSWORD_RESET, passwordResetExpirationMs, null);
                        sendEmailSafely(() -> emailService.sendPasswordResetEmail(
                                locked.getEmail(), challenge.rawToken(), passwordResetExpirationMs), locked.getId());
                    } else if (lifecycle == AccountLifecycle.PENDING_EMAIL
                            || (lifecycle == AccountLifecycle.ACTIVE && !Boolean.TRUE.equals(user.getEmailVerified()))) {
                        UserEntity locked = userRepository.findByIdForUpdate(user.getId()).orElse(user);
                        EmailChallengeService.IssuedChallenge challenge = emailChallengeService.issueForLockedUser(
                                locked, EmailTokenPurpose.EMAIL_VERIFY, passwordResetExpirationMs, null);
                        sendEmailSafely(() -> emailService.sendEmailVerificationEmail(
                                locked.getEmail(), challenge.rawToken(), passwordResetExpirationMs), locked.getId());
                    }
                });
    }

    public void resetPassword(ResetPasswordRequest request) {
        resetPassword(request, "unknown");
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request, String clientIp) {
        authAttemptThrottleService.checkAndConsumeTokenAttempt("RESET_CONSUME", clientIp);
        passwordPolicy.validate(request.newPassword());

        EmailChallengeService.ConsumedChallenge consumed = emailChallengeService.consume(
                request.token(),
                EmailTokenPurpose.PASSWORD_RESET,
                null,
                null,
                user -> {
                    // Resetting password never reactivates SELF_DEACTIVATED, ADMIN_SUSPENDED, or LEGACY_DISABLED.
                    AccountLifecycle lifecycle = effectiveLifecycle(user);
                    if (lifecycle != AccountLifecycle.ACTIVE) {
                        throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                                "Account is not active; password reset cannot reactivate disabled accounts");
                    }
                    user.setPassword(passwordEncoder.encode(request.newPassword()));
                });

        userSessionService.invalidateAll(consumed.userId(), SecurityChangeReason.PASSWORD_RESET);
    }

    private void sendEmailSafely(Runnable sender, long userId) {
        try {
            sender.run();
        } catch (Exception ex) {
            log.error("Failed to send auth email to userId={}", userId, ex);
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

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
