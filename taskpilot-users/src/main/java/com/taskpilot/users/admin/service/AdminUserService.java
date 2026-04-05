package com.taskpilot.users.admin.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.admin.dto.AdminCreateUserRequest;
import com.taskpilot.users.admin.dto.AdminUpdateUserRequest;
import com.taskpilot.users.admin.dto.AdminUserResponse;
import com.taskpilot.users.repository.PasswordResetTokenRepository;
import com.taskpilot.users.auth.service.email.EmailService;
import com.taskpilot.users.entity.PasswordResetTokenEntity;
import com.taskpilot.users.entity.UserEntity;
import com.taskpilot.users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminUserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final PasswordResetTokenRepository passwordResetTokenRepository;

    @Value("${application.security.password-reset.expiration-ms:3600000}")
    private long passwordResetExpirationMs;

    private static final String DEFAULT_PASSWORD_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789@#$!";
    private static final int DEFAULT_PASSWORD_LENGTH = 12;

    public Page<AdminUserResponse> getAllUsers(String keyword, Pageable pageable) {
        Pageable safePageable = buildSafePageable(pageable, "id", "email", "fullName", "role",
                "status", "currentWorkload", "createdAt", "updatedAt");
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

    private Pageable buildSafePageable(Pageable pageable, String... allowedFields) {
        if (!pageable.getSort().isSorted()) {
            return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                    Sort.by(Sort.Direction.ASC, "id"));
        }

        Set<String> allowed = Set.of(allowedFields);
        for (Sort.Order order : pageable.getSort()) {
            if (!allowed.contains(order.getProperty())) {
                return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        Sort.by(Sort.Direction.ASC, "id"));
            }
        }
        return pageable;
    }

    @Transactional
    public AdminUserResponse createUser(AdminCreateUserRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Email '" + request.email() + "' already exists");
        }

        String defaultPassword = generateRandomPassword();

        UserEntity user = UserEntity.builder().email(request.email()).fullName(request.fullName())
                .password(passwordEncoder.encode(defaultPassword)).role(request.role()).build();

        userRepository.save(user);

        // Send password reset email so user can set their own password
        issuePasswordResetAndNotify(user);

        return AdminUserResponse.fromEntity(user);
    }

    @Transactional
    public AdminUserResponse updateUser(Long id, AdminUpdateUserRequest request) {
        UserEntity user = userRepository.findById(id).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));

        if (request.role() != null) {
            user.setRole(request.role());
        }
        if (request.status() != null) {
            user.setStatus(request.status());
        }
        if (request.currentWorkload() != null) {
            user.setCurrentWorkload(request.currentWorkload());
        }

        userRepository.save(user);
        return AdminUserResponse.fromEntity(user);
    }

    @Transactional
    public void deactivateUser(Long id) {
        UserEntity user = userRepository.findById(id).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));

        user.setStatus(UserEntity.UserStatus.DEACTIVATED);
        userRepository.save(user);
    }

    @Transactional
    public void resetPassword(Long id) {
        UserEntity user = userRepository.findById(id).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));

        String newPassword = generateRandomPassword();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Send password reset email so user can set a new password
        issuePasswordResetAndNotify(user);
    }

    private void issuePasswordResetAndNotify(UserEntity user) {
        passwordResetTokenRepository.deleteByUser(user);

        PasswordResetTokenEntity resetToken =
                PasswordResetTokenEntity.builder().user(user).token(UUID.randomUUID().toString())
                        .expiryDate(Instant.now().plusMillis(passwordResetExpirationMs)).used(false)
                        .build();

        PasswordResetTokenEntity savedToken = passwordResetTokenRepository.save(resetToken);

        try {
            emailService.sendPasswordResetEmail(user.getEmail(), savedToken.getToken());
        } catch (Exception ex) {
            log.error("Failed to send password reset email to {}", user.getEmail(), ex);
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
