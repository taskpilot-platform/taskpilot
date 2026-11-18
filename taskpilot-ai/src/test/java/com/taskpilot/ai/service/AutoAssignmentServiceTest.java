package com.taskpilot.ai.service;

import com.taskpilot.ai.assignment.port.out.AiAuditPort;
import com.taskpilot.ai.dto.AutoAssignmentResponse;
import com.taskpilot.ai.dto.CandidateScore;
import com.taskpilot.ai.entity.AiLogEntity;
import com.taskpilot.ai.heuristic.HeuristicStrategy;
import com.taskpilot.ai.heuristic.HeuristicStrategyFactory;
import com.taskpilot.ai.heuristic.BalancedHeuristicStrategy;
import com.taskpilot.ai.heuristic.HeuristicConfig;
import com.taskpilot.ai.heuristic.HeuristicNormalization;
import com.taskpilot.ai.heuristic.HeuristicNormalizationConfig;
import com.taskpilot.ai.heuristic.HeuristicWeights;
import com.taskpilot.contracts.assignment.dto.TaskAssignmentMetrics;
import com.taskpilot.contracts.assignment.dto.TaskAssignmentMetrics.DataStatus;
import com.taskpilot.contracts.assignment.dto.TaskAssignmentMetrics.Performance;
import com.taskpilot.contracts.assignment.dto.TaskAssignmentMetrics.Workload;
import com.taskpilot.contracts.assignment.port.out.TaskPerformanceReadPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import com.taskpilot.contracts.assignment.dto.ProjectHeuristicConfigDto;
import com.taskpilot.contracts.assignment.dto.ProjectMemberDto;
import com.taskpilot.contracts.assignment.dto.UserProfileDto;
import com.taskpilot.contracts.assignment.dto.UserSkillDto;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.contracts.assignment.port.out.ProjectPort;
import com.taskpilot.contracts.assignment.port.out.UserPort;
import com.taskpilot.contracts.assignment.port.out.UserSkillPort;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutoAssignmentServiceTest {

    @Mock
    private ProjectMemberPort projectMemberPort;

    @Mock
    private UserSkillPort userSkillPort;

    @Mock
    private AiAuditPort aiAuditPort;

    @Mock
    private UserPort userPort;

    @Mock
    private ProjectPort projectPort;

    @Mock
    private HeuristicStrategyFactory heuristicStrategyFactory;

    @Mock
    private TaskPerformanceReadPort metrics;

    @Mock
    private StreamingChatModel explanationModel;

    private AutoAssignmentService autoAssignmentService;

    private static final Long PROJECT_ID = 10L;
    private static final Long USER_ID = 100L;
    private static final Long REQUESTING_USER_ID = 1L;

    @BeforeEach
    void setUp() {
        autoAssignmentService = new AutoAssignmentService(
                projectMemberPort,
                metrics,
                userSkillPort,
                aiAuditPort,
                userPort,
                projectPort,
                heuristicStrategyFactory,
                explanationModel,
                100L // 100ms timeout for test determinism
        );

        when(projectMemberPort.isProjectMember(PROJECT_ID, REQUESTING_USER_ID)).thenReturn(true);
        when(projectPort.findById(PROJECT_ID))
                .thenReturn(Optional.of(new ProjectHeuristicConfigDto(PROJECT_ID, "BALANCED")));
        when(heuristicStrategyFactory.resolve("BALANCED")).thenReturn(strategy());

        ProjectMemberDto member = new ProjectMemberDto(USER_ID, "MEMBER", 0.9);
        when(projectMemberPort.findProjectMembers(PROJECT_ID)).thenReturn(List.of(member));

        UserProfileDto userProfile = new UserProfileDto(USER_ID, "Alice Engineer", "alice@example.com", "AVAILABLE", 20);
        when(userPort.findById(USER_ID)).thenReturn(Optional.of(userProfile));

        UserSkillDto skill = new UserSkillDto("Java", 5);
        lenient().when(userSkillPort.findByUserIdWithSkill(USER_ID)).thenReturn(List.of(skill));

        lenient().when(metrics.readProjectMetrics(eq(PROJECT_ID), anySet()))
                .thenAnswer(invocation -> Map.of(USER_ID, evidence(USER_ID, 1, 3.5 / 6, 1)));
    }

    @Test
    @DisplayName("Success: LLM succeeds -> recommendation succeeds, scores unchanged, LLM explanation returned")
    void recommend_WhenLlmSucceeds_ReturnsLlmExplanationAndCorrectScores() {
        String expectedExplanation = "Alice Engineer has excellent Java proficiency and balanced workload.";

        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse(expectedExplanation);
            handler.onCompleteResponse(mock(ChatResponse.class));
            return null;
        }).when(explanationModel).chat(anyList(), any(StreamingChatResponseHandler.class));

        AutoAssignmentResponse response = autoAssignmentService.recommend(
                PROJECT_ID, List.of("Java"), 5, REQUESTING_USER_ID);

        assertNotNull(response);
        assertEquals(PROJECT_ID, response.projectId());
        assertEquals(1, response.candidates().size());

        CandidateScore candidate = response.candidates().get(0);
        assertEquals(USER_ID, candidate.getUserId());
        assertEquals(0.23 - 0.65 / 6 + 0.12 * (3.5 / 6), candidate.getTotalScore(), 1e-12);
        assertEquals(expectedExplanation, response.aiExplanation());

        verify(aiAuditPort).save(any(AiLogEntity.class));
    }

    @Test
    @DisplayName("Exception: LLM throws exception -> recommendation succeeds, scores unchanged, fallback explanation returned")
    void recommend_WhenLlmThrowsException_ReturnsFallbackExplanationAndPreservesScores() {
        doThrow(new RuntimeException("Simulated HTTP 503 Service Unavailable"))
                .when(explanationModel).chat(anyList(), any(StreamingChatResponseHandler.class));

        AutoAssignmentResponse response = autoAssignmentService.recommend(
                PROJECT_ID, List.of("Java"), 5, REQUESTING_USER_ID);

        assertNotNull(response);
        assertEquals(PROJECT_ID, response.projectId());
        assertEquals(1, response.candidates().size());

        CandidateScore candidate = response.candidates().get(0);
        assertEquals(USER_ID, candidate.getUserId());
        assertEquals(0.23 - 0.65 / 6 + 0.12 * (3.5 / 6), candidate.getTotalScore(), 1e-12);
        assertEquals(AutoAssignmentService.DEFAULT_FALLBACK_EXPLANATION, response.aiExplanation());

        verify(aiAuditPort).save(any(AiLogEntity.class));
    }

    @Test
    @DisplayName("Timeout: LLM hangs -> recommendation succeeds within bounded timeout, fallback explanation returned")
    void recommend_WhenLlmTimesOut_ReturnsFallbackExplanationWithinTimeoutAndPreservesScores() {
        // Mock does nothing when chat is called, simulating a connection hang
        doAnswer(invocation -> null)
                .when(explanationModel).chat(anyList(), any(StreamingChatResponseHandler.class));

        long startTime = System.currentTimeMillis();
        AutoAssignmentResponse response = autoAssignmentService.recommend(
                PROJECT_ID, List.of("Java"), 5, REQUESTING_USER_ID);
        long duration = System.currentTimeMillis() - startTime;

        assertNotNull(response);
        assertEquals(PROJECT_ID, response.projectId());
        assertEquals(1, response.candidates().size());

        CandidateScore candidate = response.candidates().get(0);
        assertEquals(USER_ID, candidate.getUserId());
        assertEquals(0.23 - 0.65 / 6 + 0.12 * (3.5 / 6), candidate.getTotalScore(), 1e-12);
        assertEquals(AutoAssignmentService.DEFAULT_FALLBACK_EXPLANATION, response.aiExplanation());

        // Ensure timeout was bounded around 100ms (not waiting 10s or hanging)
        assertTrue(duration < 2000, "Execution should complete within bounded timeout, took " + duration + "ms");

        verify(aiAuditPort).save(any(AiLogEntity.class));
    }

    @Test
    @DisplayName("Interruption: Thread interrupted -> restores interrupt flag and returns fallback explanation")
    void recommend_WhenInterrupted_RestoresFlagAndReturnsFallbackExplanation() {
        doAnswer(invocation -> {
            // Interrupt current thread during execution
            Thread.currentThread().interrupt();
            return null;
        }).when(explanationModel).chat(anyList(), any(StreamingChatResponseHandler.class));

        try {
            AutoAssignmentResponse response = autoAssignmentService.recommend(
                    PROJECT_ID, List.of("Java"), 5, REQUESTING_USER_ID);

            assertNotNull(response);
            assertEquals(AutoAssignmentService.DEFAULT_FALLBACK_EXPLANATION, response.aiExplanation());
            assertTrue(Thread.currentThread().isInterrupted(), "Interrupt status should be restored on thread");
        } finally {
            // Clear interrupted status for JUnit thread pool
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("Telemetry: Service records actual configured model identity in audit log")
    void recommend_RecordsConfiguredModelIdentityInAuditLog() {
        String configuredModel = "gemini-2.5-flash";
        AutoAssignmentService customService = new AutoAssignmentService(
                projectMemberPort,
                metrics,
                userSkillPort,
                aiAuditPort,
                userPort,
                projectPort,
                heuristicStrategyFactory,
                explanationModel,
                100L,
                configuredModel
        );

        String explanation = "Custom explanation from configured model.";
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse(explanation);
            handler.onCompleteResponse(mock(ChatResponse.class));
            return null;
        }).when(explanationModel).chat(anyList(), any(StreamingChatResponseHandler.class));

        customService.recommend(PROJECT_ID, List.of("Java"), 5, REQUESTING_USER_ID);

        verify(aiAuditPort).save(argThat(entity ->
                configuredModel.equals(entity.getModelUsed()) &&
                explanation.equals(entity.getResponse())
        ));
    }

    @Test
    void rawScoresOrderCloseCandidatesAndExactTiesUseUserId() throws Exception {
        long higherId = USER_ID + 1;
        when(projectMemberPort.findProjectMembers(PROJECT_ID)).thenReturn(List.of(
                new ProjectMemberDto(higherId, "MEMBER", 1), new ProjectMemberDto(USER_ID, "MEMBER", 0)));
        when(userPort.findById(higherId)).thenReturn(Optional.of(
                new UserProfileDto(higherId, "Bob", "bob@example.com", "AVAILABLE", 99)));
        when(userSkillPort.findByUserIdWithSkill(higherId)).thenReturn(List.of(new UserSkillDto("Java", 5)));
        when(metrics.readProjectMetrics(PROJECT_ID, Set.of(USER_ID, higherId))).thenReturn(
                Map.of(USER_ID, evidence(USER_ID, 5, 0.5, 20), higherId, evidence(higherId, 5, 0.51, 20)),
                Map.of(USER_ID, evidence(USER_ID, 5, 0.5, 20), higherId, evidence(higherId, 5, 0.5, 20)));

        var close = autoAssignmentService.recommendCandidates(PROJECT_ID, List.of("Java"), 5, REQUESTING_USER_ID);
        assertEquals(higherId, close.candidates().getFirst().getUserId());
        assertEquals(0.12 * 0.01, close.candidates().getFirst().getTotalScore()
                - close.candidates().getLast().getTotalScore(), 1e-12);
        assertEquals(5, close.candidates().getFirst().getStoredWorkloadValue());
        var json = new ObjectMapper().valueToTree(close).path("candidates").get(0);
        assertEquals(higherId, json.path("candidateId").asLong());
        assertFalse(json.has("totalScore"));
        assertFalse(json.has("email"));
        assertFalse(json.has("confidenceScore"));

        var tied = autoAssignmentService.recommendCandidates(PROJECT_ID, List.of("Java"), 10, REQUESTING_USER_ID);
        assertEquals(USER_ID, tied.candidates().getFirst().getUserId());
        assertEquals("INSUFFICIENT_TO_DIFFERENTIATE", tied.differentiationStatus());
    }

    @Test
    void measuredLoadKeepsFixedScaleAndMissingSkillsAreNeutral() {
        for (int count : List.of(0, 1, 5, 10)) {
            when(metrics.readProjectMetrics(PROJECT_ID, Set.of(USER_ID)))
                    .thenReturn(Map.of(USER_ID, evidence(USER_ID, count, 0.5, 0)));
            var result = autoAssignmentService.recommendCandidates(PROJECT_ID, List.of(" "), 5, REQUESTING_USER_ID);
            var candidate = result.candidates().getFirst();
            assertEquals(0.23 * 0.5 - 0.65 * count / (count + 5.0) + 0.12 * 0.5,
                    candidate.getTotalScore(), 1e-12);
            assertNull(candidate.getPresentationFitValue());
            assertEquals("MISSING_REQUIREMENTS", candidate.getFitStatus());
            assertEquals("DEFAULT", candidate.getPerformanceStatus());
            assertNull(candidate.getObservedQualityMean());
        }
    }

    private static HeuristicStrategy strategy() {
        var benefit = HeuristicNormalization.BENCHMARK_BENEFIT;
        return new BalancedHeuristicStrategy(new HeuristicConfig(new HeuristicWeights(0.23, 0.65, 0.12),
                new HeuristicNormalizationConfig(benefit, benefit, benefit)));
    }

    private static TaskAssignmentMetrics evidence(long userId, int load, double performance, int sampleCount) {
        var status = sampleCount == 0 ? DataStatus.DEFAULT : DataStatus.MEASURED;
        return new TaskAssignmentMetrics(PROJECT_ID, userId, Instant.EPOCH,
                new Performance("performance-v1", "quality-v1", status, performance,
                        sampleCount == 0 ? null : 1.0, sampleCount, 20, sampleCount / (sampleCount + 5.0),
                        0, null, sampleCount, "evidence"),
                new Workload(load, "ACTIVE_TASK_COUNT", "PROJECT", "workload"));
    }
}
