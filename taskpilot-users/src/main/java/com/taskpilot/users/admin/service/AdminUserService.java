package com.taskpilot.users.admin.service;

import com.taskpilot.contracts.user.dto.SecurityChangeReason;
import com.taskpilot.infrastructure.config.security.CurrentActorResolver;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.util.PageableUtils;
import com.taskpilot.users.admin.dto.AdminCreateUserRequest;
import com.taskpilot.users.admin.dto.AdminUpdateUserRequest;
import com.taskpilot.users.admin.dto.AdminUserResponse;
import com.taskpilot.users.auth.service.EmailChallengeService;
import com.taskpilot.users.auth.service.UserLifecycleRecoveryOutboxDispatcher;
import com.taskpilot.users.auth.service.UserSessionService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

@Service
@Slf4j
public class AdminUserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final UserSessionService userSessionService;
    private final EmailChallengeService emailChallengeService;
    private final UserLifecycleRecoveryOutboxDispatcher outboxDispatcher;

    @Value("${application.security.password-reset.expiration:900000}")
    private long passwordResetExpirationMs = 900_000L;

    public AdminUserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            EmailService emailService,
            PasswordResetTokenRepository passwordResetTokenRepository,
            UserSessionService userSessionService) {
        this(userRepository, passwordEncoder, emailService, passwordResetTokenRepository, userSessionService,
                new EmailChallengeService(passwordResetTokenRepository, userRepository), null);
    }

    @Autowired
    public AdminUserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            EmailService emailService,
            PasswordResetTokenRepository passwordResetTokenRepository,
            UserSessionService userSessionService,
            EmailChallengeService emailChallengeService,
            UserLifecycleRecoveryOutboxDispatcher outboxDispatcher) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.userSessionService = userSessionService;
        this.emailChallengeService = emailChallengeService != null
                ? emailChallengeService
                : new EmailChallengeService(passwordResetTokenRepository, userRepository);
        this.outboxDispatcher = outboxDispatcher;
    }

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

        // Admin-provisioned accounts start unverified with no temporary password; owner verifies & chooses password.
        UserEntity user = UserEntity.builder()
                .email(email)
                .fullName(request.fullName().trim())
                .password(null)
                .role(request.role())
                .status(UserStatus.AVAILABLE)
                .accountState(AccountLifecycle.PENDING_EMAIL)
                .emailVerified(Boolean.FALSE)
                .emailVerificationProvenance("ADMIN_PROVISIONED_UNVERIFIED")
                .tokenVersion(0L)
                .build();

        user = userRepository.saveAndFlush(user);

        EmailChallengeService.IssuedChallenge challenge = emailChallengeService.issueForLockedUser(
                user, EmailTokenPurpose.EMAIL_VERIFY, passwordResetExpirationMs, null);
        try {
            emailService.sendEmailVerificationEmail(user.getEmail(), challenge.rawToken(), passwordResetExpirationMs);
        } catch (Exception ex) {
            log.error("Failed to send onboarding verification email to userId={}", user.getId(), ex);
        }

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

        if (request.status() == UserStatus.DEACTIVATED) {
            // Compatibility bridge: route DEACTIVATED status update to immediate admin suspension + outbox.
            if (request.role() != null && request.role() != user.getRole()) {
                user.setRole(request.role());
            }
            if (request.currentWorkload() != null) {
                user.setCurrentWorkload(request.currentWorkload());
            }
            return suspendUserInternal(user, currentUserId, "ADMIN_SUSPENDED");
        }

        // Availability edits (AVAILABLE/BUSY/OOO) must NEVER reactivate a suspended/deactivated/pending account.
        if (request.status() != null && user.getAccountState() != AccountLifecycle.ACTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Cannot change availability for non-active account. Use explicit reactivate action.");
        }

        boolean roleChanged = request.role() != null && request.role() != user.getRole();
        if (request.role() != null) {
            user.setRole(request.role());
        }
        if (request.status() != null) {
            user.setStatus(request.status());
        }
        if (request.currentWorkload() != null) {
            user.setCurrentWorkload(request.currentWorkload());
        }

        userRepository.saveAndFlush(user);

        if (roleChanged) {
            userSessionService.invalidateAll(user.getId(), SecurityChangeReason.ROLE_CHANGED);
        }

        return AdminUserResponse.fromEntity(user);
    }

    @Transactional
    public void deactivateUser(Long id) {
        suspendUser(id, "ADMIN_SUSPENDED");
    }

    @Transactional
    public AdminUserResponse suspendUser(Long id, String reason) {
        UserEntity user = userRepository.findByIdForUpdate(id).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));

        long currentUserId = CurrentActorResolver.userId(SecurityContextHolder.getContext().getAuthentication());
        if (user.getId().equals(currentUserId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Cannot deactivate your own account");
        }
        return suspendUserInternal(user, currentUserId,
                reason != null && !reason.isBlank() ? reason.trim() : "ADMIN_SUSPENDED");
    }

    @Transactional
    public AdminUserResponse reactivateUser(Long id, String reason) {
        UserEntity user = userRepository.findByIdForUpdate(id).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));

        long currentUserId = CurrentActorResolver.userId(SecurityContextHolder.getContext().getAuthentication());
        if (user.getAccountState() == AccountLifecycle.PENDING_EMAIL) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Pending email accounts must complete email verification instead of reactivation");
        }
        if (!Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Account email is not verified; require email verification before reactivation");
        }

        user.setAccountState(AccountLifecycle.ACTIVE);
        if (user.getStatus() == null || user.getStatus() == UserStatus.DEACTIVATED) {
            user.setStatus(UserStatus.AVAILABLE);
        }
        user.setLegacyReviewRequired(Boolean.FALSE);
        user.setLifecycleChangedAt(Instant.now());
        user.setLifecycleReason(reason != null && !reason.isBlank() ? reason.trim() : "ADMIN_REACTIVATED");
        user.setLifecycleChangedBy(currentUserId);
        userRepository.saveAndFlush(user);

        long nextVersion = userSessionService.invalidateAll(user.getId(), SecurityChangeReason.USER_RESTORED);
        if (outboxDispatcher != null) {
            outboxDispatcher.enqueueInCurrentTx(user.getId(), nextVersion, AccountLifecycle.ACTIVE.name());
        }
        return AdminUserResponse.fromEntity(user);
    }

    @Transactional
    public void resetPassword(Long id) {
        UserEntity user = userRepository.findByIdForUpdate(id).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));

        // Immediately clear existing password hash and invalidate sessions/tokens; never generate/mail temp password.
        user.setPassword(null);
        userRepository.saveAndFlush(user);

        userSessionService.invalidateAll(user.getId(), SecurityChangeReason.PASSWORD_RESET);

        // Reload locked user at new tokenVersion before issuing purpose-bound link.
        UserEntity updatedUser = userRepository.findByIdForUpdate(id).orElseThrow();
        EmailTokenPurpose purpose = Boolean.TRUE.equals(updatedUser.getEmailVerified())
                ? EmailTokenPurpose.PASSWORD_RESET
                : EmailTokenPurpose.EMAIL_VERIFY;
        EmailChallengeService.IssuedChallenge challenge = emailChallengeService.issueForLockedUser(
                updatedUser, purpose, passwordResetExpirationMs, null);

        try {
            if (purpose == EmailTokenPurpose.EMAIL_VERIFY) {
                emailService.sendEmailVerificationEmail(updatedUser.getEmail(), challenge.rawToken(), passwordResetExpirationMs);
            } else {
                emailService.sendPasswordResetEmail(updatedUser.getEmail(), challenge.rawToken(), passwordResetExpirationMs);
            }
        } catch (Exception ex) {
            log.error("Failed to send password reset/verify email to userId={}", updatedUser.getId(), ex);
        }
    }

    private AdminUserResponse suspendUserInternal(UserEntity user, long actorAdminId, String reason) {
        // Users-only suspension transaction: never acquires project locks or blocks on project guard.
        user.setStatus(UserStatus.DEACTIVATED);
        user.setAccountState(AccountLifecycle.ADMIN_SUSPENDED);
        user.setLifecycleChangedAt(Instant.now());
        user.setLifecycleReason(reason);
        user.setLifecycleChangedBy(actorAdminId);
        userRepository.saveAndFlush(user);

        long nextVersion = userSessionService.invalidateAll(user.getId(), SecurityChangeReason.USER_SUSPENDED);
        if (outboxDispatcher != null) {
            outboxDispatcher.enqueueInCurrentTx(user.getId(), nextVersion, AccountLifecycle.ADMIN_SUSPENDED.name());
        }
        return AdminUserResponse.fromEntity(user);
    }
}
