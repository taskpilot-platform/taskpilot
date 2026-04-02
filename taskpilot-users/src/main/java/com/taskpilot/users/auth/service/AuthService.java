package com.taskpilot.users.auth.service;

import com.taskpilot.infrastructure.config.security.JwtService;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.auth.dto.AuthResponse;
import com.taskpilot.users.auth.dto.ForgotPasswordRequest;
import com.taskpilot.users.auth.dto.LoginRequest;
import com.taskpilot.users.auth.dto.RefreshTokenRequest;
import com.taskpilot.users.auth.dto.RegisterRequest;
import com.taskpilot.users.auth.dto.ResetPasswordRequest;
import com.taskpilot.users.auth.service.email.EmailService;
import com.taskpilot.users.entity.PasswordResetTokenEntity;
import com.taskpilot.users.entity.RefreshTokenEntity;
import com.taskpilot.users.entity.UserEntity;
import com.taskpilot.users.repository.PasswordResetTokenRepository;
import com.taskpilot.users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final EmailService emailService;

    public void register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Email is already registered!");
        }

        UserEntity newUser = UserEntity.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .role(UserEntity.UserRole.USER)
                .build();

        userRepository.save(newUser);
    }

    public AuthResponse login(LoginRequest request) {
        UserEntity user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Invalid email or password");
        }
        String token = jwtService.generateToken(user.getEmail(), user.getRole().name());
        RefreshTokenEntity refreshToken = refreshTokenService.createRefreshToken(user);

        return new AuthResponse(token, refreshToken.getToken(), "Bearer", jwtService.getJwtExpiration());
    }

    public AuthResponse refreshToken(RefreshTokenRequest request) {
        return refreshTokenService.findByToken(request.refreshToken())
                .map(refreshTokenService::verifyExpiration)
                .map(RefreshTokenEntity::getUser)
                .map(user -> {
                    String token = jwtService.generateToken(user.getEmail(), user.getRole().name());
                    return new AuthResponse(token, request.refreshToken(), "Bearer", jwtService.getJwtExpiration());
                })
                .orElseThrow(
                        () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Refresh token is not in database!"));
    }

    public void logout(RefreshTokenRequest request, String authorizationHeader) {
        String accessToken = extractBearerToken(authorizationHeader);
        if (accessToken != null) {
            jwtService.revokeToken(accessToken);
        }

        RefreshTokenEntity refreshToken = refreshTokenService.findByToken(request.refreshToken())
                .orElse(null);

        if (refreshToken == null) {
            log.debug("Logout called with non-existing refresh token");
            return;
        }

        refreshTokenService.delete(refreshToken);
    }

    private String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        return authorizationHeader.substring(7);
    }

    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        userRepository.findByEmail(request.email())
                .ifPresent(this::issuePasswordResetTokenAndNotify);
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        PasswordResetTokenEntity tokenEntity = passwordResetTokenRepository.findByToken(request.token())
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid reset token"));

        if (tokenEntity.isUsed() || tokenEntity.isExpired()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Reset token is invalid or expired");
        }

        UserEntity user = tokenEntity.getUser();
        user.setPassword(passwordEncoder.encode(request.newPassword()));

        tokenEntity.setUsed(true);

        refreshTokenService.deleteByUser(user);
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
            emailService.sendPasswordResetEmail(user.getEmail(), savedToken.getToken());
        } catch (Exception ex) {
            log.error("Failed to send password reset email to {}", user.getEmail(), ex);
        }
    }
}
