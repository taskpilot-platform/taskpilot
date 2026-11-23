package com.taskpilot.ai.repository;

import com.taskpilot.ai.entity.RecommendationTaskOutcomeEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Meta;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecommendationTaskOutcomeRepository extends JpaRepository<RecommendationTaskOutcomeEntity, String> {

    @Meta(comment = "RecommendationTaskOutcomeRepository.findScopedLegacyOutcomes")
    @Query("""
            SELECT o FROM RecommendationTaskOutcomeEntity o
            WHERE (:projectId IS NULL OR o.projectId = :projectId)
            ORDER BY o.observedAt DESC, o.outcomeId ASC
            """)
    List<RecommendationTaskOutcomeEntity> findScopedLegacyOutcomes(
            @Param("projectId") Long projectId,
            Pageable pageable);
}
