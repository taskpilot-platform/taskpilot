package com.taskpilot.projects.chat.dto;

import com.taskpilot.projects.common.entity.ProjectChatMessageEntity;
import lombok.Builder;

import java.time.Instant;

@Builder
public record ChatMessageResponse(
        Long id,
        Long projectId,
        Long senderId,
        String senderName,
        String senderEmail,
        String senderAvatarUrl,
        String content,
        String messageType,
        Long fileId,
        String fileName,
        Instant createdAt
) {
    public static ChatMessageResponse fromEntity(
            ProjectChatMessageEntity entity,
            String senderName,
            String senderEmail,
            String senderAvatarUrl,
            String fileName) {
        return ChatMessageResponse.builder()
                .id(entity.getId())
                .projectId(entity.getProjectId())
                .senderId(entity.getSenderId())
                .senderName(senderName)
                .senderEmail(senderEmail)
                .senderAvatarUrl(senderAvatarUrl)
                .content(entity.getContent())
                .messageType(entity.getMessageType())
                .fileId(entity.getFileId())
                .fileName(fileName)
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
