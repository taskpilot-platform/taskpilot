package com.taskpilot.projects.projects.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProjectAttentionResponse(
        Long projectId,
        String projectName,
        String projectStatus,
        String reason,
        Instant since,
        UUID attentionEventId,
        List<EligibleMember> eligibleActiveMembers
) {
    public record EligibleMember(Long userId, String email, String fullName, String currentRole) {}
}
