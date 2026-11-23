package com.taskpilot.ai.repository;

import com.taskpilot.ai.entity.AdaptiveOutcomeInvalidationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AdaptiveOutcomeInvalidationRepository extends JpaRepository<AdaptiveOutcomeInvalidationEntity, UUID> {

    boolean existsByEventId(UUID eventId);

    Optional<AdaptiveOutcomeInvalidationEntity> findByEvaluationIdAndEvaluationVersion(
            Long evaluationId, Integer evaluationVersion);
}
