package com.taskpilot.ai.repository;

import com.taskpilot.ai.entity.RecommendationSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RecommendationSnapshotRepository extends JpaRepository<RecommendationSnapshotEntity, String> {

    @Query("SELECT s FROM RecommendationSnapshotEntity s LEFT JOIN FETCH s.candidates WHERE s.snapshotId = :snapshotId")
    Optional<RecommendationSnapshotEntity> findWithCandidatesBySnapshotId(@Param("snapshotId") String snapshotId);

    List<RecommendationSnapshotEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    List<RecommendationSnapshotEntity> findByTaskIdOrderByCreatedAtDesc(Long taskId);
}
