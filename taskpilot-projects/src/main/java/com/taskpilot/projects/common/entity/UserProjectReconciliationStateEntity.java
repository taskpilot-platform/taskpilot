package com.taskpilot.projects.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_project_reconciliation_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProjectReconciliationStateEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "last_reconciled_token_version", nullable = false)
    private Long lastReconciledTokenVersion;

    @Column(name = "last_reconciled_lifecycle", nullable = false, length = 32)
    private String lastReconciledLifecycle;

    @Column(name = "last_event_id", nullable = false)
    private UUID lastEventId;

    @Column(name = "reconciled_at", nullable = false)
    private Instant reconciledAt;
}
