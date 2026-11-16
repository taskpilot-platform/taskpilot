package com.taskpilot.users.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_lifecycle_recovery_outbox")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserLifecycleRecoveryOutboxEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RECONCILED = "RECONCILED";
    public static final String STATUS_FAILED_PERMANENT = "FAILED_PERMANENT";

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_version", nullable = false)
    private Long tokenVersion;

    @Column(name = "lifecycle", nullable = false, length = 32)
    private String lifecycle;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Builder.Default
    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_PENDING;

    @Builder.Default
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Builder.Default
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
