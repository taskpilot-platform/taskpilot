package com.taskpilot.ai.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "adaptive_reviewed_outcomes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdaptiveReviewedOutcomeEntity {

    public static final String OUTCOME_VERSION_V2 = "reviewed-outcome-v2";

    @Id
    @Column(name = "outcome_id", nullable = false, length = 64)
    private String outcomeId;

    @Column(name = "source_event_id", nullable = false, unique = true)
    private UUID sourceEventId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "submission_id", nullable = false)
    private Long submissionId;

    @Column(name = "evaluation_id", nullable = false)
    private Long evaluationId;

    @Column(name = "evaluation_version", nullable = false)
    private Integer evaluationVersion;

    @Column(name = "assignment_version", nullable = false)
    private Long assignmentVersion;

    @Column(name = "assignee_id", nullable = false)
    private Long assigneeId;

    @Column(name = "reviewer_id", nullable = false)
    private Long reviewerId;

    @Column(name = "decision_id", length = 64)
    private String decisionId;

    @Column(name = "snapshot_id", length = 64)
    private String snapshotId;

    @Column(name = "evidence_status", nullable = false, length = 32)
    private String evidenceStatus;

    @Column(name = "quality_rating")
    private Integer qualityRating;

    @Column(name = "rubric_version", nullable = false, length = 32)
    private String rubricVersion;

    @Column(name = "accepted_submitted_at", nullable = false)
    private Instant acceptedSubmittedAt;

    @Column(name = "reviewed_at", nullable = false)
    private Instant reviewedAt;

    @Column(name = "commitment_due_at")
    private Instant commitmentDueAt;

    @Column(name = "on_time")
    private Boolean onTime;

    @Column(name = "timeliness_status", nullable = false, length = 32)
    private String timelinessStatus;

    @Column(name = "evidence_revision", nullable = false)
    private Long evidenceRevision;

    @Builder.Default
    @Column(name = "invalidated", nullable = false)
    private Boolean invalidated = Boolean.FALSE;

    @Column(name = "invalidation_reason", length = 64)
    private String invalidationReason;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @Column(name = "invalidation_event_id")
    private UUID invalidationEventId;

    @Column(name = "invalidated_evidence_revision")
    private Long invalidatedEvidenceRevision;

    @Builder.Default
    @Column(name = "synthetic", nullable = false)
    private Boolean synthetic = Boolean.FALSE;

    @Builder.Default
    @Column(name = "outcome_version", nullable = false, length = 32)
    private String outcomeVersion = OUTCOME_VERSION_V2;

    @Column(name = "projected_at", nullable = false)
    private Instant projectedAt;
}
