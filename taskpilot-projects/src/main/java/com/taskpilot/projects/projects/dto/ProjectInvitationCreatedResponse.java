package com.taskpilot.projects.projects.dto;

import java.time.Instant;

/**
 * One-time creation response returning the copyable raw invite token and URL once.
 */
public record ProjectInvitationCreatedResponse(
        Long id,
        Long projectId,
        String inviteeEmail,
        Instant issuedAt,
        Instant expiresAt,
        String inviteToken,
        String inviteUrl
) {}
