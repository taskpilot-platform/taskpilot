package com.taskpilot.ai.assignment.adapter.out;

import com.taskpilot.ai.assignment.port.out.RecommendationSnapshotPort;
import com.taskpilot.ai.entity.RecommendationSnapshotEntity;
import com.taskpilot.ai.repository.RecommendationSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RecommendationSnapshotJpaAdapter implements RecommendationSnapshotPort {

    private final RecommendationSnapshotRepository snapshotRepository;

    @Override
    public RecommendationSnapshotEntity save(RecommendationSnapshotEntity snapshot) {
        return snapshotRepository.save(snapshot);
    }

    @Override
    public Optional<RecommendationSnapshotEntity> findById(String snapshotId) {
        return snapshotRepository.findWithCandidatesBySnapshotId(snapshotId);
    }

    @Override
    public List<RecommendationSnapshotEntity> findByProjectId(Long projectId) {
        return snapshotRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Override
    public List<RecommendationSnapshotEntity> findByTaskId(Long taskId) {
        return snapshotRepository.findByTaskIdOrderByCreatedAtDesc(taskId);
    }
}
