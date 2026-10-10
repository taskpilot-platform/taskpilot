package com.taskpilot.ai.service;

import com.taskpilot.ai.assignment.port.out.RecommendationSnapshotPort;
import com.taskpilot.ai.dto.InternalCandidateRanking;
import com.taskpilot.ai.dto.MetricDataStatus;
import com.taskpilot.ai.dto.RecommendationDifferentiationStatus;
import com.taskpilot.ai.dto.RecommendationRequestSource;
import com.taskpilot.ai.dto.RecommendationView;
import com.taskpilot.ai.entity.RecommendationSnapshotCandidateEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotEntity;
import com.taskpilot.ai.heuristic.HeuristicStrategy;
import com.taskpilot.ai.heuristic.HeuristicWeights;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Append-only recommendation snapshot service for Phase 2A.
 * Captures scoring weights, candidate evidence, and execution context.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationSnapshotService {

    private final RecommendationSnapshotPort snapshotPort;
    private final ProjectMemberPort projectMemberPort;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RecommendationSnapshotEntity createSnapshot(
            Long requestingUserId,
            Long projectId,
            Long taskId,
            List<String> requiredSkills,
            String heuristicMode,
            HeuristicStrategy strategy,
            RecommendationDifferentiationStatus differentiationStatus,
            List<InternalCandidateRanking> internalRankings,
            RecommendationRequestSource requestSource) {

        if (projectId == null) {
            throw new IllegalArgumentException("Project ID is required for recommendation snapshot");
        }
        if (requestingUserId == null) {
            throw new IllegalArgumentException("Requesting user ID is required for recommendation snapshot");
        }
        if (requestSource == null) {
            throw new IllegalArgumentException("Request source is required for recommendation snapshot");
        }

        String snapshotId = "rec-snap-" + UUID.randomUUID();
        Instant now = Instant.now();

        HeuristicWeights weights = strategy != null ? strategy.weights() : null;
        double fitWeight = weights != null ? weights.fitWeight() : 0.0;
        double loadWeight = weights != null ? weights.loadWeight() : 0.0;
        double perfWeight = weights != null ? weights.performanceWeight() : 0.0;

        List<InternalCandidateRanking> safeRankings = internalRankings == null ? Collections.emptyList() : internalRankings;
        int candidateCount = safeRankings.size();

        RecommendationDifferentiationStatus safeDiffStatus = differentiationStatus != null
                ? differentiationStatus
                : RecommendationDifferentiationStatus.UNKNOWN;

        Long recommendedCandidateId = !safeRankings.isEmpty() ? safeRankings.get(0).userId() : null;

        String skillsString = (requiredSkills != null && !requiredSkills.isEmpty())
                ? String.join(",", requiredSkills)
                : "";

        RecommendationSnapshotEntity snapshot = RecommendationSnapshotEntity.builder()
                .snapshotId(snapshotId)
                .requestedByUserId(requestingUserId)
                .projectId(projectId)
                .taskId(taskId)
                .createdAt(now)
                .heuristicMode(heuristicMode != null ? heuristicMode : (strategy != null ? strategy.mode() : "UNKNOWN"))
                .presentationContractVersion(RecommendationView.PRESENTATION_CONTRACT_VERSION)
                .scoringModelVersion(RecommendationView.SCORING_MODEL_VERSION)
                .differentiationStatus(safeDiffStatus.name())
                .requiredSkills(skillsString)
                .recommendedCandidateId(recommendedCandidateId)
                .candidateCount(candidateCount)
                .requestSource(requestSource)
                .fitWeight(fitWeight)
                .loadWeight(loadWeight)
                .performanceWeight(perfWeight)
                .normalizationContract(RecommendationSnapshotEntity.DEFAULT_NORMALIZATION_CONTRACT)
                .candidates(new ArrayList<>())
                .build();

        int rank = 1;
        for (InternalCandidateRanking ranking : safeRankings) {
            boolean selectedAsRecommendation = (rank == 1);

            RecommendationSnapshotCandidateEntity candidateEntity = RecommendationSnapshotCandidateEntity.builder()
                    .candidateId(ranking.userId())
                    .rank(rank)
                    .rankingKey(ranking.rankingKey())
                    .fullPrecisionScore(ranking.fullPrecisionScore())
                    .rankingRawFit(ranking.rankingRawFit())
                    .presentationFitValue(ranking.presentationFitValue())
                    .fitStatus(ranking.fitStatus() != null ? ranking.fitStatus() : MetricDataStatus.MEASURED)
                    .storedWorkloadValue(ranking.storedWorkloadValue())
                    .workloadStatus(ranking.workloadStatus() != null ? ranking.workloadStatus() : MetricDataStatus.UNVERIFIED)
                    .workloadUnit(ranking.workloadUnit() != null ? ranking.workloadUnit() : (ranking.workloadStatus() == MetricDataStatus.MEASURED ? "ACTIVE_TASK_COUNT" : null))
                    .workloadScope(ranking.workloadScope() != null ? ranking.workloadScope() : (ranking.workloadStatus() == MetricDataStatus.MEASURED ? "PROJECT" : null))
                    .workloadMeasuredAt(ranking.workloadMeasuredAt() != null ? ranking.workloadMeasuredAt() : (ranking.workloadStatus() == MetricDataStatus.MEASURED ? snapshot.getCreatedAt() : null))
                    .derivedPerformanceInput(ranking.derivedPerformanceInput())
                    .performanceStatus(ranking.performanceStatus() != null ? ranking.performanceStatus() : MetricDataStatus.DEFAULT)
                    .memberStatus(ranking.status() != null ? ranking.status() : "ACTIVE")
                    .selectedAsRecommendation(selectedAsRecommendation)
                    .build();

            snapshot.addCandidate(candidateEntity);
            rank++;
        }

        RecommendationSnapshotEntity saved = snapshotPort.save(snapshot);
        log.info("[Snapshot] Persisted recommendation snapshot {} for project {} (source={}, candidates={})",
                snapshotId, projectId, requestSource, candidateCount);
        return saved;
    }

    @Transactional(readOnly = true)
    public RecommendationSnapshotEntity getSnapshot(String snapshotId, Long requestingUserId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new BusinessException(HttpStatus.NOT_FOUND.value(), "Recommendation snapshot ID is required");
        }

        RecommendationSnapshotEntity snapshot = snapshotPort.findById(snapshotId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                        "Recommendation snapshot not found: " + snapshotId));

        if (requestingUserId == null || !projectMemberPort.isProjectMember(snapshot.getProjectId(), requestingUserId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "User " + requestingUserId + " is not authorized to view snapshot " + snapshotId);
        }

        return snapshot;
    }
}
