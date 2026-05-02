package com.taskpilot.contracts.aiquery.dto;

public record ProjectMemberDto(
    Long memberId,
    String fullName,
    String role,
    Double performanceScore,
    String skills
) {}
