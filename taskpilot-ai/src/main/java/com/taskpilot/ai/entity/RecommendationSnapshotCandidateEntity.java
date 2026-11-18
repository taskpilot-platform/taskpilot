package com.taskpilot.ai.entity;

import com.taskpilot.ai.dto.MetricDataStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

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

    @Column(name = "fit_source", length = 64)
    private String fitSource;

    @Column(name = "stored_workload_value")
    private Integer storedWorkloadValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "workload_status", nullable = false, length = 32)
    private MetricDataStatus workloadStatus;

    @Column(name = "workload_unit", length = 32)
    private String workloadUnit;

    @Column(name = "workload_scope", length = 32)
    private String workloadScope;

    @Column(name = "workload_measured_at")
    private Instant workloadMeasuredAt;

    @Column(name = "workload_revision", length = 64)
    private String workloadRevision;

    @Column(name = "derived_performance_input", nullable = false)
    private Double derivedPerformanceInput;

    @Enumerated(EnumType.STRING)
    @Column(name = "performance_status", nullable = false, length = 32)
    private MetricDataStatus performanceStatus;

    @Column(name = "performance_version", length = 32)
    private String performanceVersion;

    @Column(name = "performance_rubric_version", length = 32)
    private String performanceRubricVersion;

    @Column(name = "performance_sample_count")
    private Integer performanceSampleCount;

    @Column(name = "performance_window_limit")
    private Integer performanceWindowLimit;

    @Column(name = "observed_quality_mean")
    private Double observedQualityMean;

    @Column(name = "evidence_weight")
    private Double evidenceWeight;

    @Column(name = "performance_evidence_revision", length = 64)
    private String performanceEvidenceRevision;

    @Column(name = "performance_measured_at")
    private Instant performanceMeasuredAt;

    @Column(name = "member_status", nullable = false, length = 32)
    private String memberStatus;

    @Column(name = "selected_as_recommendation", nullable = false)
    private Boolean selectedAsRecommendation;
}
