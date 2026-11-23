package com.taskpilot.ai.repository;

import com.taskpilot.ai.entity.RecommendationSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Meta;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RecommendationSnapshotRepository extends JpaRepository<RecommendationSnapshotEntity, String> {

    @Meta(comment = "RecommendationSnapshotRepository.findByIdWithCandidates")
    @Query("SELECT DISTINCT s FROM RecommendationSnapshotEntity s LEFT JOIN FETCH s.candidates WHERE s.snapshotId = :snapshotId")
    Optional<RecommendationSnapshotEntity> findByIdWithCandidates(@Param("snapshotId") String snapshotId);

    @Meta(comment = "RecommendationSnapshotRepository.findAllByIdInWithCandidates")
    @Query("SELECT DISTINCT s FROM RecommendationSnapshotEntity s LEFT JOIN FETCH s.candidates WHERE s.snapshotId IN :snapshotIds")
    List<RecommendationSnapshotEntity> findAllByIdInWithCandidates(@Param("snapshotIds") Collection<String> snapshotIds);
}
