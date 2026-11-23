package com.taskpilot.ai.service;

import com.taskpilot.ai.assignment.port.out.RecommendationSnapshotPort;
import com.taskpilot.ai.dto.InternalCandidateRanking;
import com.taskpilot.ai.dto.MetricDataStatus;
import com.taskpilot.ai.dto.RecommendationDifferentiationStatus;
import com.taskpilot.ai.dto.RecommendationRequestSource;
import com.taskpilot.ai.entity.RecommendationSnapshotCandidateEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotEntity;
import com.taskpilot.ai.heuristic.HeuristicStrategy;
import com.taskpilot.ai.heuristic.HeuristicWeights;
import com.taskpilot.ai.heuristic.NormalizedScores;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class RecommendationSnapshotService {

    public static final String PRESENTATION_CONTRACT_VERSION = "allowlisted-view-v3";

    private final RecommendationSnapshotPort snapshotPort;
    private final ProjectMemberPort projectMemberPort;
    private final Clock clock;

    @Autowired
    public RecommendationSnapshotService(
            RecommendationSnapshotPort snapshotPort,
            ProjectMemberPort projectMemberPort,
            Optional<Clock> clock) {
        this(snapshotPort, projectMemberPort,
                clock != null ? clock.orElse(Clock.systemUTC()) : Clock.systemUTC());
    }

    public RecommendationSnapshotService(
            RecommendationSnapshotPort snapshotPort,
            ProjectMemberPort projectMemberPort,
            Clock clock) {
        this.snapshotPort = snapshotPort;
        this.projectMemberPort = projectMemberPort;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Transactional
    public RecommendationSnapshotEntity createSnapshot(
            Long requestingUserId,
            Long projectId,
            Long taskId,
            Long taskVersion,
            Long assignmentVersion,
            List<String> requiredSkills,
            String heuristicMode,
            HeuristicStrategy strategy,
            String weightVersion,
            String rosterRevision,
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
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        HeuristicWeights weights = strategy != null ? strategy.weights() : null;
        double fitWeight = weights != null ? weights.fitWeight() : 0.0;
        double loadWeight = weights != null ? weights.loadWeight() : 0.0;
        double perfWeight = weights != null ? weights.performanceWeight() : 0.0;

        List<InternalCandidateRanking> safeRankings = internalRankings == null
                ? Collections.emptyList()
                : internalRankings;
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
                .taskVersion(taskVersion)
                .assignmentVersion(assignmentVersion)
                .createdAt(now)
                .heuristicMode(heuristicMode != null ? heuristicMode : (strategy != null ? strategy.mode() : "UNKNOWN"))
                .presentationContractVersion(PRESENTATION_CONTRACT_VERSION)
                .scoringModelVersion(NormalizedScores.PROJECT_RANKING_VERSION)
                .differentiationStatus(safeDiffStatus.name())
                .requiredSkills(skillsString)
                .recommendedCandidateId(recommendedCandidateId)
                .candidateCount(candidateCount)
                .requestSource(requestSource)
                .fitWeight(fitWeight)
                .loadWeight(loadWeight)
                .performanceWeight(perfWeight)
                .normalizationContract(RecommendationSnapshotEntity.DEFAULT_NORMALIZATION_CONTRACT)
                .weightVersion((weightVersion == null || weightVersion.isBlank()) ? "baseline-v1" : weightVersion)
                .rosterRevision(rosterRevision)
                .synthetic(Boolean.FALSE)
                .candidates(new ArrayList<>())
                .build();

        int rank = 1;
        for (InternalCandidateRanking ranking : safeRankings) {
            RecommendationSnapshotCandidateEntity candidateEntity = RecommendationSnapshotCandidateEntity.builder()
                    .candidateId(ranking.userId())
                    .rank(rank)
                    .rankingKey(ranking.rankingKey())
                    .fullPrecisionScore(ranking.fullPrecisionScore())
                    .rankingRawFit(ranking.rankingRawFit())
                    .presentationFitValue(ranking.presentationFitValue())
                    .fitStatus(ranking.fitStatus() != null ? ranking.fitStatus() : MetricDataStatus.MEASURED)
                    .fitSource(ranking.fitSource())
                    .storedWorkloadValue(ranking.storedWorkloadValue())
                    .workloadStatus(ranking.workloadStatus() != null ? ranking.workloadStatus() : MetricDataStatus.MEASURED)
                    .workloadUnit(ranking.workloadUnit())
                    .workloadScope(ranking.workloadScope())
                    .workloadMeasuredAt(ranking.workloadMeasuredAt())
                    .workloadRevision(ranking.workloadRevision())
                    .derivedPerformanceInput(ranking.derivedPerformanceInput())
                    .performanceStatus(ranking.performanceStatus() != null ? ranking.performanceStatus() : MetricDataStatus.DEFAULT)
                    .performanceVersion(ranking.performanceVersion())
                    .performanceRubricVersion(ranking.performanceRubricVersion())
                    .performanceSampleCount(ranking.performanceSampleCount())
                    .performanceWindowLimit(ranking.performanceWindowLimit())
                    .observedQualityMean(ranking.observedQualityMean())
                    .evidenceWeight(ranking.evidenceWeight())
                    .performanceEvidenceRevision(ranking.performanceEvidenceRevision())
                    .performanceMeasuredAt(ranking.performanceMeasuredAt())
                    .memberStatus(ranking.memberStatus() != null ? ranking.memberStatus() : "ACTIVE")
                    .selectedAsRecommendation(rank == 1)
                    .build();

            snapshot.addCandidate(candidateEntity);
            rank++;
        }

        RecommendationSnapshotEntity saved = snapshotPort.save(snapshot);
        log.info("[Snapshot] Persisted recommendation snapshot {} for project {} task {} (source={}, candidates={})",
                snapshotId, projectId, taskId, requestSource, candidateCount);
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
