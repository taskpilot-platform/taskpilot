package com.taskpilot.ai.assignment.adapter.out;

import com.taskpilot.ai.assignment.port.out.RecommendationSnapshotPort;
import com.taskpilot.ai.entity.RecommendationSnapshotEntity;
import com.taskpilot.ai.repository.RecommendationSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RecommendationSnapshotJpaAdapter implements RecommendationSnapshotPort {

    private final RecommendationSnapshotRepository repository;

    @Override
    public RecommendationSnapshotEntity save(RecommendationSnapshotEntity snapshot) {
        return repository.saveAndFlush(snapshot);
    }

    @Override
    public Optional<RecommendationSnapshotEntity> findById(String snapshotId) {
        return repository.findByIdWithCandidates(snapshotId);
    }

    @Override
    public List<RecommendationSnapshotEntity> findAllByIds(Collection<String> snapshotIds) {
        if (snapshotIds == null || snapshotIds.isEmpty()) {
            return List.of();
        }
        return repository.findAllByIdInWithCandidates(snapshotIds);
    }
}
