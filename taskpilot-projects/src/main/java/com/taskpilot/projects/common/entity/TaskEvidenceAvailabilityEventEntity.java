package com.taskpilot.projects.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "task_evidence_availability_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskEvidenceAvailabilityEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "evidence_ref_id", nullable = false)
    private Long evidenceRefId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "original_file_id", nullable = false)
    private Long originalFileId;

    @Column(nullable = false)
    private Boolean available;

    @Column(nullable = false, length = 64)
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_user_id")
    private Long actorUserId;
}
