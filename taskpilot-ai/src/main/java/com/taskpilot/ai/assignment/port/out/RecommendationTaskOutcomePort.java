package com.taskpilot.ai.assignment.port.out;

import com.taskpilot.ai.entity.RecommendationTaskOutcomeEntity;

import java.util.List;
import java.util.Optional;

public interface RecommendationTaskOutcomePort {

    RecommendationTaskOutcomeEntity save(RecommendationTaskOutcomeEntity outcome);

    Optional<RecommendationTaskOutcomeEntity> findById(String outcomeId);

    Optional<RecommendationTaskOutcomeEntity> findByDecisionIdAndTaskId(String decisionId, Long taskId);

    Optional<RecommendationTaskOutcomeEntity> findBySnapshotId(String snapshotId);

    List<RecommendationTaskOutcomeEntity> findByTaskId(Long taskId);

    List<RecommendationTaskOutcomeEntity> findByDecisionId(String decisionId);

    List<RecommendationTaskOutcomeEntity> findByProjectId(Long projectId);
}
