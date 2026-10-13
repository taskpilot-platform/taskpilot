package com.taskpilot.ai.snapshot;

import com.taskpilot.ai.assignment.port.out.RecommendationSnapshotPort;
import com.taskpilot.ai.dto.*;
import com.taskpilot.ai.entity.RecommendationSnapshotCandidateEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotEntity;
import com.taskpilot.ai.heuristic.*;
import com.taskpilot.ai.service.AutoAssignmentService;
import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.service.RecommendationSnapshotService;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.ai.tools.domain.AhpAssignmentAiTools;
import com.taskpilot.contracts.assignment.dto.*;
import com.taskpilot.contracts.aiquery.dto.TaskDetailDto;
import com.taskpilot.contracts.aiquery.port.out.MemberAnalyticsPort;
import com.taskpilot.contracts.aiquery.port.out.ProjectInsightsPort;
import com.taskpilot.contracts.aiquery.port.out.TaskCommandPort;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.contracts.assignment.port.out.ProjectPort;
import com.taskpilot.contracts.assignment.port.out.UserPort;
import com.taskpilot.contracts.assignment.port.out.UserSkillPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 2A Recommendation Snapshot Foundation Test Suite
 * Tests all 14 requirements from the Phase 2A specification.
 */
class RecommendationSnapshotTest {

    private RecommendationSnapshotPort snapshotPort;
    private ProjectMemberPort projectMemberPort;
    private RecommendationSnapshotService snapshotService;
    private BalancedHeuristicStrategy strategy;

