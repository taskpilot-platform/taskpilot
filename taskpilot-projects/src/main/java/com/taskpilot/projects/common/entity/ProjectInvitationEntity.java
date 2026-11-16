package com.taskpilot.projects.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "project_invitations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectInvitationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "invitee_email", nullable = false, length = 255)
    private String inviteeEmail;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "invited_by_user_id", nullable = false)
    private Long invitedByUserId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "consumed_by_user_id")
    private Long consumedByUserId;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by_user_id")
    private Long revokedByUserId;

    @Column(name = "revocation_reason", length = 64)
    private String revocationReason;

    @Version
    @Builder.Default
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    public String computeStatus(Instant now) {
        if (consumedAt != null) {
            return "ACCEPTED";
        }
        if (revokedAt != null) {
            return "REVOKED";
        }
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            return "EXPIRED";
        }
        return "PENDING";
    }
}
