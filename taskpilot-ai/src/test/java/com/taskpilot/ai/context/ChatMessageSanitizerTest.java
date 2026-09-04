package com.taskpilot.ai.context;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatMessageSanitizerTest {

    private ChatMessageSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        sanitizer = new ChatMessageSanitizer();
    }

    @Test
    void testExtractAllThinkBlocks() {
        String input = "<think>Step 1: check projects</think>Hello world!<thought>Step 2: assign</thought>";
        String extracted = sanitizer.extractAllThinkBlocks(input);
        assertNotNull(extracted);
        assertTrue(extracted.contains("Step 1: check projects"));
        assertTrue(extracted.contains("Step 2: assign"));
    }

    @Test
    void testStripThinkBlocks() {
        String input = "<think>Secret internal reasoning</think>Hello! How can I help you today?";
        String stripped = sanitizer.stripThinkBlocks(input);
        assertEquals("Hello! How can I help you today?", stripped);
    }

    @Test
    void testCleanAndAlternateRoles() {
        List<ChatMessage> messages = List.of(
                SystemMessage.from("You are an assistant"),
                UserMessage.from("Hello"),
                UserMessage.from("How are you?"),
                AiMessage.from("I am fine."),
                AiMessage.from("Ready to help.")
        );

        List<ChatMessage> alternated = sanitizer.cleanAndAlternateRoles(messages, false);
        assertEquals(3, alternated.size());
        assertTrue(alternated.get(0) instanceof SystemMessage);
        assertTrue(alternated.get(1) instanceof UserMessage);
        assertEquals("Hello\n\nHow are you?", ((UserMessage) alternated.get(1)).singleText());
        assertTrue(alternated.get(2) instanceof AiMessage);
    }

    @Test
    void testSanitizeHistoryForToolsFlattensToolResults() {
        List<ChatMessage> messages = List.of(
                SystemMessage.from("Base system prompt"),
                UserMessage.from("Find my projects"),
                ToolExecutionResultMessage.from("call-1", "queryProjects", "[{\"id\":1,\"name\":\"TaskPilot\"}]")
        );

        List<ChatMessage> sanitized = sanitizer.sanitizeHistoryForTools(messages);
        assertEquals(3, sanitized.size());
        assertTrue(sanitized.get(2) instanceof SystemMessage);
        assertTrue(((SystemMessage) sanitized.get(2)).text().contains("SYSTEM TOOL RESULT [queryProjects]"));
    }
}
