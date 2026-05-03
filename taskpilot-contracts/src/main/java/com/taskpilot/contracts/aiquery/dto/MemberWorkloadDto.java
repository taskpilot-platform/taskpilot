package com.taskpilot.contracts.aiquery.dto;

public record MemberWorkloadDto(
    Long memberId,
    String fullName,
    int openTasks,
    int overdueTasks,
    int activeWorkloadScore
) {}
