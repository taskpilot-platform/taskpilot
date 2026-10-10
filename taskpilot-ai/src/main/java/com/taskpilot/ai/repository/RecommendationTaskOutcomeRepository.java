package com.taskpilot.ai.repository;

import com.taskpilot.ai.entity.RecommendationTaskOutcomeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecommendationTaskOutcomeRepository extends JpaRepository<RecommendationTaskOutcomeEntity, String> {

    Optional<RecommendationTaskOutcomeEntity> findByDecisionIdAndTaskId(String decisionId, Long taskId);

    Optional<RecommendationTaskOutcomeEntity> findBySnapshotId(String snapshotId);

    List<RecommendationTaskOutcomeEntity> findByTaskIdOrderByObservedAtDesc(Long taskId);

    List<RecommendationTaskOutcomeEntity> findByDecisionIdOrderByObservedAtDesc(String decisionId);

    List<RecommendationTaskOutcomeEntity> findByProjectIdOrderByObservedAtDesc(Long projectId);
}
