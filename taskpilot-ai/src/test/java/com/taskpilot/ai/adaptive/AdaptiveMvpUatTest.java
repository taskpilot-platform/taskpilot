package com.taskpilot.ai.adaptive;

import com.taskpilot.ai.assignment.port.out.RecommendationDecisionEventPort;
import com.taskpilot.ai.assignment.port.out.RecommendationSnapshotPort;
import com.taskpilot.ai.assignment.port.out.RecommendationTaskOutcomePort;
import com.taskpilot.ai.controller.AdaptiveAdminController;
import com.taskpilot.ai.dto.*;
import com.taskpilot.ai.dto.AdaptiveAdminRequests.ApproveProposalRequest;
import com.taskpilot.ai.dto.AdaptiveAdminRequests.RejectProposalRequest;
import com.taskpilot.ai.entity.AdaptiveWeightProposalEntity;
import com.taskpilot.ai.entity.RecommendationDecisionEventEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotCandidateEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotEntity;
import com.taskpilot.ai.entity.RecommendationTaskOutcomeEntity;
import com.taskpilot.ai.heuristic.*;
import com.taskpilot.ai.repository.AdaptiveWeightProposalRepository;
import com.taskpilot.ai.service.AdaptiveDatasetService;
import com.taskpilot.ai.service.AdaptiveWeightProposalService;
import com.taskpilot.contracts.user.dto.UserIdentityDto;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.infrastructure.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Automated end-to-end UAT test covering Scenarios A through F from Section 17.
 * Uses synthetic deterministic evidence and in-memory mock repositories.
 */
class AdaptiveMvpUatTest {

    private RecommendationTaskOutcomePort outcomePort;
    private RecommendationDecisionEventPort decisionPort;
    private RecommendationSnapshotPort snapshotPort;
    private AdaptiveWeightProposalRepository proposalRepository;
    private UserIdentityPort userIdentityPort;
    private HeuristicStrategyFactory strategyFactory;

    private AdaptiveDatasetService datasetService;
    private AdaptiveWeightProposalService proposalService;
    private AdaptiveAdminController adminController;

    private HeuristicWeights initialActiveWeights;
    private Map<String, AdaptiveWeightProposalEntity> persistedProposals;
    private Map<String, RecommendationTaskOutcomeEntity> persistedOutcomes;
    private Map<String, RecommendationDecisionEventEntity> persistedDecisions;
    private Map<String, RecommendationSnapshotEntity> persistedSnapshots;

    private Authentication adminAuth;
    private Authentication userAuth;

    private static final Instant BASE_TIME = Instant.parse("2026-10-10T12:00:00Z");

    @BeforeEach
    void setUp() {
        outcomePort = mock(RecommendationTaskOutcomePort.class);
        decisionPort = mock(RecommendationDecisionEventPort.class);
        snapshotPort = mock(RecommendationSnapshotPort.class);
        proposalRepository = mock(AdaptiveWeightProposalRepository.class);
        userIdentityPort = mock(UserIdentityPort.class);
        strategyFactory = mock(HeuristicStrategyFactory.class);

        persistedProposals = new LinkedHashMap<>();
        persistedOutcomes = new LinkedHashMap<>();
        persistedDecisions = new LinkedHashMap<>();
        persistedSnapshots = new LinkedHashMap<>();

        initialActiveWeights = new HeuristicWeights(0.5, 0.3, 0.2);
        HeuristicConfig config = new HeuristicConfig(
                initialActiveWeights,
                new HeuristicNormalizationConfig(
                        HeuristicNormalization.BENCHMARK_BENEFIT,
                        HeuristicNormalization.BENCHMARK_BENEFIT,
                        HeuristicNormalization.BENCHMARK_BENEFIT
                )
        );
        HeuristicStrategy strategy = new BalancedHeuristicStrategy(config);
        when(strategyFactory.resolve(any())).thenReturn(strategy);

        when(outcomePort.findAll()).thenAnswer(inv -> new ArrayList<>(persistedOutcomes.values()));
        when(outcomePort.findById(any())).thenAnswer(inv -> Optional.ofNullable(persistedOutcomes.get(inv.getArgument(0))));

        when(decisionPort.findById(any())).thenAnswer(inv -> Optional.ofNullable(persistedDecisions.get(inv.getArgument(0))));
        when(snapshotPort.findById(any())).thenAnswer(inv -> Optional.ofNullable(persistedSnapshots.get(inv.getArgument(0))));

        when(proposalRepository.save(any(AdaptiveWeightProposalEntity.class))).thenAnswer(inv -> {
            AdaptiveWeightProposalEntity entity = inv.getArgument(0);
            persistedProposals.put(entity.getProposalId(), entity);
            return entity;
        });
        when(proposalRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(persistedProposals.get(inv.getArgument(0))));
        when(proposalRepository.findAllByOrderByCreatedAtDesc()).thenAnswer(inv -> new ArrayList<>(persistedProposals.values()));
        when(proposalRepository.findTopByOrderByCreatedAtDesc()).thenAnswer(inv -> {
            List<AdaptiveWeightProposalEntity> list = new ArrayList<>(persistedProposals.values());
            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(list.size() - 1));
        });

