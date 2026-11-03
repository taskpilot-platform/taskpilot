package com.taskpilot.contracts.workspace.meeting;

public record TaskActionItemDto(
    int index,
    String title,
    String description,
    String assigneeName,
    Long assigneeId,
    boolean assigneeUnresolved,
    String dueDate,
    String priority,
    String sourceQuote
) {}
