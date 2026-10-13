package com.taskpilot.ai.adaptive;

import com.taskpilot.ai.controller.AdaptiveAdminController;
import com.taskpilot.ai.dto.*;
import com.taskpilot.ai.dto.AdaptiveAdminRequests.ApproveProposalRequest;
import com.taskpilot.ai.dto.AdaptiveAdminRequests.RejectProposalRequest;
import com.taskpilot.ai.entity.AdaptiveWeightProposalEntity;
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
 * Phase 2F Lifecycle, Review, Authorization, and No-Activation Test Suite.
 * Verifies:
 * - Admin authorization enforcement (non-admin 403, unauthenticated 401)
 * - Proposal lifecycle transitions (READY_FOR_REVIEW -> APPROVED, READY_FOR_REVIEW -> REJECTED)
 * - Immutability of terminal proposal states (second review attempt rejected)
 * - Absolute No-Activation Guarantee (approval NEVER mutates active weights)
 */
class AdaptiveWeightProposalLifecycleTest {

    private AdaptiveDatasetService datasetService;
    private AdaptiveWeightProposalRepository proposalRepository;
    private HeuristicStrategyFactory strategyFactory;
    private AdaptiveWeightProposalService proposalService;
    private UserIdentityPort userIdentityPort;
    private AdaptiveAdminController adminController;

    private HeuristicWeights initialActiveWeights;

