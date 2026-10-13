package com.taskpilot.ai.entity;

import com.taskpilot.ai.dto.RecommendationRequestSource;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only immutable recommendation snapshot for Phase 2A.
 * Captures scoring weights, candidate evidence, and execution context.
 */
@Entity
@Table(name = "recommendation_snapshots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendationSnapshotEntity {

    public static final String DEFAULT_NORMALIZATION_CONTRACT = "neutral-min-max-fixed-point-v1";

    @Id
    @Column(name = "snapshot_id", nullable = false, length = 64)
    private String snapshotId;

    @Column(name = "requested_by_user_id", nullable = false)
    private Long requestedByUserId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "heuristic_mode", nullable = false, length = 32)
    private String heuristicMode;

    @Column(name = "presentation_contract_version", nullable = false, length = 64)
    private String presentationContractVersion;

    @Column(name = "scoring_model_version", nullable = false, length = 64)
    private String scoringModelVersion;

    @Column(name = "differentiation_status", nullable = false, length = 64)
    private String differentiationStatus;

    @Column(name = "required_skills", nullable = false, columnDefinition = "TEXT")
    private String requiredSkills;

    @Column(name = "recommended_candidate_id")
    private Long recommendedCandidateId;

    @Column(name = "candidate_count", nullable = false)
    private Integer candidateCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_source", nullable = false, length = 64)
    private RecommendationRequestSource requestSource;

    @Column(name = "fit_weight", nullable = false)
    private Double fitWeight;

    @Column(name = "load_weight", nullable = false)
    private Double loadWeight;

    @Column(name = "performance_weight", nullable = false)
    private Double performanceWeight;

    @Column(name = "normalization_contract", nullable = false, length = 64)
    private String normalizationContract;

    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("rank ASC")
    @Builder.Default
    private List<RecommendationSnapshotCandidateEntity> candidates = new ArrayList<>();

    public void addCandidate(RecommendationSnapshotCandidateEntity candidate) {
        if (this.candidates == null) {
            this.candidates = new ArrayList<>();
        }
        this.candidates.add(candidate);
        candidate.setSnapshot(this);
    }
}
