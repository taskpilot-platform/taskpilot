package com.taskpilot.contracts.workspace.chat;

import java.time.Instant;

public record ChatSearchCriteria(
    String keyword,
    String senderName,
    Instant from,
    Instant to,
    int limit
) {}
