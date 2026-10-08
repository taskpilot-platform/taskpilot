package com.taskpilot.projects.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder
public record SendChatMessageRequest(
        @NotBlank(message = "Message content cannot be blank")
        String content,

        String messageType,

        Long fileId
) {
    public SendChatMessageRequest {
        if (messageType == null || messageType.isBlank()) {
            messageType = "TEXT";
        }
    }
}
