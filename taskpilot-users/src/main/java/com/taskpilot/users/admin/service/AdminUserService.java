package com.taskpilot.users.admin.service;

import com.taskpilot.contracts.user.dto.SecurityChangeReason;
import com.taskpilot.infrastructure.config.security.CurrentActorResolver;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.util.PageableUtils;
import com.taskpilot.users.admin.dto.AdminCreateUserRequest;
import com.taskpilot.users.admin.dto.AdminUpdateUserRequest;
import com.taskpilot.users.admin.dto.AdminUserResponse;
import com.taskpilot.users.auth.service.UserSessionService;
import com.taskpilot.users.auth.service.email.EmailService;
import com.taskpilot.users.common.entity.PasswordResetTokenEntity;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.enums.UserStatus;
import com.taskpilot.users.common.repository.PasswordResetTokenRepository;
import com.taskpilot.users.common.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminUserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final UserSessionService userSessionService;

    @Value("${application.security.password-reset.expiration:900000}")
    private long passwordResetExpirationMs;

    private static final String DEFAULT_PASSWORD_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789@#$!";
    private static final int DEFAULT_PASSWORD_LENGTH = 12;

    public Page<AdminUserResponse> getAllUsers(String keyword, Pageable pageable) {
        Pageable safePageable = PageableUtils.sanitize(pageable, "id", Sort.Direction.ASC,
                "id", "email", "fullName", "role", "status", "currentWorkload", "createdAt", "updatedAt");
        if (keyword != null && !keyword.isBlank()) {
            return userRepository.findByKeyword(keyword.trim(), safePageable)
                    .map(AdminUserResponse::fromEntity);
        }
        return userRepository.findAll(safePageable).map(AdminUserResponse::fromEntity);
    }

    public AdminUserResponse getUserDetail(Long id) {
        UserEntity user = userRepository.findById(id).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));
        return AdminUserResponse.fromEntity(user);
    }

    @Transactional
    public AdminUserResponse createUser(AdminCreateUserRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Email is already registered!");
        }

        String defaultPassword = generateRandomPassword();

        UserEntity user = UserEntity.builder()
                .email(email)
                .fullName(request.fullName())
                .password(passwordEncoder.encode(defaultPassword))
                .role(request.role())
                .accountState(AccountLifecycle.ACTIVE)
                .emailVerified(Boolean.FALSE)
                .tokenVersion(0L)
                .build();

        userRepository.save(user);

        // Send password reset email so user can set their own password
        issuePasswordResetAndNotify(user);

        return AdminUserResponse.fromEntity(user);
    }

    @Transactional
    public AdminUserResponse updateUser(Long id, AdminUpdateUserRequest request) {
        UserEntity user = userRepository.findByIdForUpdate(id).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));

        long currentUserId = CurrentActorResolver.userId(SecurityContextHolder.getContext().getAuthentication());
        if (user.getId().equals(currentUserId)) {
            if (request.status() == UserStatus.DEACTIVATED) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Cannot deactivate your own account");
            }
            if (request.role() == UserRole.USER) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Cannot demote your own admin account");
            }
        }

        boolean roleChanged = request.role() != null && request.role() != user.getRole();
        boolean statusDeactivated = request.status() == UserStatus.DEACTIVATED && user.getStatus() != UserStatus.DEACTIVATED;
        boolean statusReactivated = request.status() != null
                && request.status() != UserStatus.DEACTIVATED
                && (user.getStatus() == UserStatus.DEACTIVATED || user.getAccountState() != AccountLifecycle.ACTIVE);

        if (request.role() != null) {
            user.setRole(request.role());
        }
        if (request.status() == UserStatus.DEACTIVATED) {
            user.setStatus(UserStatus.DEACTIVATED);
            user.setAccountState(AccountLifecycle.ADMIN_SUSPENDED);
        } else if (request.status() != null) {
            user.setStatus(request.status());
            if (statusReactivated) {
                user.setAccountState(AccountLifecycle.ACTIVE);
            }
        }
        if (request.currentWorkload() != null) {
            user.setCurrentWorkload(request.currentWorkload());
        }

        userRepository.save(user);

        if (statusDeactivated) {
            userSessionService.invalidateAll(user.getId(), SecurityChangeReason.USER_SUSPENDED);
        } else if (roleChanged || statusReactivated) {
            userSessionService.invalidateAll(user.getId(), SecurityChangeReason.ROLE_CHANGED);
        }

        return AdminUserResponse.fromEntity(user);
    }

    @Transactional
    public void deactivateUser(Long id) {
        UserEntity user = userRepository.findByIdForUpdate(id).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));

        long currentUserId = CurrentActorResolver.userId(SecurityContextHolder.getContext().getAuthentication());
        if (user.getId().equals(currentUserId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Cannot deactivate your own account");
        }

        user.setStatus(UserStatus.DEACTIVATED);
        user.setAccountState(AccountLifecycle.ADMIN_SUSPENDED);
        userRepository.save(user);
        userSessionService.invalidateAll(user.getId(), SecurityChangeReason.USER_DEACTIVATED);
    }

    @Transactional
    public void resetPassword(Long id) {
        UserEntity user = userRepository.findByIdForUpdate(id).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));

        String newPassword = generateRandomPassword();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        userSessionService.invalidateAll(user.getId(), SecurityChangeReason.PASSWORD_RESET);

        // Send password reset email so user can set a new password
        issuePasswordResetAndNotify(user);
    }

    private void issuePasswordResetAndNotify(UserEntity user) {
        passwordResetTokenRepository.deleteByUser(user);

        PasswordResetTokenEntity resetToken = PasswordResetTokenEntity.builder().user(user)
                .token(UUID.randomUUID().toString())
                .expiryDate(Instant.now().plusMillis(passwordResetExpirationMs)).used(false)
                .build();

        PasswordResetTokenEntity savedToken = passwordResetTokenRepository.save(resetToken);

        try {
            emailService.sendPasswordResetEmail(user.getEmail(), savedToken.getToken(), passwordResetExpirationMs);
        } catch (Exception ex) {
            log.error("Failed to send password reset email to userId={}", user.getId(), ex);
        }
    }

    private String generateRandomPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(DEFAULT_PASSWORD_LENGTH);
        for (int i = 0; i < DEFAULT_PASSWORD_LENGTH; i++) {
            sb.append(
                    DEFAULT_PASSWORD_CHARS.charAt(random.nextInt(DEFAULT_PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}
