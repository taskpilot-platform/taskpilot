package com.taskpilot.contracts.workspace.chat;

import java.time.Instant;

public record ChatMessageSummaryDto(
    Long id,
    Long senderId,
    String senderName,
    String content,
    String messageType,
    Long fileId,
    String fileName,
    Instant createdAt
) {}
