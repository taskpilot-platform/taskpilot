package com.taskpilot.ai.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "adaptive_outcome_invalidations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdaptiveOutcomeInvalidationEntity {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "evaluation_id", nullable = false)
    private Long evaluationId;

    @Column(name = "evaluation_version", nullable = false)
    private Integer evaluationVersion;

    @Column(name = "reason", nullable = false, length = 64)
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "evidence_revision", nullable = false)
    private Long evidenceRevision;
}
