package com.taskpilot.projects.projects.dto;

import com.taskpilot.projects.common.entity.ProjectInvitationEntity;

import java.time.Instant;

/**
 * Redacted invitation summary for manager listing (never includes raw token or token hash).
 */
public record ProjectInvitationResponse(
        Long id,
        Long projectId,
        String inviteeEmail,
        Long invitedByUserId,
        String status,
        Instant issuedAt,
        Instant expiresAt,
        Instant consumedAt,
        Instant revokedAt,
        String revocationReason
) {
    public static ProjectInvitationResponse fromEntity(ProjectInvitationEntity entity, Instant now) {
        return new ProjectInvitationResponse(
                entity.getId(),
                entity.getProjectId(),
                entity.getInviteeEmail(),
                entity.getInvitedByUserId(),
                entity.computeStatus(now),
                entity.getIssuedAt(),
                entity.getExpiresAt(),
                entity.getConsumedAt(),
                entity.getRevokedAt(),
                entity.getRevocationReason());
    }
}
