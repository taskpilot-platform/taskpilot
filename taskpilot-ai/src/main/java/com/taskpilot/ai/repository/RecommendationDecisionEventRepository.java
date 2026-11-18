package com.taskpilot.ai.repository;

import com.taskpilot.ai.dto.RecommendationDecisionType;
import com.taskpilot.ai.entity.RecommendationDecisionEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Meta;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RecommendationDecisionEventRepository extends JpaRepository<RecommendationDecisionEventEntity, String> {

    Optional<RecommendationDecisionEventEntity> findBySnapshotId(String snapshotId);

    Optional<RecommendationDecisionEventEntity> findByDecidedByUserIdAndIdempotencyKey(
            Long decidedByUserId, String idempotencyKey);

    List<RecommendationDecisionEventEntity> findByTaskId(Long taskId);

    @Meta(comment = "RecommendationDecisionEventRepository.findScopedByDecisionType")
    @Query("""
            SELECT d FROM RecommendationDecisionEventEntity d
            JOIN RecommendationSnapshotEntity s ON s.snapshotId = d.snapshotId
            WHERE d.decisionType = :decisionType
              AND (:projectId IS NULL OR d.projectId = :projectId)
              AND (:managerId IS NULL OR d.decidedByUserId = :managerId)
              AND s.heuristicMode = :mode
            ORDER BY d.createdAt DESC, d.decisionId ASC
            """)
    List<RecommendationDecisionEventEntity> findScopedByDecisionType(
            @Param("decisionType") RecommendationDecisionType decisionType,
            @Param("projectId") Long projectId,
            @Param("managerId") Long managerId,
            @Param("mode") String mode,
            Pageable pageable);
}
