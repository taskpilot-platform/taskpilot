package com.taskpilot.projects.common.entity;

import com.taskpilot.contracts.task.EvidenceStatus;
import com.taskpilot.contracts.task.TimelinessStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "task_evaluations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskEvaluationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "submission_id", nullable = false)
    private Long submissionId;

    @Column(name = "review_id", nullable = false)
    private Long reviewId;

    @Column(name = "assignee_id", nullable = false)
    private Long assigneeId;

    @Column(name = "reviewer_id", nullable = false)
    private Long reviewerId;

    @Column(name = "assignment_version", nullable = false)
    private Long assignmentVersion;

    @Column(name = "assignment_decision_id", length = 64)
    private String assignmentDecisionId;

    @Column(name = "quality_rating")
    private Integer qualityRating;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_status", nullable = false, length = 32)
    private EvidenceStatus evidenceStatus;

    @Column(name = "rubric_version", nullable = false, length = 32)
    private String rubricVersion;

    @Column(name = "accepted_submitted_at", nullable = false)
    private Instant acceptedSubmittedAt;

    @Column(name = "reviewed_at", nullable = false)
    private Instant reviewedAt;

    @Column(name = "original_completed_at", nullable = false)
    private Instant originalCompletedAt;

    @Column(name = "commitment_due_at")
    private Instant commitmentDueAt;

    @Column(name = "on_time")
    private Boolean onTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "timeliness_status", nullable = false, length = 32)
    private TimelinessStatus timelinessStatus;

    @Column(name = "evaluation_version", nullable = false)
    private Integer evaluationVersion;

    @Column(name = "supersedes_evaluation_id")
    private Long supersedesEvaluationId;

    @Builder.Default
    @Column(nullable = false)
    private Boolean invalidated = Boolean.FALSE;

    @Builder.Default
    @Column(name = "evidence_revision", nullable = false)
    private Long evidenceRevision = 1L;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
