package com.taskpilot.ai.service;

import com.taskpilot.ai.assignment.port.out.AiAuditPort;
import com.taskpilot.ai.dto.AutoAssignmentResponse;
import com.taskpilot.ai.dto.CandidateScore;
import com.taskpilot.ai.entity.AiLogEntity;
import com.taskpilot.ai.heuristic.HeuristicStrategy;
import com.taskpilot.ai.heuristic.HeuristicStrategyFactory;
import com.taskpilot.ai.heuristic.NormalizedScores;
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
    private HeuristicStrategy heuristicStrategy;

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
                userSkillPort,
                aiAuditPort,
                userPort,
                projectPort,
                heuristicStrategyFactory,
                explanationModel,
                100L // 100ms timeout for test determinism
        );

        when(projectPort.findById(PROJECT_ID))
                .thenReturn(Optional.of(new ProjectHeuristicConfigDto(PROJECT_ID, "BALANCED")));
        when(heuristicStrategyFactory.resolve("BALANCED")).thenReturn(heuristicStrategy);

        ProjectMemberDto member = new ProjectMemberDto(USER_ID, "MEMBER", 0.9);
        when(projectMemberPort.findProjectMembers(PROJECT_ID)).thenReturn(List.of(member));

        UserProfileDto userProfile = new UserProfileDto(USER_ID, "Alice Engineer", "alice@example.com", "AVAILABLE", 20);
        when(userPort.findById(USER_ID)).thenReturn(Optional.of(userProfile));

        UserSkillDto skill = new UserSkillDto("Java", 5);
        when(userSkillPort.findByUserIdWithSkill(USER_ID)).thenReturn(List.of(skill));

        when(projectMemberPort.findRecentPerformanceScores(eq(USER_ID), anyInt())).thenReturn(List.of(0.9, 0.85));

        when(heuristicStrategy.normalize(any(), any())).thenReturn(new NormalizedScores(0.9, 0.8, 0.88));
        when(heuristicStrategy.score(any())).thenReturn(0.86);
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
        assertEquals(0.86, candidate.getTotalScore(), 0.01);
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
        assertEquals(0.86, candidate.getTotalScore(), 0.01);
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
        assertEquals(0.86, candidate.getTotalScore(), 0.01);
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
}
