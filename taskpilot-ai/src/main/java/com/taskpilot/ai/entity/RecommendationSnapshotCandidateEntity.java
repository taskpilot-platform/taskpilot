package com.taskpilot.ai.entity;

import com.taskpilot.ai.dto.MetricDataStatus;
import jakarta.persistence.*;
import lombok.*;

/**
 * Normalized evidence row for each candidate evaluated in a recommendation snapshot.
 *
 * Strict Privacy & Security Invariant (Phase 2A):
 * Must NOT store email, displayName, confidenceScore, normalized per-criterion values,
 * LLM prompts, or mutable entity references.
 */
@Entity
@Table(name = "recommendation_snapshot_candidates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendationSnapshotCandidateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private RecommendationSnapshotEntity snapshot;

    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    @Column(name = "rank", nullable = false)
    private Integer rank;

    @Column(name = "ranking_key", nullable = false)
    private Long rankingKey;

    @Column(name = "full_precision_score", nullable = false)
    private Double fullPrecisionScore;

    @Column(name = "ranking_raw_fit", nullable = false)
    private Double rankingRawFit;

    @Column(name = "presentation_fit_value")
    private Double presentationFitValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "fit_status", nullable = false, length = 32)
    private MetricDataStatus fitStatus;

    @Column(name = "stored_workload_value")
    private Integer storedWorkloadValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "workload_status", nullable = false, length = 32)
    private MetricDataStatus workloadStatus;

    @Column(name = "derived_performance_input", nullable = false)
    private Double derivedPerformanceInput;

    @Enumerated(EnumType.STRING)
    @Column(name = "performance_status", nullable = false, length = 32)
    private MetricDataStatus performanceStatus;

    @Column(name = "member_status", nullable = false, length = 32)
    private String memberStatus;

    @Column(name = "selected_as_recommendation", nullable = false)
    private Boolean selectedAsRecommendation;
}
