package com.taskpilot.ai.service;

import com.taskpilot.ai.config.AiModelConfig;
import com.taskpilot.contracts.assignment.port.out.SystemSettingPort;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SmartRoutingModelPathTest {

    @Mock
    private AiModelConfig aiModelConfig;

    @Mock
    private SystemSettingPort systemSettingPort;

    @Mock
    private StreamingChatModel geminiPrimaryModel;

    @Mock
    private StreamingChatModel groqReasoningModel;

    @Mock
    private StreamingChatModel groqReasoningTextModel;

    private SmartRoutingService routingService;

    @BeforeEach
    void setUp() {
        routingService = new SmartRoutingService(
                geminiPrimaryModel,
                null, null, null, null, null, null, null,
                groqReasoningModel,
                null,
                null, null, null, null, null, null, null, null, null, null, null,
                groqReasoningTextModel,
                null,
                null,
                null,
                null,
                systemSettingPort,
                aiModelConfig
        );

        ReflectionTestUtils.setField(routingService, "groqEnabled", true);
        ReflectionTestUtils.setField(routingService, "groqReasoningModelName", "openai/gpt-oss-120b");
        ReflectionTestUtils.setField(routingService, "groqReasoningFallback1ModelName", "openai/gpt-oss-20b");
        ReflectionTestUtils.setField(routingService, "geminiModelName", "gemini-3.5-flash");
    }

    @Test
    @DisplayName("isRetiredModel correctly identifies all confirmed-dead model identifiers")
    void testIsRetiredModel() {
        assertTrue(SmartRoutingService.isRetiredModel("llama-3.3-70b-versatile"));
        assertTrue(SmartRoutingService.isRetiredModel("meta-llama/llama-4-scout-17b-16e-instruct"));
        assertTrue(SmartRoutingService.isRetiredModel("llama-3.1-8b-instant"));
        assertTrue(SmartRoutingService.isRetiredModel("gemini-2.0-flash"));
        assertTrue(SmartRoutingService.isRetiredModel("gemini-2.0-flash-lite"));
        assertTrue(SmartRoutingService.isRetiredModel("gpt-4o"));
        assertTrue(SmartRoutingService.isRetiredModel("DeepSeek-R1"));

        assertFalse(SmartRoutingService.isRetiredModel("gemini-3.5-flash"));
        assertFalse(SmartRoutingService.isRetiredModel("gemini-2.5-flash"));
        assertFalse(SmartRoutingService.isRetiredModel("openai/gpt-oss-120b"));
        assertFalse(SmartRoutingService.isRetiredModel("openai/gpt-oss-20b"));
    }

    @Test
    @DisplayName("getModelByProviderAndName returns null and refuses to construct client for retired model")
    void testGetModelByProviderAndName_RefusesRetiredModel() {
        StreamingChatModel model = routingService.getModelByProviderAndName("GROQ", "llama-3.3-70b-versatile", "text");
        assertNull(model, "Retired model llama-3.3-70b-versatile must be rejected");

        StreamingChatModel scoutModel = routingService.getModelByProviderAndName("GROQ", "meta-llama/llama-4-scout-17b-16e-instruct", "reasoning");
        assertNull(scoutModel, "Retired model llama-4-scout must be rejected");

        // Verify dynamic client factory was NOT called for retired models
        verify(aiModelConfig, never()).getOrCreateDynamicModel(any(), eq("llama-3.3-70b-versatile"), any());
        verify(aiModelConfig, never()).getOrCreateDynamicModel(any(), eq("meta-llama/llama-4-scout-17b-16e-instruct"), any());
    }

    @Test
    @DisplayName("resolveModelByPriority skips retired model in DB and falls back to active model")
    void testResolveModelByPriority_SkipsRetiredModelInDb() {
        // Mock DB priority containing retired model at index 0
        Map<String, Object> dbPriority = Map.of("models", List.of(
                Map.of("provider", "GROQ", "model", "llama-3.3-70b-versatile"),
                Map.of("provider", "GROQ", "model", "openai/gpt-oss-120b")
        ));
        when(systemSettingPort.findJsonObjectByKey("ai.model_priority")).thenReturn(Optional.of(dbPriority));

        // When requesting reasoning text model
        StreamingChatModel resolved = routingService.getReasoningTextModel();

        // Must resolve to groqReasoningTextModel (for openai/gpt-oss-120b), NEVER llama-3.3
        assertEquals(groqReasoningTextModel, resolved);
        verify(aiModelConfig, never()).getOrCreateDynamicModel(any(), eq("llama-3.3-70b-versatile"), any());
    }

    @Test
    @DisplayName("AiModelConfig refuses to instantiate dynamic model for retired models")
    void testAiModelConfig_RefusesRetiredDynamicModel() {
        AiModelConfig config = new AiModelConfig();
        StreamingChatModel result = config.getOrCreateDynamicModel("GROQ", "llama-3.3-70b-versatile", "text");
        assertNull(result, "getOrCreateDynamicModel must refuse llama-3.3-70b-versatile");

        StreamingChatModel scoutResult = config.getOrCreateDynamicModel("GROQ", "meta-llama/llama-4-scout-17b-16e-instruct", "reasoning");
        assertNull(scoutResult, "getOrCreateDynamicModel must refuse llama-4-scout");
    }

    @Test
    @DisplayName("AiModelConfig sanitizes retired model names injected from environment")
    void testAiModelConfig_SanitizesRetiredEnvModels() {
        AiModelConfig config = new AiModelConfig();
        ReflectionTestUtils.setField(config, "groqReasoningModelName", "llama-3.3-70b-versatile");
        ReflectionTestUtils.setField(config, "groqReasoningFallback1ModelName", "meta-llama/llama-4-scout-17b-16e-instruct");
        ReflectionTestUtils.setField(config, "groqGatekeeperModelName", "llama-3.1-8b-instant");

        config.sanitizeConfiguredModels();

        assertEquals("openai/gpt-oss-120b", ReflectionTestUtils.getField(config, "groqReasoningModelName"));
        assertEquals("openai/gpt-oss-20b", ReflectionTestUtils.getField(config, "groqReasoningFallback1ModelName"));
        assertEquals("openai/gpt-oss-20b", ReflectionTestUtils.getField(config, "groqGatekeeperModelName"));
    }
}
