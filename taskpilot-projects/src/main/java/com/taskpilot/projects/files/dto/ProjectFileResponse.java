package com.taskpilot.projects.files.dto;

import com.taskpilot.projects.common.entity.ProjectFileEntity;
import lombok.Builder;

import java.time.Instant;

@Builder
public record ProjectFileResponse(
        Long id,
        Long projectId,
        Long uploaderId,
        String uploaderName,
        String uploaderEmail,
        String fileName,
        String originalName,
        Long fileSize,
        String contentType,
        String storageKey,
        String storageBucket,
        String description,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProjectFileResponse fromEntity(ProjectFileEntity entity, String uploaderName, String uploaderEmail) {
        return ProjectFileResponse.builder()
                .id(entity.getId())
                .projectId(entity.getProjectId())
                .uploaderId(entity.getUploaderId())
                .uploaderName(uploaderName)
                .uploaderEmail(uploaderEmail)
                .fileName(entity.getFileName())
                .originalName(entity.getOriginalName())
                .fileSize(entity.getFileSize())
                .contentType(entity.getContentType())
                .storageKey(entity.getStorageKey())
                .storageBucket(entity.getStorageBucket())
                .description(entity.getDescription())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
