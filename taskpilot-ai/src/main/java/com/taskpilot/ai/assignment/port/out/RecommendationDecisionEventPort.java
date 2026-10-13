package com.taskpilot.ai.assignment.port.out;

import com.taskpilot.ai.entity.RecommendationDecisionEventEntity;

import java.util.List;
import java.util.Optional;

public interface RecommendationDecisionEventPort {

    RecommendationDecisionEventEntity save(RecommendationDecisionEventEntity event);

    Optional<RecommendationDecisionEventEntity> findBySnapshotId(String snapshotId);

    Optional<RecommendationDecisionEventEntity> findById(String decisionId);

    List<RecommendationDecisionEventEntity> findByProjectId(Long projectId);

    List<RecommendationDecisionEventEntity> findByTaskId(Long taskId);
}
