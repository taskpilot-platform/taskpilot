package com.taskpilot.ai.assignment.adapter.out;

import com.taskpilot.ai.assignment.port.out.RecommendationDecisionEventPort;
import com.taskpilot.ai.dto.RecommendationDecisionType;
import com.taskpilot.ai.entity.RecommendationDecisionEventEntity;
import com.taskpilot.ai.repository.RecommendationDecisionEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RecommendationDecisionEventJpaAdapter implements RecommendationDecisionEventPort {

    private final RecommendationDecisionEventRepository repository;

    @Override
    public RecommendationDecisionEventEntity save(RecommendationDecisionEventEntity decision) {
        return repository.saveAndFlush(decision);
    }

    @Override
    public Optional<RecommendationDecisionEventEntity> findById(String decisionId) {
        return repository.findById(decisionId);
    }

    @Override
    public Optional<RecommendationDecisionEventEntity> findBySnapshotId(String snapshotId) {
        return repository.findBySnapshotId(snapshotId);
    }

    @Override
    public Optional<RecommendationDecisionEventEntity> findByDecidedByUserIdAndIdempotencyKey(
            Long decidedByUserId, String idempotencyKey) {
        return repository.findByDecidedByUserIdAndIdempotencyKey(decidedByUserId, idempotencyKey);
    }

    @Override
    public List<RecommendationDecisionEventEntity> findAllByIds(Collection<String> decisionIds) {
        if (decisionIds == null || decisionIds.isEmpty()) {
            return List.of();
        }
        return repository.findAllById(decisionIds);
    }

    @Override
    public List<RecommendationDecisionEventEntity> findScopedByDecisionType(
            RecommendationDecisionType decisionType,
            Long projectId,
            Long managerId,
            String mode,
            Pageable pageable) {
        return repository.findScopedByDecisionType(decisionType, projectId, managerId, mode, pageable);
    }
}
