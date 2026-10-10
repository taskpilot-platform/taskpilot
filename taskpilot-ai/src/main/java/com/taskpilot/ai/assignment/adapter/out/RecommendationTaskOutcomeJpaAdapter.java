package com.taskpilot.ai.assignment.adapter.out;

import com.taskpilot.ai.assignment.port.out.RecommendationTaskOutcomePort;
import com.taskpilot.ai.entity.RecommendationTaskOutcomeEntity;
import com.taskpilot.ai.repository.RecommendationTaskOutcomeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RecommendationTaskOutcomeJpaAdapter implements RecommendationTaskOutcomePort {

    private final RecommendationTaskOutcomeRepository repository;

    @Override
    public RecommendationTaskOutcomeEntity save(RecommendationTaskOutcomeEntity outcome) {
        return repository.save(outcome);
    }

    @Override
    public Optional<RecommendationTaskOutcomeEntity> findById(String outcomeId) {
        return repository.findById(outcomeId);
    }

    @Override
    public Optional<RecommendationTaskOutcomeEntity> findByDecisionIdAndTaskId(String decisionId, Long taskId) {
        return repository.findByDecisionIdAndTaskId(decisionId, taskId);
    }

    @Override
    public Optional<RecommendationTaskOutcomeEntity> findBySnapshotId(String snapshotId) {
        return repository.findBySnapshotId(snapshotId);
    }

    @Override
    public List<RecommendationTaskOutcomeEntity> findByTaskId(Long taskId) {
        return repository.findByTaskIdOrderByObservedAtDesc(taskId);
    }

    @Override
    public List<RecommendationTaskOutcomeEntity> findByDecisionId(String decisionId) {
        return repository.findByDecisionIdOrderByObservedAtDesc(decisionId);
    }

    @Override
    public List<RecommendationTaskOutcomeEntity> findByProjectId(Long projectId) {
        return repository.findByProjectIdOrderByObservedAtDesc(projectId);
    }
}
