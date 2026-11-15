package com.taskpilot.users.auth.service;

import com.taskpilot.contracts.user.dto.SecurityChangeReason;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2OneTimeCodeStore;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2Properties;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.auth.dto.AuthResponse;
import com.taskpilot.users.auth.dto.ForgotPasswordRequest;
import com.taskpilot.users.auth.dto.LoginRequest;
import com.taskpilot.users.auth.dto.RefreshTokenRequest;
import com.taskpilot.users.auth.dto.RegisterRequest;
import com.taskpilot.users.auth.dto.ResetPasswordRequest;
import com.taskpilot.users.auth.service.email.EmailService;
import com.taskpilot.users.common.entity.PasswordResetTokenEntity;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.repository.PasswordResetTokenRepository;
import com.taskpilot.users.common.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {
    @Value("${application.security.password-reset.expiration:900000}")
    private long passwordResetExpirationMs;

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserSessionService userSessionService;
    private final EmailService emailService;
    private final PasswordResetThrottleService passwordResetThrottleService;
    private final OAuth2OneTimeCodeStore oAuth2OneTimeCodeStore;
    private final OAuth2Properties oAuth2Properties;

    @Transactional
    public void register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Email is already registered!");
        }

        UserEntity newUser = UserEntity.builder()
                .email(email)
                .password(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .role(UserRole.USER)
                .accountState(AccountLifecycle.ACTIVE)
                .emailVerified(Boolean.FALSE)
                .tokenVersion(0L)
                .build();

        userRepository.save(newUser);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Invalid email or password"));

        if (user.getPassword() == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Invalid email or password");
        }
        if (!user.isActiveAccount()) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Account is not active");
        }

        return userSessionService.issueSession(user.getId());
    }

    @Transactional
    public AuthResponse exchangeOAuth2Code(String code) {
        Long userId = oAuth2OneTimeCodeStore.consume(code)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid or expired exchange code"));

        return userSessionService.issueSession(userId);
    }

    public java.util.List<String> getEnabledOAuth2Providers() {
        java.util.List<String> providers = new java.util.ArrayList<>();
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
                .ifPresent(this::issuePasswordResetTokenAndNotify);
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        PasswordResetTokenEntity tokenEntity = passwordResetTokenRepository.findByToken(request.token())
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "Invalid reset token. Please use the latest reset link from your email."));

        if (tokenEntity.isUsed() || tokenEntity.isExpired()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Reset token is invalid or expired. Please request a new reset link.");
        }

        UserEntity lockedUser = userRepository.findByIdForUpdate(tokenEntity.getUser().getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST.value(), "User not found"));
        lockedUser.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(lockedUser);

        tokenEntity.setUsed(true);
        passwordResetTokenRepository.save(tokenEntity);

        userSessionService.invalidateAll(lockedUser.getId(), SecurityChangeReason.PASSWORD_RESET);
    }

    private void issuePasswordResetTokenAndNotify(UserEntity user) {
        passwordResetTokenRepository.deleteByUser(user);

        PasswordResetTokenEntity resetToken = PasswordResetTokenEntity.builder()
                .user(user)
                .token(UUID.randomUUID().toString())
                .expiryDate(Instant.now().plusMillis(passwordResetExpirationMs))
                .used(false)
                .build();

        PasswordResetTokenEntity savedToken = passwordResetTokenRepository.save(resetToken);

        try {
            emailService.sendPasswordResetEmail(user.getEmail(), savedToken.getToken(), passwordResetExpirationMs);
        } catch (Exception ex) {
            log.error("Failed to send password reset email to userId={}", user.getId(), ex);
        }
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
