package com.taskpilot.projects.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "task_submissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskSubmissionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "cycle_no", nullable = false)
    private Integer cycleNo;

    @Column(name = "assignment_version", nullable = false)
    private Long assignmentVersion;

    @Column(name = "submitter_id", nullable = false)
    private Long submitterId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "commitment_due_at")
    private Instant commitmentDueAt;

    @Column(name = "due_commitment_revision", nullable = false)
    private Long dueCommitmentRevision;

    @Column(name = "criteria_revision", nullable = false)
    private Long criteriaRevision;

    @Column(name = "project_timezone_id", nullable = false, length = 64)
    private String projectTimezoneId;

    @Column(name = "criteria_snapshot_json", columnDefinition = "TEXT")
    private String criteriaSnapshotJson;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "withdrawal_reason", columnDefinition = "TEXT")
    private String withdrawalReason;

    @Column(name = "withdrawn_by_user_id")
    private Long withdrawnByUserId;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @Column(name = "invalidation_reason", length = 64)
    private String invalidationReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
