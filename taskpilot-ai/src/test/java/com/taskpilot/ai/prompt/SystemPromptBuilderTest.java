package com.taskpilot.ai.prompt;

import com.taskpilot.ai.heuristic.HeuristicConfigProvider;
import com.taskpilot.ai.service.SmartRoutingService;
import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SystemPromptBuilderTest {

    private UserProfilePort userProfilePort;
    private HeuristicConfigProvider heuristicConfigProvider;
    private SmartRoutingService routingService;
    private SystemPromptBuilder promptBuilder;

    @BeforeEach
    void setUp() {
        userProfilePort = mock(UserProfilePort.class);
        heuristicConfigProvider = mock(HeuristicConfigProvider.class);
        routingService = mock(SmartRoutingService.class);
        promptBuilder = new SystemPromptBuilder(userProfilePort, heuristicConfigProvider, routingService);
    }

    @Test
    void testBuildSystemPromptInjectsContext() {
        when(userProfilePort.findLiteById(18L)).thenReturn(Optional.of(
                new UserProfileLiteDto(18L, "FuTie Neith", null)
        ));
        when(heuristicConfigProvider.getCurrentMode()).thenReturn("BALANCED");

        String prompt = promptBuilder.buildSystemPrompt(18L);
        assertNotNull(prompt);
        assertTrue(prompt.contains("FuTie Neith"));
        assertTrue(prompt.contains("ID: 18"));
        assertTrue(prompt.contains("Current Assignment Mode: BALANCED"));
        assertTrue(prompt.contains("[TASKPILOT TOOL WORKFLOW RULES]"));
    }

    @Test
    void testWithSystemPromptReplacesOldSystemMessage() {
        List<ChatMessage> history = List.of(
                SystemMessage.from("Old prompt"),
                UserMessage.from("Hello")
        );

        List<ChatMessage> updated = promptBuilder.withSystemPrompt(history, "New prompt");
        assertEquals(2, updated.size());
        assertEquals("New prompt", ((SystemMessage) updated.get(0)).text());
        assertEquals("Hello", ((UserMessage) updated.get(1)).singleText());
    }

    @Test
    void testGetInitialStepClassification() {
        when(routingService.normalize(anyString())).thenAnswer(inv -> ((String) inv.getArgument(0)).toLowerCase());

        String taskStep = promptBuilder.getInitialStep("Liệt kê các task của dự án 5");
        assertTrue(taskStep.contains("nhiệm vụ và công việc") || taskStep.contains("tổng hợp"));

        String notifStep = promptBuilder.getInitialStep("Xem thông báo mới");
        assertTrue(notifStep.contains("thông báo"));
    }
}
