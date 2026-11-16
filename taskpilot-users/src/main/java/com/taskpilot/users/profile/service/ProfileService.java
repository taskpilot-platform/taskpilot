package com.taskpilot.users.profile.service;

import com.taskpilot.contracts.user.dto.SecurityChangeReason;
import com.taskpilot.infrastructure.config.security.CurrentActorResolver;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.storage.StorageService;
import com.taskpilot.users.auth.service.PasswordPolicy;
import com.taskpilot.users.auth.service.SelfDeactivationCoordinator;
import com.taskpilot.users.auth.service.UserSessionService;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.UserStatus;
import com.taskpilot.users.common.repository.UserIdentityRepository;
import com.taskpilot.users.common.repository.UserRepository;
import com.taskpilot.users.profile.dto.ChangePasswordRequest;
import com.taskpilot.users.profile.dto.SelfDeactivateRequest;
import com.taskpilot.users.profile.dto.UpdateAvailabilityRequest;
import com.taskpilot.users.profile.dto.UpdateProfileRequest;
import com.taskpilot.users.profile.dto.UserProfileResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;

@Service
public class ProfileService {

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserSessionService userSessionService;
    private final StorageService storageService;
    private final PasswordPolicy passwordPolicy;
    private final AvatarValidator avatarValidator;
    private final SelfDeactivationCoordinator selfDeactivationCoordinator;

    public ProfileService(
            UserRepository userRepository,
            UserIdentityRepository userIdentityRepository,
            PasswordEncoder passwordEncoder,
            UserSessionService userSessionService,
            StorageService storageService) {
        this(userRepository, userIdentityRepository, passwordEncoder, userSessionService, storageService,
                new PasswordPolicy(), new AvatarValidator(), null);
    }

    @Autowired
    public ProfileService(
            UserRepository userRepository,
            UserIdentityRepository userIdentityRepository,
            PasswordEncoder passwordEncoder,
            UserSessionService userSessionService,
            StorageService storageService,
            PasswordPolicy passwordPolicy,
            AvatarValidator avatarValidator,
            SelfDeactivationCoordinator selfDeactivationCoordinator) {
        this.userRepository = userRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.passwordEncoder = passwordEncoder;
        this.userSessionService = userSessionService;
        this.storageService = storageService;
        this.passwordPolicy = passwordPolicy != null ? passwordPolicy : new PasswordPolicy();
        this.avatarValidator = avatarValidator != null ? avatarValidator : new AvatarValidator();
        this.selfDeactivationCoordinator = selfDeactivationCoordinator;
    }

    private long getCurrentUserId() {
        return CurrentActorResolver.userId(SecurityContextHolder.getContext().getAuthentication());
    }

    private UserEntity getCurrentUser() {
        long userId = getCurrentUserId();
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile() {
        UserEntity user = getCurrentUser();
        List<String> linkedProviders = userIdentityRepository.findByUserId(user.getId())
                .stream()
                .map(identity -> identity.getProvider().name().toLowerCase(Locale.ROOT))
                .toList();
        String storedAvatar = user.getAvatarUrl();
        boolean avatarRejected = storedAvatar != null && !storedAvatar.isBlank()
                && !avatarValidator.isPermittedAvatarUrl(storedAvatar);
        String effectiveAvatar = avatarRejected ? null : storedAvatar;
        return UserProfileResponse.fromEntity(user, linkedProviders, effectiveAvatar, avatarRejected);
    }

    @Transactional
    public UserProfileResponse updateProfile(UpdateProfileRequest request) {
        UserEntity user = userRepository.findByIdForUpdate(getCurrentUserId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));
        user.setFullName(request.fullName().trim());
        if (request.avatarUrl() != null) {
            user.setAvatarUrl(avatarValidator.validateAndNormalizeProfileAvatarUrl(request.avatarUrl()));
        }
        userRepository.save(user);
        return getProfile();
    }

    @Transactional
    public UserProfileResponse updateAvailability(UpdateAvailabilityRequest request) {
        UserStatus availability = parseAvailability(request.availability());
        UserEntity user = userRepository.findByIdForUpdate(getCurrentUserId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));
        if (!user.isActiveAccount()) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Account is not active");
        }
        user.setStatus(availability);
        userRepository.save(user);
        return getProfile();
    }

    @Transactional
    public UserProfileResponse uploadAvatar(MultipartFile file) {
        avatarValidator.validateUploadedImage(file);
        try {
            String avatarUrl = storageService.uploadFile(file, "avatars", "avatars");
            UserEntity user = userRepository.findByIdForUpdate(getCurrentUserId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));
            user.setAvatarUrl(avatarUrl);
            userRepository.save(user);
            return getProfile();
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    "Failed to upload avatar: " + e.getMessage());
        }
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        passwordPolicy.validate(request.newPassword());

        long userId = getCurrentUserId();
        UserEntity user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));

        if (!user.hasUsablePassword()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "OAuth-only accounts must set an initial password via verified email setup link");
        }
        if (request.oldPassword() == null || request.oldPassword().isBlank()
                || !passwordEncoder.matches(request.oldPassword(), user.getPassword())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Old password does not match");
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        userSessionService.invalidateAll(user.getId(), SecurityChangeReason.PASSWORD_CHANGED);
    }

    /**
     * Nontransactional entry point for self-deactivation. Delegates directly to the outer
     * {@link SelfDeactivationCoordinator} without creating an enclosing transaction.
     */
    public void selfDeactivate(SelfDeactivateRequest request) {
        if (selfDeactivationCoordinator == null) {
            throw new IllegalStateException("SelfDeactivationCoordinator is required");
        }
        if (request == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Explicit self-deactivation confirmation and credential proof are required");
        }
        long userId = getCurrentUserId();
        selfDeactivationCoordinator.selfDeactivate(userId, request);
    }

    private static UserStatus parseAvailability(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Availability is required");
        }
        try {
            UserStatus parsed = UserStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            if (parsed == UserStatus.DEACTIVATED) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "DEACTIVATED cannot be selected as an availability state");
            }
            return parsed;
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid availability value");
        }
    }
}
