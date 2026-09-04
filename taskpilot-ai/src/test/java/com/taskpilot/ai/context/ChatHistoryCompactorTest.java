package com.taskpilot.ai.context;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.TokenCountEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatHistoryCompactorTest {

    private ChatHistoryCompactor compactor;
    private TokenCountEstimator tokenEstimator;
    private ChatMessageSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        tokenEstimator = mock(TokenCountEstimator.class);
        sanitizer = new ChatMessageSanitizer();
        compactor = new ChatHistoryCompactor(tokenEstimator, sanitizer);
    }

    @Test
    void testCompactHistoryWhenUnderLimit() {
        when(tokenEstimator.estimateTokenCountInMessages(anyList())).thenReturn(500);

        List<ChatMessage> messages = List.of(
                SystemMessage.from("System prompt"),
                UserMessage.from("User message 1"),
                AiMessage.from("AI response 1")
        );

        List<ChatMessage> result = compactor.compactHistoryForRequest(messages, "test");
        assertEquals(3, result.size());
    }

    @Test
    void testCompactHistoryWhenOverLimit() {
        // Return 10000 initially, then 4000 when compacted
        when(tokenEstimator.estimateTokenCountInMessages(anyList()))
                .thenReturn(10000)
                .thenReturn(4000);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("System prompt"));
        for (int i = 0; i < 15; i++) {
            messages.add(UserMessage.from("Message " + i));
            messages.add(AiMessage.from("Response " + i));
        }

        List<ChatMessage> result = compactor.compactHistoryForRequest(messages, "test");
        assertTrue(result.size() < messages.size());
        assertTrue(result.get(0) instanceof SystemMessage);
    }
}