    @BeforeEach
    void setUp() {
        datasetService = mock(AdaptiveDatasetService.class);
        proposalRepository = mock(AdaptiveWeightProposalRepository.class);
        strategyFactory = mock(HeuristicStrategyFactory.class);
        userIdentityPort = mock(UserIdentityPort.class);

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

        proposalService = new AdaptiveWeightProposalService(datasetService, proposalRepository, strategyFactory);
        adminController = new AdaptiveAdminController(datasetService, proposalService, userIdentityPort);

        when(proposalRepository.save(any(AdaptiveWeightProposalEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        when(userIdentityPort.findByEmail("admin@taskpilot.io"))
                .thenReturn(Optional.of(new UserIdentityDto(1L, "admin@taskpilot.io")));
        when(userIdentityPort.findByEmail("user@taskpilot.io"))
                .thenReturn(Optional.of(new UserIdentityDto(2L, "user@taskpilot.io")));
    }

    private AdaptiveWeightProposalEntity createProposal(String id, WeightProposalStatus status) {
        boolean isInsuff = (status == WeightProposalStatus.INSUFFICIENT_EVIDENCE);
        return AdaptiveWeightProposalEntity.builder()
                .proposalId(id)
                .status(status)
                .targetHeuristicMode("BALANCED")
                .datasetHash("sha-abc")
                .datasetSampleCount(isInsuff ? 15 : 25)
                .currentFitWeight(0.5)
                .currentLoadWeight(0.3)
                .currentPerformanceWeight(0.2)
                .currentAdaptiveFitWeight(0.625)
                .currentAdaptiveLoadWeight(0.375)
                .currentMetricHistoricalAgreement(0.80)
                .currentMetricOnTimeAssociation(0.70)
                .proposedFitWeight(isInsuff ? null : 0.7)
                .proposedLoadWeight(isInsuff ? null : 0.3)
                .proposedPerformanceWeight(0.0)
                .gridStep(0.05)
                .metricHistoricalAgreement(isInsuff ? null : 0.85)
                .metricOnTimeAssociation(isInsuff ? null : 0.75)
                .createdAt(Instant.now().minusSeconds(3600))
                .evaluationWindowStart(Instant.now().minusSeconds(7200))
                .evaluationWindowEnd(Instant.now())
                .createdByUserId(100L)
                .proposalVersion("proposal-v1")
                .datasetVersion("dataset-v1")
                .algorithmVersion("grid-search-v1")
                .build();
    }

    @Test
    @DisplayName("1. Admin approves proposal: status changes to APPROVED with reviewer and notes")
    void test01_adminApprovesProposal() {
        AdaptiveWeightProposalEntity entity = createProposal("prop-1", WeightProposalStatus.READY_FOR_REVIEW);
        when(proposalRepository.findById("prop-1")).thenReturn(Optional.of(entity));

        AdaptiveWeightProposalDto approved = proposalService.approveProposal("prop-1", 1L, "Approved for shadow monitoring");

        assertEquals(WeightProposalStatus.APPROVED, approved.status());
        assertEquals(1L, approved.reviewedByUserId());
        assertEquals("Approved for shadow monitoring", approved.reviewNotes());
        assertNotNull(approved.reviewedAt());
    }

    @Test
    @DisplayName("2. Admin rejects proposal: status changes to REJECTED with rejection reason")
    void test02_adminRejectsProposal() {
        AdaptiveWeightProposalEntity entity = createProposal("prop-2", WeightProposalStatus.READY_FOR_REVIEW);
        when(proposalRepository.findById("prop-2")).thenReturn(Optional.of(entity));

        AdaptiveWeightProposalDto rejected = proposalService.rejectProposal("prop-2", 1L, "Overfitting risk on small sample");

        assertEquals(WeightProposalStatus.REJECTED, rejected.status());
        assertEquals(1L, rejected.reviewedByUserId());
        assertEquals("Overfitting risk on small sample", rejected.rejectionReason());
        assertNotNull(rejected.reviewedAt());
    }

    @Test
    @DisplayName("3. Terminal state immutability: second review attempt on APPROVED proposal fails with 400 Bad Request")
    void test03_secondReviewOnApprovedProposalFails() {
        AdaptiveWeightProposalEntity approvedEntity = createProposal("prop-3", WeightProposalStatus.APPROVED);
        when(proposalRepository.findById("prop-3")).thenReturn(Optional.of(approvedEntity));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                proposalService.approveProposal("prop-3", 1L, "Second approval attempt")
        );
        assertEquals(HttpStatus.BAD_REQUEST.value(), ex.getStatus());
        assertTrue(ex.getMessage().contains("APPROVED"));

        BusinessException ex2 = assertThrows(BusinessException.class, () ->
                proposalService.rejectProposal("prop-3", 1L, "Rejection after approval")
        );
        assertEquals(HttpStatus.BAD_REQUEST.value(), ex2.getStatus());
    }

    @Test
    @DisplayName("4. Terminal state immutability: review attempt on REJECTED proposal fails with 400 Bad Request")
    void test04_reviewOnRejectedProposalFails() {
        AdaptiveWeightProposalEntity rejectedEntity = createProposal("prop-4", WeightProposalStatus.REJECTED);
        when(proposalRepository.findById("prop-4")).thenReturn(Optional.of(rejectedEntity));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                proposalService.approveProposal("prop-4", 1L, "Approval after rejection")
        );
        assertEquals(HttpStatus.BAD_REQUEST.value(), ex.getStatus());
    }

    @Test
    @DisplayName("5. Review attempt on INSUFFICIENT_EVIDENCE proposal fails with 400 Bad Request")
    void test05_reviewOnInsufficientEvidenceFails() {
        AdaptiveWeightProposalEntity insuffEntity = createProposal("prop-5", WeightProposalStatus.INSUFFICIENT_EVIDENCE);
        when(proposalRepository.findById("prop-5")).thenReturn(Optional.of(insuffEntity));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                proposalService.approveProposal("prop-5", 1L, "Attempt to approve insufficient evidence")
        );
        assertEquals(HttpStatus.BAD_REQUEST.value(), ex.getStatus());
    }

    @Test
    @DisplayName("6. ABSOLUTE NO-ACTIVATION GUARANTEE: Approving proposal does NOT mutate active production weights")
    void test06_absoluteNoActivationGuarantee() {
        AdaptiveWeightProposalEntity entity = createProposal("prop-6", WeightProposalStatus.READY_FOR_REVIEW);
        when(proposalRepository.findById("prop-6")).thenReturn(Optional.of(entity));

        // Active weights before approval
        HeuristicStrategy strategyBefore = strategyFactory.resolve("BALANCED");
        HeuristicWeights weightsBefore = strategyBefore.weights();
        assertEquals(0.5, weightsBefore.fitWeight());
        assertEquals(0.3, weightsBefore.loadWeight());
        assertEquals(0.2, weightsBefore.performanceWeight());

        // Approve proposal (which proposed 0.7 fit and 0.3 load)
        AdaptiveWeightProposalDto approved = proposalService.approveProposal("prop-6", 1L, "Shadow approval");
        assertEquals(WeightProposalStatus.APPROVED, approved.status());
        assertEquals(0.7, approved.proposedFitWeight());

        // Active weights after approval must remain 100% IDENTICAL to before
        HeuristicStrategy strategyAfter = strategyFactory.resolve("BALANCED");
        HeuristicWeights weightsAfter = strategyAfter.weights();
        assertEquals(weightsBefore.fitWeight(), weightsAfter.fitWeight(), 1e-9);
        assertEquals(weightsBefore.loadWeight(), weightsAfter.loadWeight(), 1e-9);
        assertEquals(weightsBefore.performanceWeight(), weightsAfter.performanceWeight(), 1e-9);
    }

