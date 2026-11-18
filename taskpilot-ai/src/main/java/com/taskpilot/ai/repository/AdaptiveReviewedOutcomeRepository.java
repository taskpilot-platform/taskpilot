package com.taskpilot.ai.repository;

import com.taskpilot.ai.entity.AdaptiveReviewedOutcomeEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Meta;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AdaptiveReviewedOutcomeRepository extends JpaRepository<AdaptiveReviewedOutcomeEntity, String> {

    boolean existsBySourceEventId(UUID sourceEventId);

    Optional<AdaptiveReviewedOutcomeEntity> findByEvaluationIdAndEvaluationVersion(
            Long evaluationId, Integer evaluationVersion);

    List<AdaptiveReviewedOutcomeEntity> findByTaskIdOrderByEvaluationVersionDesc(Long taskId);

    @Meta(comment = "AdaptiveReviewedOutcomeRepository.findScopedOutcomes")
    @Query("""
            SELECT o FROM AdaptiveReviewedOutcomeEntity o
            JOIN RecommendationDecisionEventEntity d ON d.decisionId = o.decisionId
            JOIN RecommendationSnapshotEntity s ON s.snapshotId = d.snapshotId
            WHERE (:projectId IS NULL OR o.projectId = :projectId)
              AND (:managerId IS NULL OR d.decidedByUserId = :managerId)
              AND s.heuristicMode = :mode
            ORDER BY o.taskId ASC, o.evaluationId ASC, o.evaluationVersion ASC, o.outcomeId ASC
            """)
    List<AdaptiveReviewedOutcomeEntity> findScopedOutcomes(
            @Param("projectId") Long projectId,
            @Param("managerId") Long managerId,
            @Param("mode") String mode,
            Pageable pageable);

    @Meta(comment = "AdaptiveReviewedOutcomeRepository.findUnlinkedOutcomes")
    @Query("""
            SELECT o FROM AdaptiveReviewedOutcomeEntity o
            WHERE (:projectId IS NULL OR o.projectId = :projectId)
              AND o.decisionId IS NULL
            ORDER BY o.taskId ASC, o.evaluationId ASC, o.evaluationVersion ASC, o.outcomeId ASC
            """)
    List<AdaptiveReviewedOutcomeEntity> findUnlinkedOutcomes(
            @Param("projectId") Long projectId,
            Pageable pageable);
}
