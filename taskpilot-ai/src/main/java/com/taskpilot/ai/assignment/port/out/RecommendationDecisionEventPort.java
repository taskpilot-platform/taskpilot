package com.taskpilot.ai.assignment.port.out;

import com.taskpilot.ai.dto.RecommendationDecisionType;
import com.taskpilot.ai.entity.RecommendationDecisionEventEntity;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RecommendationDecisionEventPort {

    RecommendationDecisionEventEntity save(RecommendationDecisionEventEntity decision);

    Optional<RecommendationDecisionEventEntity> findById(String decisionId);

    Optional<RecommendationDecisionEventEntity> findBySnapshotId(String snapshotId);

    Optional<RecommendationDecisionEventEntity> findByDecidedByUserIdAndIdempotencyKey(
            Long decidedByUserId, String idempotencyKey);

    List<RecommendationDecisionEventEntity> findAllByIds(Collection<String> decisionIds);

    List<RecommendationDecisionEventEntity> findScopedByDecisionType(
            RecommendationDecisionType decisionType,
            Long projectId,
            Long managerId,
            String mode,
            Pageable pageable);
}
