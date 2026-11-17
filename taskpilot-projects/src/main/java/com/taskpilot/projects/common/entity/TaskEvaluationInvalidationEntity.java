package com.taskpilot.projects.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "task_evaluation_invalidations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskEvaluationInvalidationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "evaluation_id", nullable = false)
    private Long evaluationId;

    @Column(name = "evaluation_version", nullable = false)
    private Integer evaluationVersion;

    @Column(nullable = false, length = 64)
    private String reason;

    @Column(name = "detail_reason", columnDefinition = "TEXT")
    private String detailReason;

    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "evidence_revision", nullable = false)
    private Long evidenceRevision;
}
