package com.taskpilot.users.profile.dto;

import com.taskpilot.users.entity.UserEntity;
import com.taskpilot.users.entity.UserEntity.UserRole;
import com.taskpilot.users.entity.UserEntity.UserStatus;
import java.time.Instant;

public record UserProfileResponseDTO(
        Long id,
        String email,
        String fullName,
        String avatarUrl,
        UserRole role,
        UserStatus status,
        Integer currentWorkload,
        Instant createdAt,
        Instant updatedAt
) {
    public static UserProfileResponseDTO fromEntity(UserEntity entity) {
        return new UserProfileResponseDTO(
                entity.getId(),
                entity.getEmail(),
                entity.getFullName(),
                entity.getAvatarUrl(),
                entity.getRole(),
                entity.getStatus(),
                entity.getCurrentWorkload(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
