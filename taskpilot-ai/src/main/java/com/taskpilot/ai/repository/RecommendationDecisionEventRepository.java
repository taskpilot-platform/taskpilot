package com.taskpilot.ai.repository;

import com.taskpilot.ai.entity.RecommendationDecisionEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecommendationDecisionEventRepository extends JpaRepository<RecommendationDecisionEventEntity, String> {

    Optional<RecommendationDecisionEventEntity> findBySnapshotId(String snapshotId);

    List<RecommendationDecisionEventEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    List<RecommendationDecisionEventEntity> findByTaskIdOrderByCreatedAtDesc(Long taskId);
}