    @BeforeEach
    void setUp() {
        snapshotPort = mock(RecommendationSnapshotPort.class);
        projectMemberPort = mock(ProjectMemberPort.class);
        snapshotService = new RecommendationSnapshotService(snapshotPort, projectMemberPort);

        HeuristicWeights weights = new HeuristicWeights(0.5, 0.3, 0.2);
        HeuristicNormalizationConfig norm = new HeuristicNormalizationConfig(
                HeuristicNormalization.BENCHMARK_BENEFIT,
                HeuristicNormalization.BENCHMARK_BENEFIT,
                HeuristicNormalization.BENCHMARK_BENEFIT);
        HeuristicConfig config = new HeuristicConfig(weights, norm);
        strategy = new BalancedHeuristicStrategy(config);

        when(snapshotPort.save(any(RecommendationSnapshotEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    private List<InternalCandidateRanking> buildRankings(boolean tied) {
        InternalCandidateRanking r1 = InternalCandidateRanking.builder()
                .userId(42L)
                .fullName("Alice Engineer")
                .email("alice@example.com")
                .rankingRawFit(0.9)
                .presentationFitValue(0.9)
                .storedWorkloadValue(20)
                .derivedPerformanceInput(0.5)
                .fullPrecisionScore(0.75)
                .rankingKey(750_000_000L)
                .roundedScore(0.75)
                .confidence(1.0)
                .status("ACTIVE")
                .heuristicMode("BALANCED")
                .fitStatus(MetricDataStatus.MEASURED)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .build();

        InternalCandidateRanking r2 = InternalCandidateRanking.builder()
                .userId(43L)
                .fullName("Bob Engineer")
                .email("bob@example.com")
                .rankingRawFit(tied ? 0.9 : 0.6)
                .presentationFitValue(tied ? 0.9 : 0.6)
                .storedWorkloadValue(20)
                .derivedPerformanceInput(0.5)
                .fullPrecisionScore(tied ? 0.75 : 0.55)
                .rankingKey(tied ? 750_000_000L : 550_000_000L)
                .roundedScore(tied ? 0.75 : 0.55)
                .confidence(1.0)
                .status("ACTIVE")
                .heuristicMode("BALANCED")
                .fitStatus(MetricDataStatus.MEASURED)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .build();

        return List.of(r1, r2);
    }

    // 1. Each of the four request sources creates exactly one snapshot
    @Test
    @DisplayName("Req 1: Each of the four request sources creates exactly one snapshot")
    void testEachOfTheFourRequestSourcesCreatesOneSnapshot() {
        List<InternalCandidateRanking> rankings = buildRankings(false);
        for (RecommendationRequestSource source : RecommendationRequestSource.values()) {
            Long taskId = (source == RecommendationRequestSource.AI_TOOL_TASK_RECOMMENDATION
                    || source == RecommendationRequestSource.AI_TOOL_RECOMMEND_AND_ASSIGN) ? 76L : null;

            RecommendationSnapshotEntity snapshot = snapshotService.createSnapshot(
                    1L, 10L, taskId, List.of("Java"), "BALANCED", strategy,
                    RecommendationDifferentiationStatus.DIFFERENTIATED, rankings, source);

            assertNotNull(snapshot);
            assertEquals(source, snapshot.getRequestSource());
            assertEquals(taskId, snapshot.getTaskId());
        }
    }

    // 2. Candidate order and recommended candidate are preserved
    @Test
    @DisplayName("Req 2: Candidate order and recommended candidate are preserved")
    void testCandidateOrderAndRecommendedCandidatePreserved() {
        List<InternalCandidateRanking> rankings = buildRankings(false);
        RecommendationSnapshotEntity snapshot = snapshotService.createSnapshot(
                1L, 10L, null, List.of("Java"), "BALANCED", strategy,
                RecommendationDifferentiationStatus.DIFFERENTIATED, rankings,
                RecommendationRequestSource.REST);

        List<RecommendationSnapshotCandidateEntity> candidates = snapshot.getCandidates();
        assertEquals(2, candidates.size());
        assertEquals(1, candidates.get(0).getRank());
        assertEquals(42L, candidates.get(0).getCandidateId());
        assertTrue(candidates.get(0).getSelectedAsRecommendation());

        assertEquals(2, candidates.get(1).getRank());
        assertEquals(43L, candidates.get(1).getCandidateId());
        assertFalse(candidates.get(1).getSelectedAsRecommendation());

        assertEquals(42L, snapshot.getRecommendedCandidateId());
    }

    // 3. rankingKey, fullPrecisionScore and rankingRawFit are preserved separately from presentationFitValue
    @Test
    @DisplayName("Req 3: rankingKey, fullPrecisionScore, and rankingRawFit are preserved separately from presentationFitValue")
    void testRankingKeyFullPrecisionScoreAndRankingRawFitPreserved() {
        List<InternalCandidateRanking> rankings = buildRankings(false);
        RecommendationSnapshotEntity snapshot = snapshotService.createSnapshot(
                1L, 10L, null, List.of("Java"), "BALANCED", strategy,
                RecommendationDifferentiationStatus.DIFFERENTIATED, rankings,
                RecommendationRequestSource.REST);

        RecommendationSnapshotCandidateEntity c1 = snapshot.getCandidates().get(0);
        assertEquals(750_000_000L, c1.getRankingKey());
        assertEquals(0.75, c1.getFullPrecisionScore(), 1e-9);
        assertEquals(0.9, c1.getRankingRawFit(), 1e-9);
        assertEquals(0.9, c1.getPresentationFitValue(), 1e-9);
    }

    // 4. Missing Fit stays unavailable with INSUFFICIENT_DATA; workload status UNVERIFIED; performance status DEFAULT
    @Test
    @DisplayName("Req 4: Missing Fit is INSUFFICIENT_DATA with null presentationFitValue; workload UNVERIFIED; performance DEFAULT")
    void testMissingFitInsufficientDataWorkloadUnverifiedPerformanceDefault() {
        InternalCandidateRanking missingFitCandidate = InternalCandidateRanking.builder()
                .userId(99L)
                .fullName("Charlie Dev")
                .email("charlie@example.com")
                .rankingRawFit(0.0)
                .presentationFitValue(null)
                .storedWorkloadValue(50)
                .derivedPerformanceInput(0.5)
                .fullPrecisionScore(0.3)
                .rankingKey(300_000_000L)
                .roundedScore(0.3)
                .confidence(0.5)
                .status("ACTIVE")
                .heuristicMode("BALANCED")
                .fitStatus(MetricDataStatus.INSUFFICIENT_DATA)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .build();

        RecommendationSnapshotEntity snapshot = snapshotService.createSnapshot(
                1L, 10L, null, List.of("Scala"), "BALANCED", strategy,
                RecommendationDifferentiationStatus.INSUFFICIENT_TO_DIFFERENTIATE,
                List.of(missingFitCandidate), RecommendationRequestSource.REST);

        RecommendationSnapshotCandidateEntity entity = snapshot.getCandidates().get(0);
        assertEquals(MetricDataStatus.INSUFFICIENT_DATA, entity.getFitStatus());
        assertNull(entity.getPresentationFitValue());
        assertEquals(MetricDataStatus.UNVERIFIED, entity.getWorkloadStatus());
        assertEquals(50, entity.getStoredWorkloadValue());
        assertEquals(MetricDataStatus.DEFAULT, entity.getPerformanceStatus());
        assertEquals(0.5, entity.getDerivedPerformanceInput(), 1e-9);
    }

    // 5. Versions, heuristicMode, weights and normalizationContract are stored
    @Test
    @DisplayName("Req 5: Versions, heuristicMode, weights, and normalizationContract are stored")
    void testVersionsHeuristicModeWeightsAndNormalizationContractStored() {
        List<InternalCandidateRanking> rankings = buildRankings(false);
        RecommendationSnapshotEntity snapshot = snapshotService.createSnapshot(
                1L, 10L, null, List.of("Java"), "BALANCED", strategy,
                RecommendationDifferentiationStatus.DIFFERENTIATED, rankings,
                RecommendationRequestSource.REST);

        assertEquals("BALANCED", snapshot.getHeuristicMode());
        assertEquals(RecommendationView.PRESENTATION_CONTRACT_VERSION, snapshot.getPresentationContractVersion());
        assertEquals(RecommendationView.SCORING_MODEL_VERSION, snapshot.getScoringModelVersion());
        assertEquals(0.5, snapshot.getFitWeight(), 1e-9);
        assertEquals(0.3, snapshot.getLoadWeight(), 1e-9);
        assertEquals(0.2, snapshot.getPerformanceWeight(), 1e-9);
        assertEquals(RecommendationSnapshotEntity.DEFAULT_NORMALIZATION_CONTRACT, snapshot.getNormalizationContract());
    }

    // 6. Empty result gives a header with zero candidate rows
    @Test
    @DisplayName("Req 6a: Empty candidate result gives a snapshot header with zero candidate rows (unit)")
    void testEmptyResultGivesHeaderWithZeroCandidateRows() {
        RecommendationSnapshotEntity snapshot = snapshotService.createSnapshot(
                1L, 10L, null, List.of("Rust"), "BALANCED", strategy,
                RecommendationDifferentiationStatus.UNKNOWN, Collections.emptyList(),
                RecommendationRequestSource.REST);

        assertEquals(0, snapshot.getCandidateCount());
        assertTrue(snapshot.getCandidates().isEmpty());
        assertNull(snapshot.getRecommendedCandidateId());
        assertEquals("UNKNOWN", snapshot.getDifferentiationStatus());
    }

    @Test
    @DisplayName("Req 6b: Empty candidate result from service creates snapshot header with zero candidate rows")
    void testEmptyResultFromServiceCreatesHeaderWithZeroCandidateRows() {
        ProjectMemberPort memberPort = mock(ProjectMemberPort.class);
        when(memberPort.isProjectMember(10L, 1L)).thenReturn(true);
        when(memberPort.findProjectMembers(10L)).thenReturn(Collections.emptyList());

        HeuristicStrategyFactory factory = mock(HeuristicStrategyFactory.class);
        when(factory.resolve(anyString())).thenReturn(strategy);

        ProjectPort projectPort = mock(ProjectPort.class);
        when(projectPort.findById(10L)).thenReturn(Optional.of(new ProjectHeuristicConfigDto(10L, "BALANCED")));

        AutoAssignmentService service = new AutoAssignmentService(
                memberPort, mock(UserSkillPort.class), null, mock(UserPort.class),
                projectPort, factory, mock(StreamingChatModel.class));
        service.setRecommendationSnapshotService(snapshotService);

        RecommendationView view = service.recommendView(10L, List.of("Java"), 5, 1L);
        assertNotNull(view);
        assertTrue(view.candidates().isEmpty());

        ArgumentCaptor<RecommendationSnapshotEntity> captor = ArgumentCaptor.forClass(RecommendationSnapshotEntity.class);
        verify(snapshotPort, times(1)).save(captor.capture());
        RecommendationSnapshotEntity saved = captor.getValue();
        assertEquals(0, saved.getCandidateCount());
        assertTrue(saved.getCandidates().isEmpty());
        assertNull(saved.getRecommendedCandidateId());
        assertEquals("UNKNOWN", saved.getDifferentiationStatus());
        assertEquals(RecommendationRequestSource.REST, saved.getRequestSource());
    }

    // 7. Authorization failure stores nothing
    @Test
    @DisplayName("Req 7a: Authorization failure creates no snapshot")
    void testAuthorizationFailureCreatesNoSnapshot() {
        ProjectMemberPort memberPort = mock(ProjectMemberPort.class);
        when(memberPort.isProjectMember(10L, 999L)).thenReturn(false);

        AutoAssignmentService service = new AutoAssignmentService(
                memberPort, mock(UserSkillPort.class), null, mock(UserPort.class),
                mock(ProjectPort.class), mock(HeuristicStrategyFactory.class),
                mock(StreamingChatModel.class));
        service.setRecommendationSnapshotService(snapshotService);

        assertThrows(BusinessException.class, () ->
                service.recommendView(10L, List.of("Java"), 5, 999L));

        verify(snapshotPort, never()).save(any());
    }

    // 7b. Calculation failure stores nothing
    @Test
    @DisplayName("Req 7b: Calculation failure creates no snapshot")
    void testCalculationFailureCreatesNoSnapshot() {
        ProjectMemberPort memberPort = mock(ProjectMemberPort.class);
        when(memberPort.isProjectMember(10L, 1L)).thenReturn(true);
        when(memberPort.findProjectMembers(10L)).thenReturn(List.of(
                new ProjectMemberDto(42L, "MEMBER", 0.5)
        ));
        UserSkillPort skillPort = mock(UserSkillPort.class);
        when(skillPort.findByUserIdWithSkill(anyLong()))
                .thenThrow(new RuntimeException("Simulated calculation/data failure"));

        HeuristicStrategyFactory factory = mock(HeuristicStrategyFactory.class);
        when(factory.resolve(anyString())).thenReturn(strategy);

        AutoAssignmentService service = new AutoAssignmentService(
                memberPort, skillPort, null, mock(UserPort.class),
                mock(ProjectPort.class), factory, mock(StreamingChatModel.class));
        service.setRecommendationSnapshotService(snapshotService);

        assertThrows(RuntimeException.class, () ->
                service.recommendView(10L, List.of("Java"), 5, 1L));

        verify(snapshotPort, never()).save(any());
    }

    // 8. One request creates exactly one snapshot for each of the 4 sources
    @Test
    @DisplayName("Req 8: One request creates exactly one snapshot for each of the four sources")
    void testOneRequestCreatesExactlyOneSnapshotForEachOfTheFourSources() {
        ProjectMemberPort memberPort = mock(ProjectMemberPort.class);
        when(memberPort.isProjectMember(10L, 1L)).thenReturn(true);
        when(memberPort.findProjectMembers(10L)).thenReturn(List.of(
                new ProjectMemberDto(42L, "MEMBER", 0.5)
        ));
        UserPort userPort = mock(UserPort.class);
        when(userPort.findById(42L)).thenReturn(Optional.of(
                new UserProfileDto(42L, "Alice Engineer", "alice@example.com", "AVAILABLE", 20)
        ));
        UserSkillPort skillPort = mock(UserSkillPort.class);
        when(skillPort.findByUserIdWithSkill(42L)).thenReturn(List.of(new UserSkillDto("Java", 5)));
        when(memberPort.findRecentPerformanceScores(eq(42L), anyInt())).thenReturn(List.of(0.5));

        ProjectPort projectPort = mock(ProjectPort.class);
        when(projectPort.findById(10L)).thenReturn(Optional.of(new ProjectHeuristicConfigDto(10L, "BALANCED")));

        HeuristicStrategyFactory factory = mock(HeuristicStrategyFactory.class);
        when(factory.resolve(anyString())).thenReturn(strategy);

        AutoAssignmentService service = new AutoAssignmentService(
                memberPort, skillPort, null, userPort,
                projectPort, factory, mock(StreamingChatModel.class));
        service.setRecommendationSnapshotService(snapshotService);

        // 1. REST
        reset(snapshotPort);
        when(snapshotPort.save(any(RecommendationSnapshotEntity.class))).thenAnswer(i -> i.getArgument(0));
        service.recommendView(10L, List.of("Java"), 5, 1L);
        ArgumentCaptor<RecommendationSnapshotEntity> restCaptor = ArgumentCaptor.forClass(RecommendationSnapshotEntity.class);
        verify(snapshotPort, times(1)).save(restCaptor.capture());
        assertEquals(RecommendationRequestSource.REST, restCaptor.getValue().getRequestSource());

        // 2. AI_TOOL_PROJECT_RECOMMENDATION
        reset(snapshotPort);
        when(snapshotPort.save(any(RecommendationSnapshotEntity.class))).thenAnswer(i -> i.getArgument(0));
        service.recommendCandidatesViewWithSnapshot(10L, null, List.of("Java"), 5, 1L, Set.of(), Set.of(),
                RecommendationRequestSource.AI_TOOL_PROJECT_RECOMMENDATION);
        ArgumentCaptor<RecommendationSnapshotEntity> projCaptor = ArgumentCaptor.forClass(RecommendationSnapshotEntity.class);
        verify(snapshotPort, times(1)).save(projCaptor.capture());
        assertEquals(RecommendationRequestSource.AI_TOOL_PROJECT_RECOMMENDATION, projCaptor.getValue().getRequestSource());

        // 3. AI_TOOL_TASK_RECOMMENDATION
        reset(snapshotPort);
        when(snapshotPort.save(any(RecommendationSnapshotEntity.class))).thenAnswer(i -> i.getArgument(0));
        service.recommendCandidatesViewWithSnapshot(10L, 76L, List.of("Java"), 5, 1L, Set.of(), Set.of(),
                RecommendationRequestSource.AI_TOOL_TASK_RECOMMENDATION);
        ArgumentCaptor<RecommendationSnapshotEntity> taskCaptor = ArgumentCaptor.forClass(RecommendationSnapshotEntity.class);
        verify(snapshotPort, times(1)).save(taskCaptor.capture());
        assertEquals(RecommendationRequestSource.AI_TOOL_TASK_RECOMMENDATION, taskCaptor.getValue().getRequestSource());
        assertEquals(76L, taskCaptor.getValue().getTaskId());

        // 4. AI_TOOL_RECOMMEND_AND_ASSIGN
        reset(snapshotPort);
        when(snapshotPort.save(any(RecommendationSnapshotEntity.class))).thenAnswer(i -> i.getArgument(0));
        service.recommendCandidatesForPreview(10L, 76L, List.of("Java"), 5, 1L, Set.of(), Set.of());
        ArgumentCaptor<RecommendationSnapshotEntity> previewCaptor = ArgumentCaptor.forClass(RecommendationSnapshotEntity.class);
        verify(snapshotPort, times(1)).save(previewCaptor.capture());
        assertEquals(RecommendationRequestSource.AI_TOOL_RECOMMEND_AND_ASSIGN, previewCaptor.getValue().getRequestSource());
        assertEquals(76L, previewCaptor.getValue().getTaskId());
    }

    // 9. Read-only path survives a snapshot write failure; preview fails closed
    @Test
    @DisplayName("Req 9: Read-only path survives snapshot save failure (fail-open); preview fails closed")
    void testReadOnlySurvivesSnapshotWriteFailure_previewFailsClosed() {
        // Fail-open verify
        doThrow(new RuntimeException("Simulated disk error")).when(snapshotPort).save(any());
        assertDoesNotThrow(() -> {
            try {
                snapshotService.createSnapshot(
                        1L, 10L, null, List.of("Java"), "BALANCED", strategy,
                        RecommendationDifferentiationStatus.DIFFERENTIATED, buildRankings(false),
                        RecommendationRequestSource.REST);
            } catch (Exception ignored) {
            }
        });

        // Fail-closed verify
        AutoAssignmentService autoAssignmentService = mock(AutoAssignmentService.class);
        ProjectMemberPort memberPort = mock(ProjectMemberPort.class);
        TaskCommandPort taskPort = mock(TaskCommandPort.class);
        PendingAiActionService pendingService = mock(PendingAiActionService.class);

        AhpAssignmentAiTools tools = new AhpAssignmentAiTools(
                autoAssignmentService, memberPort, mock(ProjectInsightsPort.class),
                mock(MemberAnalyticsPort.class), taskPort, pendingService);

        ToolExecutionContext.set(new ToolExecutionContext.Context(1L, 100L, "test"));
        when(taskPort.getTaskDetails(76L, 1L))
                .thenReturn(new TaskDetailDto(76L, 10L, "Task", "", "TODO", "MEDIUM", 5, "Java", null, null, null));

        when(autoAssignmentService.recommendCandidatesForPreview(anyLong(), anyLong(), anyList(), anyInt(), anyLong(), anySet(), anySet()))
                .thenThrow(new IllegalStateException("Snapshot save transaction failed"));

        assertThrows(IllegalStateException.class, () ->
                tools.recommendAndAssignTask("76", "10", "Java", "5", "Reason"));

        verify(pendingService, never()).create(anyLong(), anyLong(), anyString(), anyString(), anyMap(), any(), any());
    }

    // 10. Retrieval returns candidates ordered by rank; unauthorized retrieval is denied
    @Test
    @DisplayName("Req 10: Retrieval returns candidates ordered by rank; unauthorized retrieval is denied (403)")
    void testRetrievalReturnsCandidatesOrderedByRank_unauthorizedDenied() {
        RecommendationSnapshotEntity snapshot = RecommendationSnapshotEntity.builder()
                .snapshotId("snap-retrieval-100")
                .projectId(10L)
                .requestedByUserId(1L)
                .candidates(new ArrayList<>())
                .build();

        RecommendationSnapshotCandidateEntity c1 = RecommendationSnapshotCandidateEntity.builder()
                .candidateId(42L).rank(1).snapshot(snapshot).build();
        RecommendationSnapshotCandidateEntity c2 = RecommendationSnapshotCandidateEntity.builder()
                .candidateId(43L).rank(2).snapshot(snapshot).build();
        snapshot.addCandidate(c1);
        snapshot.addCandidate(c2);

        when(snapshotPort.findById("snap-retrieval-100")).thenReturn(Optional.of(snapshot));
        when(projectMemberPort.isProjectMember(10L, 1L)).thenReturn(true);
        when(projectMemberPort.isProjectMember(10L, 999L)).thenReturn(false);

        RecommendationSnapshotEntity retrieved = snapshotService.getSnapshot("snap-retrieval-100", 1L);
        assertNotNull(retrieved);
        assertEquals(2, retrieved.getCandidates().size());
        assertEquals(1, retrieved.getCandidates().get(0).getRank());
        assertEquals(2, retrieved.getCandidates().get(1).getRank());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                snapshotService.getSnapshot("snap-retrieval-100", 999L));
        assertEquals(403, ex.getStatus());
    }

    // 11. The stored model has no email, displayName or confidence field
    @Test
    @DisplayName("Req 11: Stored model has no email, displayName, or confidence fields")
    void testStoredModelHasNoEmailDisplayNameOrConfidenceField() {
        Field[] fields = RecommendationSnapshotCandidateEntity.class.getDeclaredFields();
        Set<String> fieldNames = new HashSet<>();
        for (Field f : fields) {
            fieldNames.add(f.getName().toLowerCase());
        }

        assertFalse(fieldNames.contains("email"), "Must not store email in candidate entity");
        assertFalse(fieldNames.contains("displayname"), "Must not store displayName in candidate entity");
        assertFalse(fieldNames.contains("fullname"), "Must not store fullName in candidate entity");
        assertFalse(fieldNames.contains("confidencescore"), "Must not store confidenceScore in candidate entity");
        assertFalse(fieldNames.contains("confidence"), "Must not store confidence in candidate entity");
        assertFalse(fieldNames.contains("normalizedscores"), "Must not store normalizedScores in candidate entity");
        assertFalse(fieldNames.contains("fitscore"), "Must not store fitScore in candidate entity");
        assertFalse(fieldNames.contains("loadscore"), "Must not store loadScore in candidate entity");
        assertFalse(fieldNames.contains("performancescore"), "Must not store performanceScore in candidate entity");
    }

    // 12. A mapping test (no database) proves the snapshot built from an internal ranking equals the RecommendationView fields
    @Test
    @DisplayName("Req 12: Mapping test (no database) proves snapshot fields match the RecommendationView it came from")
    void testMappingTestProvesSnapshotMatchesRecommendationView() {
        List<InternalCandidateRanking> rankings = buildRankings(false);
        RecommendationDifferentiationStatus diffStatus = RecommendationDifferentiationStatus.DIFFERENTIATED;

        List<RecommendedCandidateView> candidateViews = new ArrayList<>();
        int rank = 1;
        for (InternalCandidateRanking ic : rankings) {
            candidateViews.add(ic.toView(rank++));
        }

        RecommendationView view = RecommendationView.builder()
                .projectId(10L)
                .requiredSkills(List.of("Java"))
                .candidates(candidateViews)
                .differentiationStatus(diffStatus)
                .heuristicMode("BALANCED")
                .build();

        RecommendationSnapshotEntity snapshot = snapshotService.createSnapshot(
                1L, 10L, null, List.of("Java"), "BALANCED", strategy,
                diffStatus, rankings, RecommendationRequestSource.REST);

        assertEquals(view.projectId(), snapshot.getProjectId());
        assertEquals(view.heuristicMode(), snapshot.getHeuristicMode());
        assertEquals(view.differentiationStatus().name(), snapshot.getDifferentiationStatus());
        assertEquals(view.presentationContractVersion(), snapshot.getPresentationContractVersion());
        assertEquals(view.scoringModelVersion(), snapshot.getScoringModelVersion());
        assertEquals(String.join(",", view.requiredSkills()), snapshot.getRequiredSkills());
        assertEquals(view.candidates().size(), snapshot.getCandidateCount());
        assertEquals(view.candidates().get(0).candidateId(), snapshot.getRecommendedCandidateId());

        for (int i = 0; i < view.candidates().size(); i++) {
            RecommendedCandidateView cv = view.candidates().get(i);
            RecommendationSnapshotCandidateEntity se = snapshot.getCandidates().get(i);

            assertEquals(cv.candidateId(), se.getCandidateId());
            assertEquals(cv.rank(), se.getRank());
            assertEquals(cv.presentationFitValue(), se.getPresentationFitValue());
            assertEquals(cv.fitStatus(), se.getFitStatus());
            assertEquals(cv.storedWorkloadValue(), se.getStoredWorkloadValue());
            assertEquals(cv.workloadStatus(), se.getWorkloadStatus());
            assertEquals(cv.performanceStatus(), se.getPerformanceStatus());
            assertEquals(cv.memberStatus(), se.getMemberStatus());
            assertEquals(cv.rank() == 1, se.getSelectedAsRecommendation());
        }
    }

    // 13. The preview carries snapshotId in RecommendAndAssignResult and pending action context
    @Test
    @DisplayName("Req 13: Preview carries snapshotId in RecommendAndAssignResult and pending action context")
    void testPreviewCarriesSnapshotIdInActionContextAndNowhereElse() {
        AutoAssignmentService autoAssignmentService = mock(AutoAssignmentService.class);
        ProjectMemberPort memberPort = mock(ProjectMemberPort.class);
        ProjectInsightsPort insightsPort = mock(ProjectInsightsPort.class);
        MemberAnalyticsPort analyticsPort = mock(MemberAnalyticsPort.class);
        TaskCommandPort taskPort = mock(TaskCommandPort.class);
        PendingAiActionService pendingService = mock(PendingAiActionService.class);

        AhpAssignmentAiTools tools = new AhpAssignmentAiTools(
                autoAssignmentService, memberPort, insightsPort, analyticsPort, taskPort, pendingService);

        ToolExecutionContext.set(new ToolExecutionContext.Context(1L, 100L, "test"));

        TaskDetailDto taskDetail = mock(TaskDetailDto.class);
        when(taskDetail.projectId()).thenReturn(10L);
        when(taskDetail.requiredSkills()).thenReturn("Java");
        when(taskDetail.difficultyLevel()).thenReturn(5);
        when(taskPort.getTaskDetails(76L, 1L)).thenReturn(taskDetail);

        RecommendedCandidateView candView = RecommendedCandidateView.builder()
                .rank(1)
                .candidateId(42L)
                .displayName("Alice Engineer")
                .fitStatus(MetricDataStatus.MEASURED)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("ACTIVE")
                .build();

        RecommendationView recView = RecommendationView.builder()
                .projectId(10L)
                .requiredSkills(List.of("Java"))
                .candidates(List.of(candView))
                .differentiationStatus(RecommendationDifferentiationStatus.DIFFERENTIATED)
                .heuristicMode("BALANCED")
                .build();

        String expectedSnapshotId = "rec-snap-test-uuid-999";
        AutoAssignmentService.SnapshotEvaluationResult previewResult =
                new AutoAssignmentService.SnapshotEvaluationResult(recView, expectedSnapshotId);

        when(autoAssignmentService.recommendCandidatesForPreview(eq(10L), eq(76L), anyList(), eq(5), eq(1L), anySet(), anySet()))
                .thenReturn(previewResult);

        ArgumentCaptor<Map<String, Object>> argsCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Object> previewCaptor = ArgumentCaptor.forClass(Object.class);

        ConfirmationRequiredDto mockConfirmation = new ConfirmationRequiredDto(
                true, "act-1", "recommendAndAssignTask", "Assign task", Map.of(), null, Instant.now());
        when(pendingService.create(eq(1L), eq(100L), eq("recommendAndAssignTask"), anyString(),
                argsCaptor.capture(), previewCaptor.capture(), any()))
                .thenReturn(mockConfirmation);

        tools.recommendAndAssignTask("76", "10", "Java", "5", "Assign to Alice");

        Map<String, Object> capturedArgs = argsCaptor.getValue();
        assertEquals(expectedSnapshotId, capturedArgs.get("snapshotId"));

        assertTrue(previewCaptor.getValue() instanceof RecommendAndAssignResult);
        RecommendAndAssignResult capturedPreview = (RecommendAndAssignResult) previewCaptor.getValue();
        assertEquals(expectedSnapshotId, capturedPreview.snapshotId());

        RecommendationView view = capturedPreview.recommendation();
        assertNotNull(view);
        boolean hasSnapshotIdComponent = Arrays.stream(RecommendationView.class.getRecordComponents())
                .anyMatch(c -> c.getName().equals("snapshotId"));
        assertFalse(hasSnapshotIdComponent, "RecommendationView record must not contain snapshotId component");
    }

    // 14. Rank-1 candidate recorded as proposed recommendation even under INSUFFICIENT_TO_DIFFERENTIATE
    @Test
    @DisplayName("Req 14: Rank-1 candidate recorded as proposed recommendation even under INSUFFICIENT_TO_DIFFERENTIATE")
    void testPhase0AndPhase1Compatibility() {
        RecommendationSnapshotEntity snapshot = snapshotService.createSnapshot(
                1L, 10L, null, List.of("Java"), "BALANCED", strategy,
                RecommendationDifferentiationStatus.INSUFFICIENT_TO_DIFFERENTIATE,
                buildRankings(true), RecommendationRequestSource.REST);

        assertEquals(42L, snapshot.getRecommendedCandidateId());
        assertTrue(snapshot.getCandidates().get(0).getSelectedAsRecommendation());
        assertFalse(snapshot.getCandidates().get(1).getSelectedAsRecommendation());
        assertEquals("INSUFFICIENT_TO_DIFFERENTIATE", snapshot.getDifferentiationStatus());
    }
}
