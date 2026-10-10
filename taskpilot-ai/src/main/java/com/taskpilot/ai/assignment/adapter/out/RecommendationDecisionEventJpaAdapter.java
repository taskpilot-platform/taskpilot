package com.taskpilot.ai.assignment.adapter.out;

import com.taskpilot.ai.assignment.port.out.RecommendationDecisionEventPort;
import com.taskpilot.ai.entity.RecommendationDecisionEventEntity;
import com.taskpilot.ai.repository.RecommendationDecisionEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RecommendationDecisionEventJpaAdapter implements RecommendationDecisionEventPort {

    private final RecommendationDecisionEventRepository repository;

    @Override
    public RecommendationDecisionEventEntity save(RecommendationDecisionEventEntity event) {
        return repository.save(event);
    }

    @Override
    public Optional<RecommendationDecisionEventEntity> findBySnapshotId(String snapshotId) {
        return repository.findBySnapshotId(snapshotId);
    }

    @Override
    public Optional<RecommendationDecisionEventEntity> findById(String decisionId) {
        return repository.findById(decisionId);
    }

    @Override
    public List<RecommendationDecisionEventEntity> findByProjectId(Long projectId) {
        return repository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Override
    public List<RecommendationDecisionEventEntity> findByTaskId(Long taskId) {
        return repository.findByTaskIdOrderByCreatedAtDesc(taskId);
    }
}
