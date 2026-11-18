package com.taskpilot.ai.service;

import com.taskpilot.ai.assignment.port.out.RecommendationDecisionEventPort;
import com.taskpilot.ai.assignment.port.out.RecommendationSnapshotPort;
import com.taskpilot.ai.dto.AdaptiveDatasetQualityReportDto;
import com.taskpilot.ai.dto.AdaptiveDatasetRowDto;
import com.taskpilot.ai.dto.AutoAssignmentResponse;
import com.taskpilot.ai.dto.InternalCandidateRanking;
import com.taskpilot.ai.dto.MetricDataStatus;
import com.taskpilot.ai.dto.RecommendationDecisionResponse;
import com.taskpilot.ai.dto.RecommendationDecisionSource;
import com.taskpilot.ai.dto.RecommendationDecisionType;
import com.taskpilot.ai.dto.RecommendationDifferentiationStatus;
import com.taskpilot.ai.dto.RecommendationRequestSource;
import com.taskpilot.ai.entity.AdaptiveOutcomeInvalidationEntity;
import com.taskpilot.ai.entity.AdaptiveReviewedOutcomeEntity;
import com.taskpilot.ai.entity.RecommendationDecisionEventEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotCandidateEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotEntity;
import com.taskpilot.ai.entity.RecommendationTaskOutcomeEntity;
import com.taskpilot.ai.heuristic.BalancedHeuristicStrategy;
import com.taskpilot.ai.heuristic.HeuristicConfig;
import com.taskpilot.ai.heuristic.HeuristicNormalization;
import com.taskpilot.ai.heuristic.HeuristicNormalizationConfig;
import com.taskpilot.ai.heuristic.HeuristicStrategyFactory;
import com.taskpilot.ai.heuristic.HeuristicWeights;
import com.taskpilot.ai.heuristic.NormalizedScores;
import com.taskpilot.ai.repository.AdaptiveOutcomeInvalidationRepository;
import com.taskpilot.ai.repository.AdaptiveReviewedOutcomeRepository;
import com.taskpilot.ai.repository.RecommendationTaskOutcomeRepository;
import com.taskpilot.contracts.aiquery.port.out.TaskCommandPort;
import com.taskpilot.contracts.assignment.dto.ProjectHeuristicConfigDto;
import com.taskpilot.contracts.assignment.dto.ProjectMemberDto;
import com.taskpilot.contracts.assignment.dto.TaskAssignmentContextDto;
import com.taskpilot.contracts.assignment.dto.TaskAssignmentMetrics;
import com.taskpilot.contracts.assignment.dto.TaskAssignmentMetrics.DataStatus;
import com.taskpilot.contracts.assignment.dto.UserProfileDto;
import com.taskpilot.contracts.assignment.dto.UserSkillDto;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.contracts.assignment.port.out.ProjectPort;
import com.taskpilot.contracts.assignment.port.out.TaskPerformanceReadPort;
import com.taskpilot.contracts.assignment.port.out.UserPort;
import com.taskpilot.contracts.assignment.port.out.UserSkillPort;
import com.taskpilot.ai.assignment.port.out.AiAuditPort;
import com.taskpilot.contracts.task.TaskEvaluationAccepted;
import com.taskpilot.contracts.task.TaskEvaluationJournalStatePort;
import com.taskpilot.contracts.user.dto.AccountAuthStateDto;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.infrastructure.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdaptivePipelineUnitTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-11-18T12:00:00Z"), ZoneOffset.UTC);

    private static BalancedHeuristicStrategy balancedStrategy() {
        return new BalancedHeuristicStrategy(new HeuristicConfig(
                new HeuristicWeights(0.45, 0.30, 0.25),
                new HeuristicNormalizationConfig(
                        HeuristicNormalization.BENCHMARK_BENEFIT,
                        HeuristicNormalization.BENCHMARK_BENEFIT,
                        HeuristicNormalization.BENCHMARK_BENEFIT)));
    }

    @Test
    void snapshotCreationCapturesTaskVersionsProvenanceAndBaselineWeightVersion() {
        RecommendationSnapshotPort snapshotPort = mock(RecommendationSnapshotPort.class);
        when(snapshotPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ProjectMemberPort memberPort = mock(ProjectMemberPort.class);

        RecommendationSnapshotService service =
                new RecommendationSnapshotService(snapshotPort, memberPort, FIXED_CLOCK);

        List<InternalCandidateRanking> rankings = List.of(
                InternalCandidateRanking.builder()
                        .userId(101L)
                        .fullName("Alice")
                        .rankingRawFit(0.95)
                        .presentationFitValue(95.0)
                        .fitStatus(MetricDataStatus.MEASURED)
                        .fitSource("DECLARED_PROFILE")
                        .storedWorkloadValue(2)
                        .workloadStatus(MetricDataStatus.MEASURED)
                        .workloadUnit("ACTIVE_TASK_COUNT")
                        .workloadScope("PROJECT")
                        .workloadRevision("wl-rev-1")
                        .derivedPerformanceInput(0.88)
                        .performanceStatus(MetricDataStatus.MEASURED)
                        .performanceVersion("performance-v1")
                        .performanceRubricVersion("quality-v1")
                        .observedQualityMean(0.90)
                        .evidenceWeight(0.75)
                        .fullPrecisionScore(0.8412345)
                        .rankingKey(8412345L)
                        .memberStatus("AVAILABLE")
                        .heuristicMode("BALANCED")
                        .build()
        );

        RecommendationSnapshotEntity saved = service.createSnapshot(
                9L, 10L, 50L, 4L, 2L,
                List.of("Java", "PostgreSQL"), "BALANCED", balancedStrategy(),
                "baseline-v1", "roster-sha-1",
                RecommendationDifferentiationStatus.DIFFERENTIATED,
                rankings, RecommendationRequestSource.REST_AUTO_ASSIGN);

        assertThat(saved.getSnapshotId()).startsWith("rec-snap-");
        assertThat(saved.getProjectId()).isEqualTo(10L);
        assertThat(saved.getTaskId()).isEqualTo(50L);
        assertThat(saved.getTaskVersion()).isEqualTo(4L);
        assertThat(saved.getAssignmentVersion()).isEqualTo(2L);
        assertThat(saved.getWeightVersion()).isEqualTo("baseline-v1");
        assertThat(saved.getRosterRevision()).isEqualTo("roster-sha-1");
        assertThat(saved.getPresentationContractVersion()).isEqualTo("allowlisted-view-v3");
        assertThat(saved.getScoringModelVersion()).isEqualTo(NormalizedScores.PROJECT_RANKING_VERSION);
        assertThat(saved.getCandidates()).hasSize(1);
        assertThat(saved.getCandidates().get(0).getFullPrecisionScore()).isEqualTo(0.8412345);
        assertThat(saved.getCandidates().get(0).getObservedQualityMean()).isEqualTo(0.90);
        assertThat(saved.getCandidates().get(0).getEvidenceWeight()).isEqualTo(0.75);
    }

    @Test
    void telemetryDisabledPreservesBaselineWithoutPersistingSnapshots() {
        ProjectMemberPort memberPort = mock(ProjectMemberPort.class);
        TaskPerformanceReadPort perfPort = mock(TaskPerformanceReadPort.class);
        UserSkillPort skillPort = mock(UserSkillPort.class);
        AiAuditPort aiAuditPort = mock(AiAuditPort.class);
        UserPort userPort = mock(UserPort.class);
        ProjectPort projectPort = mock(ProjectPort.class);
        HeuristicStrategyFactory strategyFactory = mock(HeuristicStrategyFactory.class);
        RecommendationSnapshotService snapshotService = mock(RecommendationSnapshotService.class);
        TaskCommandPort taskCommandPort = mock(TaskCommandPort.class);

        when(memberPort.isProjectMember(10L, 1L)).thenReturn(true);
        when(memberPort.findProjectMembers(10L)).thenReturn(List.of(
                new ProjectMemberDto(101L, "MEMBER", 0.8)
        ));
        when(userPort.findById(101L)).thenReturn(Optional.of(
                new UserProfileDto(101L, "Alice", "alice@example.com", "AVAILABLE", 1, true)
        ));
        when(perfPort.readProjectMetrics(eq(10L), any())).thenReturn(Map.of(
                101L, new TaskAssignmentMetrics(
                        10L, 101L, FIXED_CLOCK.instant(),
                        new TaskAssignmentMetrics.Performance("performance-v1", "quality-v1", DataStatus.MEASURED,
                                0.8, 4.0, 3, 20, 0.6, 3, 1.0, 0, "rev-1"),
                        new TaskAssignmentMetrics.Workload(1L, "ACTIVE_TASK_COUNT", "PROJECT", "wl-rev-1"))
        ));
        when(skillPort.findByUserIdWithSkill(101L)).thenReturn(List.of(new UserSkillDto("Java", 4)));
        when(projectPort.findById(10L)).thenReturn(Optional.of(new ProjectHeuristicConfigDto(10L, "BALANCED")));
        when(strategyFactory.resolve("BALANCED")).thenReturn(balancedStrategy());
        when(strategyFactory.resolveWeightVersion("BALANCED")).thenReturn("baseline-v1");
        when(taskCommandPort.getTaskAssignmentContext(50L, 1L))
                .thenReturn(Optional.of(new TaskAssignmentContextDto(50L, 10L, 1L, 0L, "TODO", null, false)));

        AutoAssignmentService service = new AutoAssignmentService(
                memberPort, perfPort, skillPort, aiAuditPort, userPort, projectPort, strategyFactory, null);
        service.setAdaptiveTelemetryDependencies(snapshotService, taskCommandPort, false);

        AutoAssignmentResponse response = service.recommend(10L, 50L, List.of("Java"), 5, 1L);

        assertThat(response.snapshotId()).isNull();
        assertThat(response.taskId()).isEqualTo(50L);
        assertThat(response.candidates()).hasSize(1);
        assertThat(response.presentationContractVersion()).isEqualTo("allowlisted-view-v3");
        verify(snapshotService, never()).createSnapshot(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    void rejectWithoutSelectionHasNullSelectedCandidateAndDoesNotCallAssign() {
        RecommendationSnapshotPort snapshotPort = mock(RecommendationSnapshotPort.class);
        RecommendationDecisionEventPort decisionPort = mock(RecommendationDecisionEventPort.class);
        ProjectMemberPort memberPort = mock(ProjectMemberPort.class);
        TaskCommandPort taskCommandPort = mock(TaskCommandPort.class);
        AccountAuthStatePort authStatePort = mock(AccountAuthStatePort.class);

        when(authStatePort.findById(1L))
                .thenReturn(Optional.of(new AccountAuthStateDto(1L, "m@example.com", "USER", "ACTIVE", true, 1L)));
        RecommendationSnapshotEntity snapshot = RecommendationSnapshotEntity.builder()
                .snapshotId("rec-snap-1")
                .projectId(10L)
                .taskId(55L)
                .taskVersion(3L)
                .assignmentVersion(0L)
                .rosterRevision("roster-1")
                .recommendedCandidateId(101L)
                .synthetic(false)
                .build();
        when(snapshotPort.findById("rec-snap-1")).thenReturn(Optional.of(snapshot));
        when(memberPort.isProjectManager(10L, 1L)).thenReturn(true);
        when(memberPort.computeProjectRosterRevision(10L)).thenReturn("roster-1");
        when(taskCommandPort.getTaskAssignmentContext(55L, 1L))
                .thenReturn(Optional.of(new TaskAssignmentContextDto(55L, 10L, 3L, 0L, "TODO", null, false)));
        when(decisionPort.findBySnapshotId("rec-snap-1")).thenReturn(Optional.empty());
        when(decisionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RecommendationDecisionService service = new RecommendationDecisionService(
                snapshotPort, decisionPort, memberPort, taskCommandPort, authStatePort, FIXED_CLOCK);

        RecommendationDecisionResponse res = service.recordAndApplyDecision(
                1L, "rec-snap-1", "REJECT", null, 55L, 3L, UUID.randomUUID(), "NO_FIT", "Need external specialist");

        assertThat(res.decisionType()).isEqualTo(RecommendationDecisionType.REJECTED);
        assertThat(res.selectedCandidateId()).isNull();
        verify(taskCommandPort, never()).assignTaskToMember(any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void staleRosterRevisionRejectsDecisionWithConflict() {
        RecommendationSnapshotPort snapshotPort = mock(RecommendationSnapshotPort.class);
        RecommendationDecisionEventPort decisionPort = mock(RecommendationDecisionEventPort.class);
        ProjectMemberPort memberPort = mock(ProjectMemberPort.class);
        TaskCommandPort taskCommandPort = mock(TaskCommandPort.class);
        AccountAuthStatePort authStatePort = mock(AccountAuthStatePort.class);

        when(authStatePort.findById(1L))
                .thenReturn(Optional.of(new AccountAuthStateDto(1L, "m@example.com", "USER", "ACTIVE", true, 1L)));
        RecommendationSnapshotEntity snapshot = RecommendationSnapshotEntity.builder()
                .snapshotId("rec-snap-2")
                .projectId(10L)
                .taskId(55L)
                .taskVersion(3L)
                .rosterRevision("roster-old")
                .candidates(List.of(RecommendationSnapshotCandidateEntity.builder()
                        .candidateId(101L).rank(1).build()))
                .build();
        when(snapshotPort.findById("rec-snap-2")).thenReturn(Optional.of(snapshot));
        when(memberPort.isProjectManager(10L, 1L)).thenReturn(true);
        when(taskCommandPort.getTaskAssignmentContext(55L, 1L))
                .thenReturn(Optional.of(new TaskAssignmentContextDto(55L, 10L, 3L, 0L, "TODO", null, false)));
        when(memberPort.computeProjectRosterRevision(10L)).thenReturn("roster-changed");

        RecommendationDecisionService service = new RecommendationDecisionService(
                snapshotPort, decisionPort, memberPort, taskCommandPort, authStatePort, FIXED_CLOCK);

        assertThatThrownBy(() -> service.recordAndApplyDecision(
                1L, "rec-snap-2", "ACCEPT", 101L, 55L, 3L, UUID.randomUUID(), null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("roster");
    }

    @Test
    void listenerProjectsIdempotentlyAndHandlesOutOfOrderInvalidation() {
        AdaptiveReviewedOutcomeRepository outcomeRepo = mock(AdaptiveReviewedOutcomeRepository.class);
        AdaptiveOutcomeInvalidationRepository invRepo = mock(AdaptiveOutcomeInvalidationRepository.class);
        RecommendationDecisionEventPort decisionPort = mock(RecommendationDecisionEventPort.class);

        UUID ev1 = UUID.randomUUID();
        Instant due = Instant.parse("2026-11-20T00:00:00Z");

        // Prior invalidation arrived out-of-order BEFORE accepted event
        when(outcomeRepo.existsBySourceEventId(ev1)).thenReturn(false);
        when(outcomeRepo.findByEvaluationIdAndEvaluationVersion(70L, 1)).thenReturn(Optional.empty());
        when(invRepo.findByEvaluationIdAndEvaluationVersion(70L, 1)).thenReturn(Optional.of(
                AdaptiveOutcomeInvalidationEntity.builder()
                        .eventId(UUID.randomUUID())
                        .projectId(10L)
                        .taskId(55L)
                        .evaluationId(70L)
                        .evaluationVersion(1)
                        .reason("REOPENED")
                        .occurredAt(FIXED_CLOCK.instant())
                        .evidenceRevision(1L)
                        .build()
        ));
        when(decisionPort.findById("dec-70")).thenReturn(Optional.of(
                RecommendationDecisionEventEntity.builder()
                        .decisionId("dec-70")
                        .snapshotId("snap-70")
                        .projectId(10L)
                        .taskId(55L)
                        .build()
        ));
        when(outcomeRepo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        TaskEvaluationJournalListener listener =
                new TaskEvaluationJournalListener(outcomeRepo, invRepo, decisionPort, Optional.of(FIXED_CLOCK));

        TaskEvaluationAccepted accepted = new TaskEvaluationAccepted(
                ev1, 55L, 10L, 1L, 300L, 70L, 1L, 101L, 1L,
                Instant.parse("2026-11-18T10:00:00Z"),
                Instant.parse("2026-11-18T11:00:00Z"),
                due, 5, true, "quality-v1", "INDEPENDENT_ACCEPTED", 1L, "dec-70");

        listener.onEvaluationAccepted(accepted);

        verify(outcomeRepo).saveAndFlush(any(AdaptiveReviewedOutcomeEntity.class));
        when(outcomeRepo.existsBySourceEventId(ev1)).thenReturn(true);
        assertThat(listener.hasProcessed(ev1)).isTrue();
    }

    @Test
    void datasetBuilderSeparatesEvidenceKeepsFeatureTimeValuesAndIsDeterministic() {
        AdaptiveReviewedOutcomeRepository outcomeRepo = mock(AdaptiveReviewedOutcomeRepository.class);
        RecommendationTaskOutcomeRepository legacyRepo = mock(RecommendationTaskOutcomeRepository.class);
        RecommendationDecisionEventPort decisionPort = mock(RecommendationDecisionEventPort.class);
        RecommendationSnapshotPort snapshotPort = mock(RecommendationSnapshotPort.class);
        TaskEvaluationJournalStatePort journalPort = mock(TaskEvaluationJournalStatePort.class);

        when(journalPort.summarize(10L)).thenReturn(new TaskEvaluationJournalStatePort.JournalHealthSummary(0, 0, 0));

        RecommendationSnapshotEntity snap = RecommendationSnapshotEntity.builder()
                .snapshotId("snap-1")
                .projectId(10L)
                .taskId(55L)
                .requestedByUserId(1L)
                .heuristicMode("BALANCED")
                .presentationContractVersion("allowlisted-view-v3")
                .scoringModelVersion(NormalizedScores.PROJECT_RANKING_VERSION)
                .differentiationStatus("DIFFERENTIATED")
                .weightVersion("baseline-v1")
                .synthetic(false)
                .candidates(List.of(
                        RecommendationSnapshotCandidateEntity.builder()
                                .candidateId(101L).rank(1).rankingRawFit(0.85).storedWorkloadValue(1)
                                .fitStatus(MetricDataStatus.MEASURED)
                                .workloadStatus(MetricDataStatus.MEASURED)
                                .workloadUnit("ACTIVE_TASK_COUNT").workloadScope("PROJECT")
                                .workloadMeasuredAt(FIXED_CLOCK.instant())
                                .derivedPerformanceInput(0.75).performanceStatus(MetricDataStatus.MEASURED)
                                .build(),
                        RecommendationSnapshotCandidateEntity.builder()
                                .candidateId(102L).rank(2).rankingRawFit(0.70).storedWorkloadValue(3)
                                .fitStatus(MetricDataStatus.MEASURED)
                                .workloadStatus(MetricDataStatus.MEASURED)
                                .workloadUnit("ACTIVE_TASK_COUNT").workloadScope("PROJECT")
                                .workloadMeasuredAt(FIXED_CLOCK.instant())
                                .derivedPerformanceInput(0.55).performanceStatus(MetricDataStatus.MEASURED)
                                .build()
                ))
                .build();

        RecommendationDecisionEventEntity decision = RecommendationDecisionEventEntity.builder()
                .decisionId("dec-1")
                .snapshotId("snap-1")
                .projectId(10L)
                .taskId(55L)
                .decidedByUserId(1L)
                .decisionSource(RecommendationDecisionSource.USER)
                .decisionType(RecommendationDecisionType.ACCEPTED)
                .recommendedCandidateId(101L)
                .selectedCandidateId(101L)
                .synthetic(false)
                .build();

        RecommendationDecisionEventEntity rejected = RecommendationDecisionEventEntity.builder()
                .decisionId("dec-rej")
                .snapshotId("snap-1")
                .projectId(10L)
                .taskId(56L)
                .decidedByUserId(1L)
                .decisionSource(RecommendationDecisionSource.USER)
                .decisionType(RecommendationDecisionType.REJECTED)
                .recommendedCandidateId(101L)
                .selectedCandidateId(null)
                .synthetic(false)
                .build();

        AdaptiveReviewedOutcomeEntity eligibleOutcome = AdaptiveReviewedOutcomeEntity.builder()
                .outcomeId("rev-out-1")
                .projectId(10L).taskId(55L).evaluationId(1L).evaluationVersion(1)
                .assignmentVersion(1L).decisionId("dec-1").snapshotId("snap-1")
                .assigneeId(101L).reviewerId(1L).evidenceStatus("INDEPENDENT_ACCEPTED")
                .qualityRating(5).onTime(true).timelinessStatus("ON_TIME")
                .rubricVersion("quality-v1").invalidated(false).synthetic(false)
                .acceptedSubmittedAt(Instant.parse("2026-11-18T10:00:00Z"))
                .reviewedAt(Instant.parse("2026-11-18T11:00:00Z"))
                .build();

        AdaptiveReviewedOutcomeEntity manualOutcome = AdaptiveReviewedOutcomeEntity.builder()
                .outcomeId("rev-out-2")
                .projectId(10L).taskId(57L).evaluationId(2L).evaluationVersion(1)
                .assignmentVersion(1L).decisionId(null).snapshotId(null)
                .assigneeId(102L).reviewerId(1L).evidenceStatus("INDEPENDENT_ACCEPTED")
                .qualityRating(4).onTime(false).timelinessStatus("LATE")
                .rubricVersion("quality-v1").invalidated(false).synthetic(false)
                .build();

        AdaptiveReviewedOutcomeEntity selfAttested = AdaptiveReviewedOutcomeEntity.builder()
                .outcomeId("rev-out-3")
                .projectId(10L).taskId(58L).evaluationId(3L).evaluationVersion(1)
                .assignmentVersion(1L).decisionId("dec-1").snapshotId("snap-1")
                .assigneeId(101L).reviewerId(1L).evidenceStatus("SELF_ATTESTED_ONLY")
                .qualityRating(5).onTime(true).timelinessStatus("ON_TIME")
                .rubricVersion("quality-v1").invalidated(false).synthetic(false)
                .build();

        AdaptiveReviewedOutcomeEntity syntheticRow = AdaptiveReviewedOutcomeEntity.builder()
                .outcomeId("rev-out-4")
                .projectId(10L).taskId(59L).evaluationId(4L).evaluationVersion(1)
                .assignmentVersion(1L).decisionId("dec-1").snapshotId("snap-1")
                .assigneeId(101L).reviewerId(1L).evidenceStatus("INDEPENDENT_ACCEPTED")
                .invalidated(false).synthetic(true)
                .build();

        when(outcomeRepo.findScopedOutcomes(eq(10L), eq(1L), eq("BALANCED"), any(Pageable.class)))
                .thenReturn(List.of(eligibleOutcome, selfAttested, syntheticRow));
        when(outcomeRepo.findUnlinkedOutcomes(eq(10L), any(Pageable.class))).thenReturn(List.of(manualOutcome));
        when(decisionPort.findScopedByDecisionType(eq(RecommendationDecisionType.REJECTED), eq(10L), eq(1L),
                eq("BALANCED"), any(Pageable.class)))
                .thenReturn(List.of(rejected));
        when(legacyRepo.findScopedLegacyOutcomes(eq(10L), any(Pageable.class)))
                .thenReturn(List.of(RecommendationTaskOutcomeEntity.builder().outcomeId("legacy-1").projectId(10L).build()));
        when(decisionPort.findAllByIds(any())).thenReturn(List.of(decision));
        when(snapshotPort.findAllByIds(any())).thenReturn(List.of(snap));

        AdaptiveDatasetService service = new AdaptiveDatasetService(
                outcomeRepo, legacyRepo, decisionPort, snapshotPort, journalPort, FIXED_CLOCK);

        AdaptiveDatasetQualityReportDto report1 = service.generateQualityReport(10L, 1L, "BALANCED", 0, 50);
        AdaptiveDatasetQualityReportDto report2 = service.generateQualityReport(10L, 1L, "BALANCED", 0, 50);

        assertThat(report1.canonicalDatasetSha256()).isEqualTo(report2.canonicalDatasetSha256());
        assertThat(report1.eligibleSamplesCount()).isEqualTo(1L);
        assertThat(report1.manualEvidenceCount()).isEqualTo(1L);
        assertThat(report1.selfAttestedEvidenceCount()).isEqualTo(1L);
        assertThat(report1.rejectedFeedbackCount()).isEqualTo(1L);
        assertThat(report1.legacyOutcomeCount()).isEqualTo(1L);
        assertThat(report1.syntheticRowsCount()).isEqualTo(1L);
        assertThat(report1.sufficientEvidenceForProposal()).isFalse();
        assertThat(report1.readinessStatus()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(report1.sampleRows()).hasSize(1);
        AdaptiveDatasetRowDto row = report1.sampleRows().get(0);
        assertThat(row.candidates()).hasSize(2);
        assertThat(row.selectedCandidateFit()).isEqualTo(0.85);
        assertThat(row.selectedCandidatePerformance()).isEqualTo(0.75);

        service.setMinimumRequiredSamples(1);
        when(outcomeRepo.findScopedOutcomes(eq(10L), isNull(), eq("BALANCED"), any(Pageable.class)))
                .thenReturn(List.of(eligibleOutcome));
        AdaptiveDatasetService.DatasetResult overview = service.buildDataset(10L, null, "BALANCED", 0, 50);
        assertThat(overview.qualityReport().eligibleSamplesCount()).isEqualTo(1);
        assertThat(overview.qualityReport().readinessStatus()).isEqualTo("SCOPE_REQUIRED");
        assertThat(overview.qualityReport().sufficientEvidenceForProposal()).isFalse();
        assertThat(overview.qualityReport().canonicalDatasetSha256()).isNull();
        assertThat(overview.eligibleRows()).isEmpty();

        when(outcomeRepo.findScopedOutcomes(eq(10L), eq(1L), eq("BALANCED"), any(Pageable.class)))
                .thenReturn(java.util.Collections.nCopies(501, eligibleOutcome));
        AdaptiveDatasetService.DatasetResult truncated = service.buildDataset(10L, 1L, "BALANCED", 0, 50);
        assertThat(truncated.qualityReport().eligibleSamplesCount()).isEqualTo(1);
        assertThat(truncated.qualityReport().readinessStatus()).isEqualTo("DATASET_LIMIT_REACHED");
        assertThat(truncated.qualityReport().sufficientEvidenceForProposal()).isFalse();
        assertThat(truncated.qualityReport().canonicalDatasetSha256()).isNull();
        assertThat(truncated.eligibleRows()).isEmpty();
    }
}
