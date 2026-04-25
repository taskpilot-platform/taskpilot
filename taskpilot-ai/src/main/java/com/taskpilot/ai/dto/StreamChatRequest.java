package com.taskpilot.ai.dto;

import jakarta.validation.constraints.NotBlank;

public record StreamChatRequest(
                @NotBlank(message = "message must not be blank") String message,
                String clientMessageId) {
}