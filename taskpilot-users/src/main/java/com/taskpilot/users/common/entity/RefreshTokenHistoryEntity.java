package com.taskpilot.users.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Bounded consumed refresh-token hash lineage used strictly to detect refresh replay
 * against a specific session family ({@code session_id}) without storing raw tokens.
 */
@Entity
@Table(name = "refresh_token_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshTokenHistoryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "consumed_at", nullable = false)
    private Instant consumedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
