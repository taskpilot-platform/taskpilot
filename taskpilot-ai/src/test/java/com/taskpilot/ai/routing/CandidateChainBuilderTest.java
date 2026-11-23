package com.taskpilot.ai.routing;

import com.taskpilot.ai.config.AiModelConfig;
import com.taskpilot.contracts.assignment.port.out.SystemSettingPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class CandidateChainBuilderTest {

    @Mock private SystemSettingPort systemSettingPort;
    @Mock private AiModelConfig aiModelConfig;

    private CopilotRoutingProperties props;
    private CandidateChainBuilder builder;
    private CandidateChainBuilder.ProviderSlots defaultSlots;

    @BeforeEach
    void setUp() {
        props = new CopilotRoutingProperties();
        props.setVerifiedModels(List.of(
                new CopilotRoutingProperties.VerifiedModel("GROQ", "openai/gpt-oss-120b", true, "probe"),
                new CopilotRoutingProperties.VerifiedModel("GEMINI", "gemini-3.5-flash", true, "probe"),
                new CopilotRoutingProperties.VerifiedModel("GEMINI", "gemini-2.5-flash", true, "probe"),
                new CopilotRoutingProperties.VerifiedModel("GEMINI", "gemini-3.1-flash-lite", true, "probe"),
                new CopilotRoutingProperties.VerifiedModel("GEMINI", "gemini-3.5-flash-lite", true, "probe"),
                new CopilotRoutingProperties.VerifiedModel("OPENROUTER", "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free", true, "probe")
        ));
        props.setBlockedModels(List.of(
                new CopilotRoutingProperties.BlockedModel("OPENROUTER", "nvidia/nemotron-3-super-120b-a12b:free", "uat-404"),
                new CopilotRoutingProperties.BlockedModel("OPENROUTER", "openai/gpt-oss-120b:free", "uat-404")
        ));

        when(aiModelConfig.hasConfiguredKeys("GROQ")).thenReturn(Optional.of(true));
        when(aiModelConfig.hasConfiguredKeys("GEMINI")).thenReturn(Optional.of(true));
        when(aiModelConfig.hasConfiguredKeys("OPENROUTER")).thenReturn(Optional.of(true));

        builder = new CandidateChainBuilder(systemSettingPort, aiModelConfig, props);
        defaultSlots = new CandidateChainBuilder.ProviderSlots(
                true,
                "openai/gpt-oss-120b",
                true,
                List.of("gemini-3.5-flash", "gemini-2.5-flash", "gemini-3.1-flash-lite", "gemini-2.5-flash-lite",
                        "gemini-2.5-pro", "gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.1-pro-preview"),
                true,
                false,
                List.of("nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free")
        );
    }

    @Test
    void absentAndEmptySetting_produceGroqFirstThenVerifiedGeminisThenVerifiedOpenRouter() {
        when(systemSettingPort.findJsonObjectByKey("ai.model_priority")).thenReturn(Optional.empty());
        List<ModelCandidate> absentChain = builder.build(true, defaultSlots);

        when(systemSettingPort.findJsonObjectByKey("ai.model_priority"))
                .thenReturn(Optional.of(Map.of("models", List.of())));
        List<ModelCandidate> emptyChain = builder.build(true, defaultSlots);

        List<String> expectedLabels = List.of(
                "GROQ:openai/gpt-oss-120b",
                "GEMINI:gemini-3.5-flash",
                "GEMINI:gemini-2.5-flash",
                "GEMINI:gemini-3.1-flash-lite",
                "GEMINI:gemini-3.5-flash-lite",
                "OPENROUTER:nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free"
        );

        assertThat(absentChain).extracting(ModelCandidate::label).containsExactlyElementsOf(expectedLabels);
        assertThat(emptyChain).extracting(ModelCandidate::label).containsExactlyElementsOf(expectedLabels);
        assertThat(builder.maxModelAttempts(absentChain)).isEqualTo(6);
    }

    @Test
    void v31DbPriority_isPreservedWhileDroppingBlockedOpenRouter404Slugs() {
        Map<String, Object> v31 = Map.of("models", List.of(
                Map.of("provider", "GEMINI", "model", "gemini-3.5-flash"),
                Map.of("provider", "GROQ", "model", "openai/gpt-oss-120b"),
                Map.of("provider", "GEMINI", "model", "gemini-2.5-flash"),
                Map.of("provider", "GROQ", "model", "openai/gpt-oss-20b"),
                Map.of("provider", "OPENROUTER", "model", "openai/gpt-oss-120b:free"),
                Map.of("provider", "OPENROUTER", "model", "nvidia/nemotron-3-super-120b-a12b:free")
        ));
        when(systemSettingPort.findJsonObjectByKey("ai.model_priority")).thenReturn(Optional.of(v31));

        List<ModelCandidate> lightChain = builder.build(false, defaultSlots);
        assertThat(lightChain).extracting(ModelCandidate::label).containsExactly(
                "GEMINI:gemini-3.5-flash",
                "GROQ:openai/gpt-oss-120b",
                "GEMINI:gemini-2.5-flash",
                "GROQ:openai/gpt-oss-20b"
        );
        // Groq 120b must NOT be blocked by OPENROUTER openai/gpt-oss-120b:free
        assertThat(lightChain).anyMatch(c -> c.label().equals("GROQ:openai/gpt-oss-120b"));
    }

    @Test
    void dropsRetiredPaidUnverifiedDuplicateAndUnavailableEntries() {
        Map<String, Object> db = Map.of("models", List.of(
                Map.of("provider", "GROQ", "model", "llama-3.3-70b-versatile"),
                Map.of("provider", "GEMINI", "model", "gemini-2.5-pro"),
                Map.of("provider", "OPENROUTER", "model", "openai/gpt-4.1-mini"),
                Map.of("provider", "GEMINI", "model", "gemini-3.5-flash"),
                Map.of("provider", "gemini", "model", "  GEMINI-3.5-FLASH ")
        ));
        when(systemSettingPort.findJsonObjectByKey("ai.model_priority")).thenReturn(Optional.of(db));

        List<ModelCandidate> chain = builder.build(false, defaultSlots);
        assertThat(chain).extracting(ModelCandidate::label).containsExactly("GEMINI:gemini-3.5-flash");
    }

    @Test
    void disablesOpenRouterTierAndCapsMaxAttemptsWhenConfigured() {
        when(systemSettingPort.findJsonObjectByKey("ai.model_priority")).thenReturn(Optional.empty());
        props.getTurn().setMaxModelAttemptsCap(3);
        CandidateChainBuilder.ProviderSlots noOrSlots = new CandidateChainBuilder.ProviderSlots(
                true, "openai/gpt-oss-120b", true,
                List.of("gemini-3.5-flash", "gemini-2.5-flash", "gemini-3.1-flash-lite", "gemini-3.5-flash-lite"),
                false, false, List.of("nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free")
        );

        List<ModelCandidate> chain = builder.build(true, noOrSlots);
        assertThat(chain).noneMatch(c -> "OPENROUTER".equals(c.provider()));
        assertThat(builder.maxModelAttempts(chain)).isEqualTo(3);
    }

    @Test
    void requiresToolsFiltersOutTextOnlyVerifiedModels() {
        props.setVerifiedModels(List.of(
                new CopilotRoutingProperties.VerifiedModel("GROQ", "openai/gpt-oss-120b", true, "probe"),
                new CopilotRoutingProperties.VerifiedModel("GEMINI", "gemini-3.5-flash", false, "probe"),
                new CopilotRoutingProperties.VerifiedModel("GEMINI", "gemini-2.5-flash", true, "probe")
        ));
        when(systemSettingPort.findJsonObjectByKey("ai.model_priority")).thenReturn(Optional.empty());

        List<ModelCandidate> toolChain = builder.build(true, defaultSlots);
        assertThat(toolChain).extracting(ModelCandidate::label).containsExactly(
                "GROQ:openai/gpt-oss-120b",
                "GEMINI:gemini-2.5-flash"
        );

        List<ModelCandidate> lightChain = builder.build(false, defaultSlots);
        assertThat(lightChain).extracting(ModelCandidate::label).containsExactly(
                "GROQ:openai/gpt-oss-120b",
                "GEMINI:gemini-3.5-flash",
                "GEMINI:gemini-2.5-flash"
        );
    }
}
