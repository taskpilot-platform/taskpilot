package com.taskpilot.ai.config;

import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class AiModelConfigMetadataIdentityTest {

    private AiModelConfig config;

    @BeforeEach
    void setUp() {
        config = new AiModelConfig();
        ReflectionTestUtils.setField(config, "geminiApiKey", "test-gemini-key");
        ReflectionTestUtils.setField(config, "geminiTimeoutSeconds", 30);
        ReflectionTestUtils.setField(config, "openRouterApiKey", "test-openrouter-key-123456");
        ReflectionTestUtils.setField(config, "groqApiKey", "test-groq-key-123456");
        ReflectionTestUtils.setField(config, "timeoutSeconds", 30);
    }

    @Test
    @DisplayName("Gemini model instances with different roles must have reference equality semantics and independent metadata")
    void geminiModelInstances_shouldPreserveIndependentMetadata_acrossRoles() {
        // Concrete construction: OpenAiOfficialStreamingChatModel instances
        StreamingChatModel primary = config.getOrCreateDynamicModel("GEMINI", "gemini-3.5-flash", "primary");
        StreamingChatModel reasoning = config.getOrCreateDynamicModel("GEMINI", "gemini-3.5-flash", "reasoning");
        StreamingChatModel text = config.getOrCreateDynamicModel("GEMINI", "gemini-3.5-flash", "text");

        assertNotNull(primary);
        assertNotNull(reasoning);
        assertNotNull(text);

        // Verify distinct instances
        assertNotSame(primary, reasoning, "primary and reasoning must be distinct references");
        assertNotSame(reasoning, text, "reasoning and text must be distinct references");
        assertNotSame(primary, text, "primary and text must be distinct references");

        // Verify equals() returns false (default java.lang.Object reference identity)
        assertFalse(primary.equals(reasoning), "primary.equals(reasoning) must be false");
        assertFalse(reasoning.equals(text), "reasoning.equals(text) must be false");
        assertFalse(primary.equals(text), "primary.equals(text) must be false");

        // Verify independent metadata without overwrite
        assertEquals("primary", config.getModelType(primary), "primary model must retain primary type");
        assertEquals("reasoning", config.getModelType(reasoning), "reasoning model must retain reasoning type");
        assertEquals("text", config.getModelType(text), "text model must retain text type");

        assertEquals("GEMINI", config.getModelProvider(primary));
        assertEquals("GEMINI", config.getModelProvider(reasoning));
        assertEquals("GEMINI", config.getModelProvider(text));

        assertEquals("gemini-3.5-flash", config.getModelName(primary));
        assertEquals("gemini-3.5-flash", config.getModelName(reasoning));
        assertEquals("gemini-3.5-flash", config.getModelName(text));
    }

    @Test
    @DisplayName("OpenRouter multi-key model instances with different roles must preserve independent metadata")
    void openRouterModelInstances_shouldPreserveIndependentMetadata_acrossRoles() {
        // Concrete construction: OpenRouterMultiKeyStreamingChatModel instances
        StreamingChatModel reasoning = config.getOrCreateDynamicModel("OPENROUTER", "meta-llama/llama-3-8b", "reasoning");
        StreamingChatModel text = config.getOrCreateDynamicModel("OPENROUTER", "meta-llama/llama-3-8b", "text");

        assertNotNull(reasoning);
        assertNotNull(text);

        assertNotSame(reasoning, text);
        assertFalse(reasoning.equals(text), "OpenRouter reasoning and text instances must not be equal");

        assertEquals("reasoning", config.getModelType(reasoning));
        assertEquals("text", config.getModelType(text));

        assertEquals("OPENROUTER", config.getModelProvider(reasoning));
        assertEquals("OPENROUTER", config.getModelProvider(text));
        assertEquals("meta-llama/llama-3-8b", config.getModelName(reasoning));
        assertEquals("meta-llama/llama-3-8b", config.getModelName(text));
    }

    @Test
    @DisplayName("Groq multi-key model instances with different roles must preserve independent metadata")
    void groqModelInstances_shouldPreserveIndependentMetadata_acrossRoles() {
        // Concrete construction: GroqMultiKeyStreamingChatModel instances
        StreamingChatModel reasoning = config.getOrCreateDynamicModel("GROQ", "openai/gpt-oss-120b", "reasoning");
        StreamingChatModel text = config.getOrCreateDynamicModel("GROQ", "openai/gpt-oss-120b", "text");

        assertNotNull(reasoning);
        assertNotNull(text);

        assertNotSame(reasoning, text);
        assertFalse(reasoning.equals(text), "Groq reasoning and text instances must not be equal");

        assertEquals("reasoning", config.getModelType(reasoning));
        assertEquals("text", config.getModelType(text));

        assertEquals("GROQ", config.getModelProvider(reasoning));
        assertEquals("GROQ", config.getModelProvider(text));
        assertEquals("openai/gpt-oss-120b", config.getModelName(reasoning));
        assertEquals("openai/gpt-oss-120b", config.getModelName(text));
    }
}