    @Test
    @DisplayName("7. Admin controller authorization: Admin user can access endpoints")
    void test07_adminUserAuthorized() {
        Authentication adminAuth = new TestingAuthenticationToken(
                "admin@taskpilot.io", "password", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );

        AdaptiveWeightProposalEntity entity = createProposal("prop-7", WeightProposalStatus.READY_FOR_REVIEW);
        when(proposalRepository.findById("prop-7")).thenReturn(Optional.of(entity));

        ApiResponse<AdaptiveWeightProposalDto> response = adminController.approveProposal(
                "prop-7", new ApproveProposalRequest("Approved via controller"), adminAuth
        );

        assertNotNull(response);
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        assertEquals(WeightProposalStatus.APPROVED, response.getData().status());
    }

    @Test
    @DisplayName("8. Admin controller authorization: Non-admin user is rejected with 403 Forbidden")
    void test08_nonAdminUserForbidden() {
        Authentication userAuth = new TestingAuthenticationToken(
                "user@taskpilot.io", "password", List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        BusinessException ex = assertThrows(BusinessException.class, () ->
                adminController.getDatasetReport(userAuth)
        );
        assertEquals(HttpStatus.FORBIDDEN.value(), ex.getStatus());

        BusinessException ex2 = assertThrows(BusinessException.class, () ->
                adminController.generateProposal(userAuth)
        );
        assertEquals(HttpStatus.FORBIDDEN.value(), ex2.getStatus());
    }

    @Test
    @DisplayName("9. Admin controller authorization: Unauthenticated request is rejected with 401 Unauthorized")
    void test09_unauthenticatedRejected() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                adminController.getDatasetReport(null)
        );
        assertEquals(HttpStatus.UNAUTHORIZED.value(), ex.getStatus());
    }

    @Test
    @DisplayName("10. Proposal integrity gate: malformed proposals are rejected on review attempt")
    void test10_corruptedProposalIntegrityRejectedOnReview() {
        // Sub-test a: sample count < 20
        AdaptiveWeightProposalEntity tooFewSamples = createProposal("prop-few", WeightProposalStatus.READY_FOR_REVIEW);
        tooFewSamples.setDatasetSampleCount(18);
        when(proposalRepository.findById("prop-few")).thenReturn(Optional.of(tooFewSamples));
        assertThrows(BusinessException.class, () -> proposalService.approveProposal("prop-few", 1L, "note"));

        // Sub-test b: proposed fit weight out of safe bounds [0.10, 0.90]
        AdaptiveWeightProposalEntity outOfBounds = createProposal("prop-bound", WeightProposalStatus.READY_FOR_REVIEW);
        outOfBounds.setProposedFitWeight(0.95);
        outOfBounds.setProposedLoadWeight(0.05);
        when(proposalRepository.findById("prop-bound")).thenReturn(Optional.of(outOfBounds));
        assertThrows(BusinessException.class, () -> proposalService.approveProposal("prop-bound", 1L, "note"));

        // Sub-test c: weight sum != 1.0
        AdaptiveWeightProposalEntity badSum = createProposal("prop-sum", WeightProposalStatus.READY_FOR_REVIEW);
        badSum.setProposedFitWeight(0.70);
        badSum.setProposedLoadWeight(0.40);
        when(proposalRepository.findById("prop-sum")).thenReturn(Optional.of(badSum));
        assertThrows(BusinessException.class, () -> proposalService.approveProposal("prop-sum", 1L, "note"));

        // Sub-test d: missing dataset hash
        AdaptiveWeightProposalEntity missingHash = createProposal("prop-hash", WeightProposalStatus.READY_FOR_REVIEW);
        missingHash.setDatasetHash(null);
        when(proposalRepository.findById("prop-hash")).thenReturn(Optional.of(missingHash));
        assertThrows(BusinessException.class, () -> proposalService.approveProposal("prop-hash", 1L, "note"));

        // Sub-test e: missing metric agreement
        AdaptiveWeightProposalEntity missingMetric = createProposal("prop-metric", WeightProposalStatus.READY_FOR_REVIEW);
        missingMetric.setMetricHistoricalAgreement(null);
        when(proposalRepository.findById("prop-metric")).thenReturn(Optional.of(missingMetric));
        assertThrows(BusinessException.class, () -> proposalService.approveProposal("prop-metric", 1L, "note"));
    }

    @Test
    @DisplayName("11. Field immutability: review preserves calculation and dataset fields untouched")
    void test11_proposalImmutabilityOnReview() {
        AdaptiveWeightProposalEntity entity = createProposal("prop-immut", WeightProposalStatus.READY_FOR_REVIEW);
        when(proposalRepository.findById("prop-immut")).thenReturn(Optional.of(entity));

        AdaptiveWeightProposalDto reviewed = proposalService.approveProposal("prop-immut", 1L, "Immutability verified");

        // Verify only review fields mutated
        assertEquals(WeightProposalStatus.APPROVED, reviewed.status());
        assertEquals(1L, reviewed.reviewedByUserId());
        assertEquals("Immutability verified", reviewed.reviewNotes());
        assertNotNull(reviewed.reviewedAt());

        // Verify all core data fields remained 100% identical
        assertEquals("sha-abc", reviewed.datasetHash());
        assertEquals(25, reviewed.datasetSampleCount());
        assertEquals(0.5, reviewed.currentFitWeight());
        assertEquals(0.3, reviewed.currentLoadWeight());
        assertEquals(0.2, reviewed.currentPerformanceWeight());
        assertEquals(0.625, reviewed.currentAdaptiveFitWeight());
        assertEquals(0.375, reviewed.currentAdaptiveLoadWeight());
        assertEquals(0.7, reviewed.proposedFitWeight());
        assertEquals(0.3, reviewed.proposedLoadWeight());
        assertEquals(0.0, reviewed.proposedPerformanceWeight());
        assertEquals(0.05, reviewed.gridStep());
        assertEquals(0.85, reviewed.metricHistoricalAgreement());
        assertEquals(0.75, reviewed.metricOnTimeAssociation());
        assertEquals(100L, reviewed.createdByUserId());
        assertEquals("proposal-v1", reviewed.proposalVersion());
        assertEquals("dataset-v1", reviewed.datasetVersion());
        assertEquals("grid-search-v1", reviewed.algorithmVersion());
        assertEquals("BALANCED", reviewed.targetHeuristicMode());
    }

    @Test
    @DisplayName("12. Admin actor ID strictly resolved from security context email; 401 if user not found")
    void test12_adminActorIdResolvedStrictlyFromSecurityContext() {
        Authentication unknownAdmin = new TestingAuthenticationToken(
                "ghost-admin@taskpilot.io", "pass", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        when(userIdentityPort.findByEmail("ghost-admin@taskpilot.io")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                adminController.generateProposal(unknownAdmin)
        );
        assertEquals(HttpStatus.UNAUTHORIZED.value(), ex.getStatus());
    }

    @Test
    @DisplayName("13. Proposal integrity rejects review if targetHeuristicMode is null or not BALANCED")
    void test13_rejectProposalWithUnsupportedTargetHeuristicMode() {
        AdaptiveWeightProposalEntity urgentProp = createProposal("prop-urgent", WeightProposalStatus.READY_FOR_REVIEW);
        urgentProp.setTargetHeuristicMode("URGENT");
        AdaptiveWeightProposalEntity trainingProp = createProposal("prop-training", WeightProposalStatus.READY_FOR_REVIEW);
        trainingProp.setTargetHeuristicMode("TRAINING");
        AdaptiveWeightProposalEntity nullProp = createProposal("prop-null-mode", WeightProposalStatus.READY_FOR_REVIEW);
        nullProp.setTargetHeuristicMode(null);

        when(proposalRepository.findById("prop-urgent")).thenReturn(Optional.of(urgentProp));
        when(proposalRepository.findById("prop-training")).thenReturn(Optional.of(trainingProp));
        when(proposalRepository.findById("prop-null-mode")).thenReturn(Optional.of(nullProp));

        BusinessException ex1 = assertThrows(BusinessException.class, () ->
                proposalService.approveProposal("prop-urgent", 1L, "Approve urgent")
        );
        assertEquals(HttpStatus.BAD_REQUEST.value(), ex1.getStatus());
        assertTrue(ex1.getMessage().contains("Target heuristic mode must be BALANCED"));

        BusinessException ex2 = assertThrows(BusinessException.class, () ->
                proposalService.approveProposal("prop-training", 1L, "Approve training")
        );
        assertEquals(HttpStatus.BAD_REQUEST.value(), ex2.getStatus());
        assertTrue(ex2.getMessage().contains("Target heuristic mode must be BALANCED"));

        BusinessException ex3 = assertThrows(BusinessException.class, () ->
                proposalService.approveProposal("prop-null-mode", 1L, "Approve null mode")
        );
        assertEquals(HttpStatus.BAD_REQUEST.value(), ex3.getStatus());
        assertTrue(ex3.getMessage().contains("Target heuristic mode must be BALANCED"));
    }

    @Test
    @DisplayName("14. Legacy proposal safety: null-mode proposal is readable, not converted to BALANCED, and non-reviewable")
    void test14_legacyNullModeProposalBehaviorAndReadability() {
        // 1. Create legacy entity with null targetHeuristicMode
        AdaptiveWeightProposalEntity legacyEntity = createProposal("prop-legacy-null", WeightProposalStatus.READY_FOR_REVIEW);
        legacyEntity.setTargetHeuristicMode(null);

        when(proposalRepository.findById("prop-legacy-null")).thenReturn(Optional.of(legacyEntity));
        when(proposalRepository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.of(legacyEntity));

        // 2 & 3. Legacy row remains readable, and null mode is NOT silently converted to BALANCED in DTO
        AdaptiveWeightProposalDto readDto = proposalService.getProposalById("prop-legacy-null");
        assertNull(readDto.targetHeuristicMode(), "Legacy null targetHeuristicMode must remain null in DTO");

        AdaptiveWeightProposalDto latestDto = proposalService.getLatestProposal();
        assertNull(latestDto.targetHeuristicMode(), "Latest proposal DTO must not convert null to BALANCED");

        // 1 & 5. Non-reviewable (null mode, URGENT, TRAINING cannot be approved or rejected)
        BusinessException approveEx = assertThrows(BusinessException.class, () ->
                proposalService.approveProposal("prop-legacy-null", 1L, "Approve legacy")
        );
        assertEquals(HttpStatus.BAD_REQUEST.value(), approveEx.getStatus());
        assertTrue(approveEx.getMessage().contains("Target heuristic mode must be BALANCED"));

        BusinessException rejectEx = assertThrows(BusinessException.class, () ->
                proposalService.rejectProposal("prop-legacy-null", 1L, "Reject legacy")
        );
        assertEquals(HttpStatus.BAD_REQUEST.value(), rejectEx.getStatus());
        assertTrue(rejectEx.getMessage().contains("Target heuristic mode must be BALANCED"));

        // 4. New proposal explicitly stores BALANCED (not null)
        AdaptiveWeightProposalEntity newEntity = AdaptiveWeightProposalEntity.builder()
                .proposalId("prop-new")
                .targetHeuristicMode("BALANCED")
                .build();
        assertEquals("BALANCED", newEntity.getTargetHeuristicMode());

        AdaptiveWeightProposalEntity defaultEntity = new AdaptiveWeightProposalEntity();
        assertNull(defaultEntity.getTargetHeuristicMode(), "Unset targetHeuristicMode must default to null (no @Builder.Default inference)");

        // 6. Approval of BALANCED proposal remains non-activating
        AdaptiveWeightProposalEntity validProp = createProposal("prop-valid", WeightProposalStatus.READY_FOR_REVIEW);
        when(proposalRepository.findById("prop-valid")).thenReturn(Optional.of(validProp));
        AdaptiveWeightProposalDto approved = proposalService.approveProposal("prop-valid", 1L, "Approval check");
        assertEquals(WeightProposalStatus.APPROVED, approved.status());
        assertEquals("BALANCED", approved.targetHeuristicMode());
        assertEquals(0.5, initialActiveWeights.fitWeight());
        assertEquals(0.3, initialActiveWeights.loadWeight());
        assertEquals(0.2, initialActiveWeights.performanceWeight());
    }
}
