package com.taskpilot.ai.entity;

import com.taskpilot.ai.dto.WeightProposalStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Immutable audit entity for offline shadow weight proposals in Phase 2F.
 * Stores proposal lineage, dataset hash, evaluation metrics, and review trail.
 */
@Entity
@Table(name = "adaptive_weight_proposals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdaptiveWeightProposalEntity {

    @Id
    @Column(name = "proposal_id", nullable = false, length = 64)
    private String proposalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private WeightProposalStatus status;

    @Column(name = "target_heuristic_mode", length = 32)
    private String targetHeuristicMode;

    @Column(name = "dataset_version", nullable = false, length = 32)
    private String datasetVersion;

    @Column(name = "dataset_hash", nullable = false, length = 64)
    private String datasetHash;

    @Column(name = "dataset_sample_count", nullable = false)
    private Integer datasetSampleCount;

    @Column(name = "current_fit_weight", nullable = false)
    private Double currentFitWeight;

    @Column(name = "current_load_weight", nullable = false)
    private Double currentLoadWeight;

    @Column(name = "current_performance_weight", nullable = false)
    private Double currentPerformanceWeight;

    @Column(name = "current_adaptive_fit_weight")
    private Double currentAdaptiveFitWeight;

    @Column(name = "current_adaptive_load_weight")
    private Double currentAdaptiveLoadWeight;

    @Column(name = "proposed_fit_weight")
    private Double proposedFitWeight;

    @Column(name = "proposed_load_weight")
    private Double proposedLoadWeight;

    @Column(name = "proposed_performance_weight")
    private Double proposedPerformanceWeight;

    @Column(name = "grid_step")
    private Double gridStep;

    @Column(name = "current_metric_historical_agreement")
    private Double currentMetricHistoricalAgreement;

    @Column(name = "current_metric_on_time_association")
    private Double currentMetricOnTimeAssociation;

    @Column(name = "metric_historical_agreement")
    private Double metricHistoricalAgreement;

    @Column(name = "metric_on_time_association")
    private Double metricOnTimeAssociation;

    @Column(name = "evaluation_window_start")
    private Instant evaluationWindowStart;

    @Column(name = "evaluation_window_end")
    private Instant evaluationWindowEnd;

    @Column(name = "algorithm_version", nullable = false, length = 32)
    private String algorithmVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by_user_id")
    private Long reviewedByUserId;

    @Column(name = "review_notes", length = 1000)
    private String reviewNotes;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Column(name = "proposal_version", nullable = false, length = 32)
    private String proposalVersion;
}
