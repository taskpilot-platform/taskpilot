package com.taskpilot.ai.entity;

import com.taskpilot.ai.dto.RecommendationDecisionSource;
import com.taskpilot.ai.dto.RecommendationDecisionType;
import com.taskpilot.ai.dto.TaskOutcomeType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Append-only immutable task outcome entity for Phase 2D.
 * Links recommendation snapshot -> PM terminal decision -> assigned task -> observed completion outcome.
 */
@Entity
@Table(name = "recommendation_task_outcomes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendationTaskOutcomeEntity {

    @Id
    @Column(name = "outcome_id", nullable = false, length = 64)
    private String outcomeId;

    @Column(name = "snapshot_id", nullable = false, length = 64)
    private String snapshotId;

    @Column(name = "decision_id", nullable = false, length = 64)
    private String decisionId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "recommended_candidate_id")
    private Long recommendedCandidateId;

    @Column(name = "selected_candidate_id")
    private Long selectedCandidateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_type", nullable = false, length = 32)
    private RecommendationDecisionType decisionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_source", nullable = false, length = 16)
    private RecommendationDecisionSource decisionSource;

    @Column(name = "observed_assignee_id")
    private Long observedAssigneeId;

    @Column(name = "task_status", nullable = false, length = 32)
    private String taskStatus;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome_type", nullable = false, length = 32)
    private TaskOutcomeType outcomeType;

    @Column(name = "exclusion_reason", length = 64)
    private String exclusionReason;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "outcome_version", nullable = false, length = 32)
    @Builder.Default
    private String outcomeVersion = "outcome-v1";
}
