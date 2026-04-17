package com.taskpilot.ai.config;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import java.time.Duration;
@Slf4j
@Configuration
public class AiModelConfig {
    @Value("${ai.gemini.api-key}")
    private String geminiApiKey;
    @Value("${ai.github.token}")
    private String githubToken;
    @Value("${ai.gemini.model-name:gemini-2.5-flash}")
    private String geminiModelName;
    @Value("${ai.github.fallback-model:gpt-4o}")
    private String fallbackModelName;
    @Value("${ai.github.reasoning-model:DeepSeek-R1}")
    private String reasoningModelName;
    @Value("${ai.model.timeout-seconds:60}")
    private int timeoutSeconds;
    @Primary
    @Bean("geminiFlashModel")
    public StreamingChatModel geminiFlashModel() {
        log.info("[AI Config] Initializing PRIMARY model: {} (GoogleAiGemini)", geminiModelName);
        return GoogleAiGeminiStreamingChatModel.builder()
                .apiKey(geminiApiKey)
                .modelName(geminiModelName)
                .temperature(0.3)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }
    @Bean("gpt4oFallbackModel")
    public StreamingChatModel gpt4oFallbackModel() {
        log.info("[AI Config] Initializing FALLBACK model: {} (GitHub Models via OpenAI Official SDK)", fallbackModelName);
        return OpenAiOfficialStreamingChatModel.builder()
                .apiKey(githubToken)
                .modelName(fallbackModelName)
                .isGitHubModels(true)
                .temperature(0.3)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }
    @Bean("deepSeekReasoningModel")
    public StreamingChatModel deepSeekReasoningModel() {
        log.info("[AI Config] Initializing REASONING model: {} (GitHub Models via OpenAI Official SDK)", reasoningModelName);
        return OpenAiOfficialStreamingChatModel.builder()
                .apiKey(githubToken)
                .modelName(reasoningModelName)
                .isGitHubModels(true)
                .temperature(0.5)
                .timeout(Duration.ofSeconds(timeoutSeconds * 2)) // Reasoning models are slower
                .build();
    }
}

