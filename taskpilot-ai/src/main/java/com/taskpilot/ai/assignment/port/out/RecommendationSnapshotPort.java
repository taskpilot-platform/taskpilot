package com.taskpilot.ai.assignment.port.out;

import com.taskpilot.ai.entity.RecommendationSnapshotEntity;

import java.util.List;
import java.util.Optional;

public interface RecommendationSnapshotPort {

    RecommendationSnapshotEntity save(RecommendationSnapshotEntity snapshot);

    Optional<RecommendationSnapshotEntity> findById(String snapshotId);

    List<RecommendationSnapshotEntity> findByProjectId(Long projectId);

    List<RecommendationSnapshotEntity> findByTaskId(Long taskId);
}
