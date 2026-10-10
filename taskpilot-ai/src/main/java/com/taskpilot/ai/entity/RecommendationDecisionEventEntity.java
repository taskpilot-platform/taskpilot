package com.taskpilot.ai.entity;

import com.taskpilot.ai.dto.RecommendationDecisionSource;
import com.taskpilot.ai.dto.RecommendationDecisionType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Append-only immutable PM decision event for Phase 2B.
 * Tracks terminal PM action decisions linked to Phase 2A recommendation snapshots.
 */
@Entity
@Table(name = "recommendation_decision_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendationDecisionEventEntity {

    @Id
    @Column(name = "decision_id", nullable = false, length = 64)
    private String decisionId;

    @Column(name = "snapshot_id", nullable = false, unique = true, length = 64)
    private String snapshotId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "decided_by_user_id")
    private Long decidedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_source", nullable = false, length = 16)
    private RecommendationDecisionSource decisionSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_type", nullable = false, length = 32)
    private RecommendationDecisionType decisionType;

    @Column(name = "recommended_candidate_id")
    private Long recommendedCandidateId;

    @Column(name = "selected_candidate_id")
    private Long selectedCandidateId;

    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