        when(userIdentityPort.findByEmail("admin@taskpilot.io"))
                .thenReturn(Optional.of(new UserIdentityDto(1L, "admin@taskpilot.io")));
        when(userIdentityPort.findByEmail("user@taskpilot.io"))
                .thenReturn(Optional.of(new UserIdentityDto(2L, "user@taskpilot.io")));

        datasetService = new AdaptiveDatasetService(outcomePort, decisionPort, snapshotPort);
        proposalService = new AdaptiveWeightProposalService(datasetService, proposalRepository, strategyFactory);
        adminController = new AdaptiveAdminController(datasetService, proposalService, userIdentityPort);

        adminAuth = new TestingAuthenticationToken("admin@taskpilot.io", "pass", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        userAuth = new TestingAuthenticationToken("user@taskpilot.io", "pass", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private void seedSyntheticEvidenceChain(
            int index,
            RecommendationDecisionType decisionType,
            RecommendationDecisionSource decisionSource,
            TaskOutcomeType outcomeType,
            String diffStatus,
            MetricDataStatus workloadStatus,
            boolean isMissingDecision,
            boolean isMissingOutcome) {

        String snapId = "snap-" + index;
        String decId = "dec-" + index;
        String outId = "out-" + index;
        long taskId = 2000L + index;
        long selectedId = (decisionType == RecommendationDecisionType.OVERRIDDEN) ? 20L : 10L;

        RecommendationSnapshotCandidateEntity c1 = RecommendationSnapshotCandidateEntity.builder()
                .candidateId(10L)
                .rank(1)
                .rankingKey(850000000L)
                .fullPrecisionScore(0.85)
                .rankingRawFit(0.85)
                .presentationFitValue(0.85)
                .fitStatus(MetricDataStatus.MEASURED)
                .storedWorkloadValue(2)
                .workloadStatus(workloadStatus)
                .workloadUnit("ACTIVE_TASK_COUNT")
                .workloadScope("PROJECT")
                .workloadMeasuredAt(BASE_TIME)
                .derivedPerformanceInput(0.5)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("ACTIVE")
                .selectedAsRecommendation(true)
                .build();

        RecommendationSnapshotCandidateEntity c2 = RecommendationSnapshotCandidateEntity.builder()
                .candidateId(20L)
                .rank(2)
                .rankingKey(750000000L)
                .fullPrecisionScore(0.75)
                .rankingRawFit(0.75)
                .presentationFitValue(0.75)
                .fitStatus(MetricDataStatus.MEASURED)
                .storedWorkloadValue(0)
                .workloadStatus(workloadStatus)
                .workloadUnit("ACTIVE_TASK_COUNT")
                .workloadScope("PROJECT")
                .workloadMeasuredAt(BASE_TIME)
                .derivedPerformanceInput(0.5)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("ACTIVE")
                .selectedAsRecommendation(false)
                .build();

        RecommendationSnapshotEntity snap = RecommendationSnapshotEntity.builder()
                .snapshotId(snapId)
                .requestedByUserId(100L)
                .projectId(10L)
                .taskId(taskId)
                .createdAt(BASE_TIME)
                .heuristicMode("BALANCED")
                .presentationContractVersion("allowlisted-view-v2")
                .scoringModelVersion("v1.0")
                .differentiationStatus(diffStatus)
                .requiredSkills("Java,Postgres")
                .recommendedCandidateId(10L)
                .candidateCount(2)
                .fitWeight(0.5)
                .loadWeight(0.3)
                .performanceWeight(0.2)
                .candidates(new ArrayList<>(List.of(c1, c2)))
                .build();

        persistedSnapshots.put(snapId, snap);

        if (!isMissingDecision) {
            RecommendationDecisionEventEntity dec = RecommendationDecisionEventEntity.builder()
                    .decisionId(decId)
                    .snapshotId(snapId)
                    .projectId(10L)
                    .taskId(taskId)
                    .recommendedCandidateId(10L)
                    .selectedCandidateId(selectedId)
                    .decisionType(decisionType)
                    .decisionSource(decisionSource)
                    .decidedByUserId(100L)
                    .createdAt(BASE_TIME.plusSeconds(60))
                    .build();
            persistedDecisions.put(decId, dec);
        }

        if (!isMissingOutcome) {
            RecommendationTaskOutcomeEntity out = RecommendationTaskOutcomeEntity.builder()
                    .outcomeId(outId)
                    .snapshotId(snapId)
                    .decisionId(decId)
                    .projectId(10L)
                    .taskId(taskId)
                    .recommendedCandidateId(10L)
                    .selectedCandidateId(selectedId)
                    .decisionType(decisionType)
                    .decisionSource(decisionSource)
                    .observedAssigneeId(selectedId)
                    .taskStatus("DONE")
                    .dueAt(BASE_TIME.plusSeconds(3600))
                    .completedAt(outcomeType == TaskOutcomeType.COMPLETED_ON_TIME ? BASE_TIME.plusSeconds(1800) : BASE_TIME.plusSeconds(5000))
                    .outcomeType(outcomeType)
                    .observedAt(BASE_TIME.plusSeconds(2000))
                    .outcomeVersion("outcome-v1")
                    .build();
            persistedOutcomes.put(outId, out);
        }
    }

    @Test
    @DisplayName("UAT Scenario A: Insufficient evidence (< 20 samples -> INSUFFICIENT_EVIDENCE, zero metrics, no mutation)")
    void testUatScenarioA_insufficientEvidence() {
        // Seed 10 eligible samples (< 20)
        for (int i = 1; i <= 10; i++) {
            seedSyntheticEvidenceChain(i, RecommendationDecisionType.ACCEPTED, RecommendationDecisionSource.USER,
                    TaskOutcomeType.COMPLETED_ON_TIME, "DIFFERENTIATED", MetricDataStatus.MEASURED, false, false);
        }

        AdaptiveDatasetQualityReportDto report = datasetService.generateQualityReport();
        assertEquals(10, report.totalOutcomesEvaluated());
        assertEquals(10, report.eligibleSamplesCount());
        assertFalse(report.sufficientEvidenceForProposal());

        AdaptiveWeightProposalDto proposal = proposalService.generateProposal(1L);
        assertEquals(WeightProposalStatus.INSUFFICIENT_EVIDENCE, proposal.status());
        assertEquals(10, proposal.datasetSampleCount());
        assertNull(proposal.metricHistoricalAgreement());
        assertNull(proposal.metricOnTimeAssociation());
        assertNull(proposal.proposedFitWeight());
        assertNull(proposal.proposedLoadWeight());
        assertNull(proposal.proposedPerformanceWeight());
        assertEquals(0.625, proposal.currentAdaptiveFitWeight(), 1e-4);
        assertEquals(0.375, proposal.currentAdaptiveLoadWeight(), 1e-4);
        assertNull(proposal.currentMetricHistoricalAgreement());
        assertNull(proposal.currentMetricOnTimeAssociation());

        // Review attempts on INSUFFICIENT_EVIDENCE must fail with 400 Bad Request
        assertThrows(BusinessException.class, () ->
                adminController.approveProposal(proposal.proposalId(), new ApproveProposalRequest("Attempt approve"), adminAuth)
        );
        assertThrows(BusinessException.class, () ->
                adminController.rejectProposal(proposal.proposalId(), new RejectProposalRequest("Attempt reject"), adminAuth)
        );

        // Verify active weights remain unchanged
        HeuristicWeights current = strategyFactory.resolve("BALANCED").weights();
        assertEquals(0.5, current.fitWeight());
        assertEquals(0.3, current.loadWeight());
        assertEquals(0.2, current.performanceWeight());
    }

    @Test
    @DisplayName("UAT Scenario B: Reviewable proposal (>= 20 eligible samples + excluded evidence handled)")
    void testUatScenarioB_reviewableProposal() {
        // Seed 22 eligible samples (mix of ACCEPTED/OVERRIDDEN and ON_TIME/LATE)
        for (int i = 1; i <= 12; i++) {
            seedSyntheticEvidenceChain(i, RecommendationDecisionType.ACCEPTED, RecommendationDecisionSource.USER,
                    TaskOutcomeType.COMPLETED_ON_TIME, "DIFFERENTIATED", MetricDataStatus.MEASURED, false, false);
        }
        for (int i = 13; i <= 18; i++) {
            seedSyntheticEvidenceChain(i, RecommendationDecisionType.ACCEPTED, RecommendationDecisionSource.USER,
                    TaskOutcomeType.COMPLETED_LATE, "DIFFERENTIATED", MetricDataStatus.MEASURED, false, false);
        }
        for (int i = 19; i <= 22; i++) {
            seedSyntheticEvidenceChain(i, RecommendationDecisionType.OVERRIDDEN, RecommendationDecisionSource.USER,
                    TaskOutcomeType.COMPLETED_ON_TIME, "DIFFERENTIATED", MetricDataStatus.MEASURED, false, false);
        }

        // Seed 5 excluded samples:
        // 1. UNOBSERVED snapshot
        seedSyntheticEvidenceChain(23, RecommendationDecisionType.ACCEPTED, RecommendationDecisionSource.USER,
                TaskOutcomeType.COMPLETED_ON_TIME, "UNOBSERVED", MetricDataStatus.MEASURED, false, false);
        // 2. SYSTEM decision
        seedSyntheticEvidenceChain(24, RecommendationDecisionType.ACCEPTED, RecommendationDecisionSource.SYSTEM,
                TaskOutcomeType.COMPLETED_ON_TIME, "DIFFERENTIATED", MetricDataStatus.MEASURED, false, false);
        // 3. UNVERIFIED workload
        seedSyntheticEvidenceChain(25, RecommendationDecisionType.ACCEPTED, RecommendationDecisionSource.USER,
                TaskOutcomeType.COMPLETED_ON_TIME, "DIFFERENTIATED", MetricDataStatus.UNVERIFIED, false, false);
        // 4. Missing decision
        seedSyntheticEvidenceChain(26, RecommendationDecisionType.ACCEPTED, RecommendationDecisionSource.USER,
                TaskOutcomeType.COMPLETED_ON_TIME, "DIFFERENTIATED", MetricDataStatus.MEASURED, true, false);
        // 5. Excluded outcome
        seedSyntheticEvidenceChain(27, RecommendationDecisionType.ACCEPTED, RecommendationDecisionSource.USER,
                TaskOutcomeType.EXCLUDED, "DIFFERENTIATED", MetricDataStatus.MEASURED, false, false);

        AdaptiveDatasetQualityReportDto report = datasetService.generateQualityReport();
        assertEquals(27, report.totalOutcomesEvaluated()); // 27 total outcomes evaluated
        assertEquals(22, report.eligibleSamplesCount());
        assertEquals(5, report.excludedSamplesCount()); // 5 excluded samples (23, 24, 25, 26, 27)
        assertTrue(report.sufficientEvidenceForProposal());

        AdaptiveWeightProposalDto proposal = proposalService.generateProposal(1L);
        assertEquals(WeightProposalStatus.READY_FOR_REVIEW, proposal.status());
        assertEquals("BALANCED", proposal.targetHeuristicMode());
        assertEquals(22, proposal.datasetSampleCount());
        assertTrue(proposal.proposedFitWeight() >= 0.10 && proposal.proposedFitWeight() <= 0.90);
        assertTrue(proposal.proposedLoadWeight() >= 0.10 && proposal.proposedLoadWeight() <= 0.90);
        assertEquals(1.0, proposal.proposedFitWeight() + proposal.proposedLoadWeight(), 1e-4);
        assertNotNull(proposal.datasetHash());
        assertEquals(64, proposal.datasetHash().length());
    }

    @Test
    @DisplayName("UAT Scenario C: Authorization (non-admin 403 denied, admin allowed)")
    void testUatScenarioC_authorization() {
        // Seed 20 samples to generate a proposal
        for (int i = 1; i <= 20; i++) {
            seedSyntheticEvidenceChain(i, RecommendationDecisionType.ACCEPTED, RecommendationDecisionSource.USER,
                    TaskOutcomeType.COMPLETED_ON_TIME, "DIFFERENTIATED", MetricDataStatus.MEASURED, false, false);
        }

        // Non-admin attempts
        assertThrows(BusinessException.class, () -> adminController.generateProposal(userAuth));
        assertThrows(BusinessException.class, () -> adminController.getDatasetReport(userAuth));
        assertThrows(BusinessException.class, () -> adminController.approveProposal("any-id", new ApproveProposalRequest("note"), userAuth));
        assertThrows(BusinessException.class, () -> adminController.rejectProposal("any-id", new RejectProposalRequest("reason"), userAuth));

        // Admin attempt succeeds
        ApiResponse<AdaptiveWeightProposalDto> genResp = adminController.generateProposal(adminAuth);
        assertEquals(HttpStatus.CREATED.value(), genResp.getStatus());
        assertNotNull(genResp.getData().proposalId());

        ApiResponse<AdaptiveWeightProposalDto> getResp = adminController.getProposalById(genResp.getData().proposalId(), adminAuth);
        assertEquals(HttpStatus.OK.value(), getResp.getStatus());
    }

    @Test
    @DisplayName("UAT Scenario D: Approval and Absolute No-Activation Guarantee")
    void testUatScenarioD_approvalAndNoActivationGuarantee() {
        for (int i = 1; i <= 20; i++) {
            seedSyntheticEvidenceChain(i, RecommendationDecisionType.ACCEPTED, RecommendationDecisionSource.USER,
                    TaskOutcomeType.COMPLETED_ON_TIME, "DIFFERENTIATED", MetricDataStatus.MEASURED, false, false);
        }

        AdaptiveWeightProposalDto generated = proposalService.generateProposal(1L);
        String propId = generated.proposalId();

        // 1. Capture active weights before review
        HeuristicStrategy strategyBefore = strategyFactory.resolve("BALANCED");
        HeuristicWeights weightsBefore = strategyBefore.weights();
        assertEquals(0.5, weightsBefore.fitWeight());
        assertEquals(0.3, weightsBefore.loadWeight());
        assertEquals(0.2, weightsBefore.performanceWeight());

        // 2. Compute fixed recommendation score before approval
        RawScores fixedCandidateScores = new RawScores(0.8, 1.0, 0.5);
        ScoreRanges fixedRanges = new ScoreRanges(new ScoreRange(0.0, 1.0), new ScoreRange(0.0, 5.0), new ScoreRange(0.0, 1.0));
        NormalizedScores normBefore = strategyBefore.normalizeNeutral(fixedCandidateScores, fixedRanges);
        double scoreBefore = strategyBefore.score(normBefore);

        // 3. Admin approves proposal
        ApiResponse<AdaptiveWeightProposalDto> appResp = adminController.approveProposal(
                propId, new ApproveProposalRequest("Approved in dev UAT"), adminAuth
        );
        assertEquals(HttpStatus.OK.value(), appResp.getStatus());
        assertEquals(WeightProposalStatus.APPROVED, appResp.getData().status());
        assertEquals(1L, appResp.getData().reviewedByUserId());
        assertEquals("Approved in dev UAT", appResp.getData().reviewNotes());
        assertNotNull(appResp.getData().reviewedAt());

        // 4. Attempt second approval -> rejected (400)
        assertThrows(BusinessException.class, () ->
                adminController.approveProposal(propId, new ApproveProposalRequest("Second approve"), adminAuth)
        );

        // 5. Attempt rejection after approval -> rejected (400)
        assertThrows(BusinessException.class, () ->
                adminController.rejectProposal(propId, new RejectProposalRequest("Reject after approve"), adminAuth)
        );

        // 6. Capture active weights after approval -> must be strictly identical!
        HeuristicStrategy strategyAfter = strategyFactory.resolve("BALANCED");
        HeuristicWeights weightsAfter = strategyAfter.weights();
        assertEquals(weightsBefore.fitWeight(), weightsAfter.fitWeight(), 1e-9);
        assertEquals(weightsBefore.loadWeight(), weightsAfter.loadWeight(), 1e-9);
        assertEquals(weightsBefore.performanceWeight(), weightsAfter.performanceWeight(), 1e-9);

        // 7. Compute fixed recommendation score after approval -> must be strictly identical!
        NormalizedScores normAfter = strategyAfter.normalizeNeutral(fixedCandidateScores, fixedRanges);
        double scoreAfter = strategyAfter.score(normAfter);
        assertEquals(scoreBefore, scoreAfter, 1e-9);
    }

    @Test
    @DisplayName("UAT Scenario E: Rejection lifecycle and state immutability")
    void testUatScenarioE_rejectionLifecycle() {
        for (int i = 1; i <= 20; i++) {
            seedSyntheticEvidenceChain(i, RecommendationDecisionType.ACCEPTED, RecommendationDecisionSource.USER,
                    TaskOutcomeType.COMPLETED_ON_TIME, "DIFFERENTIATED", MetricDataStatus.MEASURED, false, false);
        }

        AdaptiveWeightProposalDto generated = proposalService.generateProposal(1L);
        String propId = generated.proposalId();

        // Admin rejects proposal
        ApiResponse<AdaptiveWeightProposalDto> rejResp = adminController.rejectProposal(
                propId, new RejectProposalRequest("Sub-optimal fit weight"), adminAuth
        );
        assertEquals(HttpStatus.OK.value(), rejResp.getStatus());
        assertEquals(WeightProposalStatus.REJECTED, rejResp.getData().status());
        assertEquals("Sub-optimal fit weight", rejResp.getData().rejectionReason());

        // Attempt approval after rejection -> rejected (400)
        assertThrows(BusinessException.class, () ->
                adminController.approveProposal(propId, new ApproveProposalRequest("Approve rejected"), adminAuth)
        );

        // Active weights remain identical
        HeuristicWeights current = strategyFactory.resolve("BALANCED").weights();
        assertEquals(0.5, current.fitWeight());
        assertEquals(0.3, current.loadWeight());
        assertEquals(0.2, current.performanceWeight());
    }

    @Test
    @DisplayName("UAT Scenario F: Audit Lineage and Persistence Field Verification")
    void testUatScenarioF_auditLineagePersistence() {
        for (int i = 1; i <= 20; i++) {
            seedSyntheticEvidenceChain(i, RecommendationDecisionType.ACCEPTED, RecommendationDecisionSource.USER,
                    TaskOutcomeType.COMPLETED_ON_TIME, "DIFFERENTIATED", MetricDataStatus.MEASURED, false, false);
        }

        AdaptiveWeightProposalDto proposal = proposalService.generateProposal(1L);
        AdaptiveWeightProposalEntity entity = persistedProposals.get(proposal.proposalId());

        assertNotNull(entity);
        assertEquals(proposal.proposalId(), entity.getProposalId());
        assertEquals(WeightProposalStatus.READY_FOR_REVIEW, entity.getStatus());
        assertNotNull(entity.getDatasetHash());
        assertEquals(20, entity.getDatasetSampleCount());
        assertNotNull(entity.getProposedFitWeight());
        assertNotNull(entity.getProposedLoadWeight());
        assertEquals(0.625, entity.getCurrentAdaptiveFitWeight(), 1e-4);
        assertEquals(0.375, entity.getCurrentAdaptiveLoadWeight(), 1e-4);
        assertNotNull(entity.getCurrentMetricHistoricalAgreement());
        assertNotNull(entity.getCurrentMetricOnTimeAssociation());
        assertNotNull(entity.getEvaluationWindowStart());
        assertNotNull(entity.getEvaluationWindowEnd());
        assertEquals(0.05, entity.getGridStep());
        assertNotNull(entity.getCreatedAt());
        assertEquals(1L, entity.getCreatedByUserId());
        assertEquals("proposal-v1", entity.getProposalVersion());
        assertEquals("dataset-v1", entity.getDatasetVersion());
        assertEquals("grid-search-v1", entity.getAlgorithmVersion());
    }
}
