package com.taskpilot.ai.repository;

import com.taskpilot.ai.dto.WeightProposalStatus;
import com.taskpilot.ai.entity.AdaptiveWeightProposalEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AdaptiveWeightProposalRepository extends JpaRepository<AdaptiveWeightProposalEntity, String> {

    List<AdaptiveWeightProposalEntity> findAllByOrderByCreatedAtDesc();

    Optional<AdaptiveWeightProposalEntity> findTopByOrderByCreatedAtDesc();

    List<AdaptiveWeightProposalEntity> findByStatusOrderByCreatedAtDesc(WeightProposalStatus status);
}
