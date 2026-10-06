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

    private SmartRoutingService createRoutingServiceWithRealConfig() {
        AiModelConfig realConfig = new AiModelConfig();
        ReflectionTestUtils.setField(realConfig, "geminiApiKey", "dummy-gemini-key");
        ReflectionTestUtils.setField(realConfig, "geminiTimeoutSeconds", 30);
        ReflectionTestUtils.setField(realConfig, "groqApiKey", "dummy-groq-key");
        ReflectionTestUtils.setField(realConfig, "openRouterApiKey", "dummy-openrouter-key");
        ReflectionTestUtils.setField(realConfig, "timeoutSeconds", 30);

        SmartRoutingService svc = new SmartRoutingService(
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
                realConfig
        );
        ReflectionTestUtils.setField(svc, "groqEnabled", true);
        ReflectionTestUtils.setField(svc, "openRouterEnabled", true);
        ReflectionTestUtils.setField(svc, "geminiModelName", "gemini-3.5-flash");
        return svc;
    }

    @Test
    @DisplayName("getNextStreamingFallback should resolve next catalog model when dynamic primary fails")
    void getNextStreamingFallback_shouldResolveNextCatalogModel_whenDynamicPrimaryFails() {
        SmartRoutingService svc = createRoutingServiceWithRealConfig();

        // Active priority catalog: 1. GEMINI / gemini-3.5-flash, 2. GEMINI / gemini-2.5-flash
        Map<String, Object> dbPriority = Map.of("models", List.of(
                Map.of("provider", "GEMINI", "model", "gemini-3.5-flash"),
                Map.of("provider", "GEMINI", "model", "gemini-2.5-flash")
        ));
        when(systemSettingPort.findJsonObjectByKey("ai.model_priority")).thenReturn(Optional.of(dbPriority));

        // Dynamically create primary model via same path used by production code (type = primary)
        StreamingChatModel dynamicPrimary = svc.getModelByProviderAndName("GEMINI", "gemini-3.5-flash", "primary");
        assertNotNull(dynamicPrimary);

        StreamingChatModel fallback = svc.getNextStreamingFallback(dynamicPrimary);

        // Pre-fix: fallback == dynamicPrimary (fails!)
        // Post-fix: fallback is gemini-2.5-flash, not same object as dynamicPrimary
        assertNotSame(dynamicPrimary, fallback, "Fallback must advance to next catalog model, not return current model");
        assertEquals("gemini-2.5-flash", svc.getModelName(fallback));
    }

    @Test
    @DisplayName("getNextStreamingFallback should use provider and model as catalog identity")
    void getNextStreamingFallback_shouldUseProviderAndModelAsCatalogIdentity() {
        SmartRoutingService svc = createRoutingServiceWithRealConfig();

        // Catalog with identical model name across different providers:
        // 0. GEMINI / shared-model-name
        // 1. OPENROUTER / shared-model-name
        // 2. GEMINI / gemini-2.5-flash
        Map<String, Object> dbPriority = Map.of("models", List.of(
                Map.of("provider", "GEMINI", "model", "shared-model-name"),
                Map.of("provider", "OPENROUTER", "model", "shared-model-name"),
                Map.of("provider", "GEMINI", "model", "gemini-2.5-flash")
        ));
        when(systemSettingPort.findJsonObjectByKey("ai.model_priority")).thenReturn(Optional.of(dbPriority));

        // When current model is GEMINI / shared-model-name
        StreamingChatModel currentGeminiShared = svc.getModelByProviderAndName("GEMINI", "shared-model-name", "primary");
        StreamingChatModel fallbackFromGemini = svc.getNextStreamingFallback(currentGeminiShared);

        // Must advance to OPENROUTER / shared-model-name, NOT think it's the same model or jump to gemini-2.5-flash
        assertNotSame(currentGeminiShared, fallbackFromGemini);
        assertEquals("OPENROUTER", svc.getModelProvider(fallbackFromGemini), "Must advance to OPENROUTER provider");
        assertEquals("shared-model-name", svc.getModelName(fallbackFromGemini));

        // When current model is OPENROUTER / shared-model-name
        StreamingChatModel currentOpenRouterShared = svc.getModelByProviderAndName("OPENROUTER", "shared-model-name", "reasoning");
        StreamingChatModel fallbackFromOpenRouter = svc.getNextStreamingFallback(currentOpenRouterShared);

        // Must advance to GEMINI / gemini-2.5-flash
        assertNotSame(currentOpenRouterShared, fallbackFromOpenRouter);
        assertEquals("GEMINI", svc.getModelProvider(fallbackFromOpenRouter), "Must advance to GEMINI provider");
        assertEquals("gemini-2.5-flash", svc.getModelName(fallbackFromOpenRouter));
    }

    @Test
    @DisplayName("getNextStreamingFallback should recognize same catalog identity across different model instances")
    void getNextStreamingFallback_shouldRecognizeSameCatalogIdentity_acrossDifferentModelInstances() {
        SmartRoutingService svc = createRoutingServiceWithRealConfig();

        Map<String, Object> dbPriority = Map.of("models", List.of(
                Map.of("provider", "GEMINI", "model", "gemini-3.5-flash"),
                Map.of("provider", "GEMINI", "model", "gemini-2.5-flash")
        ));
        when(systemSettingPort.findJsonObjectByKey("ai.model_priority")).thenReturn(Optional.of(dbPriority));

        // Two different Java objects representing the same logical model (GEMINI / gemini-3.5-flash)
        StreamingChatModel dynamicPrimary = svc.getModelByProviderAndName("GEMINI", "gemini-3.5-flash", "primary");
        StreamingChatModel dynamicReasoning = svc.getModelByProviderAndName("GEMINI", "gemini-3.5-flash", "reasoning");
        assertNotSame(dynamicPrimary, dynamicReasoning, "Precondition: instances must be distinct objects");

        // Both should locate the same catalog slot (index 0) and advance to gemini-2.5-flash (index 1)
        StreamingChatModel fallbackFromPrimary = svc.getNextStreamingFallback(dynamicPrimary);
        StreamingChatModel fallbackFromReasoning = svc.getNextStreamingFallback(dynamicReasoning);

        assertNotSame(dynamicPrimary, fallbackFromPrimary);
        assertEquals("gemini-2.5-flash", svc.getModelName(fallbackFromPrimary));

        assertNotSame(dynamicReasoning, fallbackFromReasoning);
        assertEquals("gemini-2.5-flash", svc.getModelName(fallbackFromReasoning));
    }

    @Test
    @DisplayName("getNextStreamingFallback should preserve no-fallback contract when current model is last eligible entry")
    void getNextStreamingFallback_shouldPreserveNoFallbackContract_whenCurrentModelIsLastEligibleEntry() {
        SmartRoutingService svc = createRoutingServiceWithRealConfig();

        Map<String, Object> dbPriority = Map.of("models", List.of(
                Map.of("provider", "GEMINI", "model", "gemini-3.5-flash"),
                Map.of("provider", "GEMINI", "model", "gemini-2.5-flash")
        ));
        when(systemSettingPort.findJsonObjectByKey("ai.model_priority")).thenReturn(Optional.of(dbPriority));

        // Current model is gemini-2.5-flash (the last entry in catalog)
        StreamingChatModel lastModel = svc.getModelByProviderAndName("GEMINI", "gemini-2.5-flash", "reasoning");

        StreamingChatModel fallback = svc.getNextStreamingFallback(lastModel);

        // Must preserve the no-fallback contract: return currentModel, not wrap to gemini-3.5-flash
        assertSame(lastModel, fallback, "Last model must return currentModel to indicate end of catalog");
    }

    @Test
    @DisplayName("getNextStreamingFallback should skip ineligible entry and select next eligible model")
    void getNextStreamingFallback_shouldSkipIneligibleEntry_andSelectNextEligibleModel() {
        SmartRoutingService svc = createRoutingServiceWithRealConfig();

        // Index 1 is retired model llama-3.3-70b-versatile
        Map<String, Object> dbPriority = Map.of("models", List.of(
                Map.of("provider", "GEMINI", "model", "gemini-3.5-flash"),
                Map.of("provider", "GROQ", "model", "llama-3.3-70b-versatile"),
                Map.of("provider", "GEMINI", "model", "gemini-2.5-flash")
        ));
        when(systemSettingPort.findJsonObjectByKey("ai.model_priority")).thenReturn(Optional.of(dbPriority));

        StreamingChatModel currentModel = svc.getModelByProviderAndName("GEMINI", "gemini-3.5-flash", "primary");

        StreamingChatModel fallback = svc.getNextStreamingFallback(currentModel);

        assertNotSame(currentModel, fallback);
        assertEquals("gemini-2.5-flash", svc.getModelName(fallback), "Must skip retired llama-3.3 and select gemini-2.5-flash");
    }

    @Test
    @DisplayName("getOrCreateDynamicModel should normalize provider case and whitespace")
    void getOrCreateDynamicModel_shouldNormalizeProviderCaseAndWhitespace() {
        AiModelConfig config = new AiModelConfig();
        ReflectionTestUtils.setField(config, "geminiApiKey", "dummy-gemini-key");
        ReflectionTestUtils.setField(config, "geminiTimeoutSeconds", 30);

        StreamingChatModel model1 = config.getOrCreateDynamicModel("GEMINI", "gemini-2.5-flash", "reasoning");
        StreamingChatModel model2 = config.getOrCreateDynamicModel("gemini", "gemini-2.5-flash", "reasoning");
        StreamingChatModel model3 = config.getOrCreateDynamicModel("  Gemini  ", "gemini-2.5-flash", "reasoning");

        assertNotNull(model1);
        assertSame(model1, model2, "lowercase provider must return cached instance");
        assertSame(model1, model3, "whitespace provider must return cached instance");
        assertEquals("GEMINI", config.getModelProvider(model1), "Provider metadata must be normalized uppercase");
        assertEquals("gemini-2.5-flash", config.getModelName(model1));
    }

    @Test
    @DisplayName("getNextStreamingFallback should preserve text role when dynamic text model fails")
    void getNextStreamingFallback_shouldPreserveTextRole_whenDynamicTextModelFails() {
        SmartRoutingService svc = createRoutingServiceWithRealConfig();

        Map<String, Object> dbPriority = Map.of("models", List.of(
                Map.of("provider", "GEMINI", "model", "gemini-3.5-flash"),
                Map.of("provider", "OPENROUTER", "model", "google/gemma-4-31b-it:free")
        ));
        when(systemSettingPort.findJsonObjectByKey("ai.model_priority")).thenReturn(Optional.of(dbPriority));

        StreamingChatModel textModel = svc.getModelByProviderAndName("GEMINI", "gemini-3.5-flash", "text");
        assertEquals("text", svc.getModelType(textModel));

        StreamingChatModel fallback = svc.getNextStreamingFallback(textModel);
        assertNotSame(textModel, fallback);
        assertEquals("OPENROUTER", svc.getModelProvider(fallback));
        assertEquals("google/gemma-4-31b-it:free", svc.getModelName(fallback));
        assertEquals("text", svc.getModelType(fallback), "Fallback model must preserve text type");
    }

    @Test
    @DisplayName("getNextStreamingFallback should preserve reasoning role when dynamic reasoning model fails")
    void getNextStreamingFallback_shouldPreserveReasoningRole_whenDynamicReasoningModelFails() {
        SmartRoutingService svc = createRoutingServiceWithRealConfig();

        Map<String, Object> dbPriority = Map.of("models", List.of(
                Map.of("provider", "GEMINI", "model", "gemini-3.5-flash"),
                Map.of("provider", "OPENROUTER", "model", "google/gemma-4-31b-it:free")
        ));
        when(systemSettingPort.findJsonObjectByKey("ai.model_priority")).thenReturn(Optional.of(dbPriority));

        StreamingChatModel reasoningModel = svc.getModelByProviderAndName("GEMINI", "gemini-3.5-flash", "reasoning");
        assertEquals("reasoning", svc.getModelType(reasoningModel));

        StreamingChatModel fallback = svc.getNextStreamingFallback(reasoningModel);
        assertNotSame(reasoningModel, fallback);
        assertEquals("OPENROUTER", svc.getModelProvider(fallback));
        assertEquals("google/gemma-4-31b-it:free", svc.getModelName(fallback));
        assertEquals("reasoning", svc.getModelType(fallback), "Fallback model must preserve reasoning type");
    }

    @Test
    @DisplayName("getNextStreamingFallback should locate same catalog entry regardless of role differences")
    void getNextStreamingFallback_shouldLocateSameCatalogEntry_regardlessOfRoleDifferences() {
        SmartRoutingService svc = createRoutingServiceWithRealConfig();

        Map<String, Object> dbPriority = Map.of("models", List.of(
                Map.of("provider", "GEMINI", "model", "gemini-3.5-flash"),
                Map.of("provider", "GEMINI", "model", "gemini-2.5-flash")
        ));
        when(systemSettingPort.findJsonObjectByKey("ai.model_priority")).thenReturn(Optional.of(dbPriority));

        StreamingChatModel primaryInstance = svc.getModelByProviderAndName("GEMINI", "gemini-3.5-flash", "primary");
        StreamingChatModel textInstance = svc.getModelByProviderAndName("GEMINI", "gemini-3.5-flash", "text");
        StreamingChatModel reasoningInstance = svc.getModelByProviderAndName("GEMINI", "gemini-3.5-flash", "reasoning");

        StreamingChatModel fallbackFromPrimary = svc.getNextStreamingFallback(primaryInstance);
        StreamingChatModel fallbackFromText = svc.getNextStreamingFallback(textInstance);
        StreamingChatModel fallbackFromReasoning = svc.getNextStreamingFallback(reasoningInstance);

        assertEquals("gemini-2.5-flash", svc.getModelName(fallbackFromPrimary));
        assertEquals("gemini-2.5-flash", svc.getModelName(fallbackFromText));
        assertEquals("gemini-2.5-flash", svc.getModelName(fallbackFromReasoning));
    }
}
