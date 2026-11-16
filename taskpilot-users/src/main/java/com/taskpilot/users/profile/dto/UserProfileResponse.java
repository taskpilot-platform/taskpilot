package com.taskpilot.users.profile.dto;

import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.enums.UserStatus;
import java.time.Instant;
import java.util.List;

public record UserProfileResponse(
        Long id,
        String email,
        String fullName,
        String avatarUrl,
        UserRole role,
        UserStatus status,
        Integer currentWorkload,
        Instant createdAt,
        Instant updatedAt,
        Boolean hasPassword,
        List<String> linkedProviders,
        String accountState,
        String availability,
        Boolean emailVerified,
        Boolean legacyReviewRequired,
        Boolean avatarRejected
) {
    public static UserProfileResponse fromEntity(UserEntity entity) {
        return fromEntity(entity, List.of());
    }

    public static UserProfileResponse fromEntity(UserEntity entity, List<String> linkedProviders) {
        return fromEntity(entity, linkedProviders, entity.getAvatarUrl(), false);
    }

    public static UserProfileResponse fromEntity(
            UserEntity entity,
            List<String> linkedProviders,
            String effectiveAvatarUrl,
            boolean avatarRejected) {
        boolean hasPassword = entity.hasUsablePassword();
        AccountLifecycle lifecycle = entity.getAccountState() != null
                ? entity.getAccountState()
                : AccountLifecycle.ACTIVE;
        UserStatus availabilityEnum = entity.effectiveAvailability();
        return new UserProfileResponse(
                entity.getId(),
                entity.getEmail(),
                entity.getFullName(),
                effectiveAvatarUrl,
                entity.getRole(),
                availabilityEnum,
                entity.getCurrentWorkload(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                hasPassword,
                linkedProviders != null ? linkedProviders : List.of(),
                lifecycle.name(),
                availabilityEnum.name(),
                Boolean.TRUE.equals(entity.getEmailVerified()),
                Boolean.TRUE.equals(entity.getLegacyReviewRequired()),
                avatarRejected
        );
    }
}
