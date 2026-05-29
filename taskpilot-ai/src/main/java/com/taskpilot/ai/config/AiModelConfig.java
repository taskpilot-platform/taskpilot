package com.taskpilot.ai.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GeminiMode;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialChatModel;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

        @Value("${ai.gemini.model-name:gemini-3.5-flash}")
        private String geminiModelName;

        // Fallbacks are ordered for responsiveness after the quality-first primary model.
        @Value("${ai.gemini.fallback1-model:gemini-3.1-flash-lite}")
        private String geminiFallback1ModelName;

        @Value("${ai.gemini.fallback2-model:gemini-2.5-flash-lite}")
        private String geminiFallback2ModelName;

        @Value("${ai.gemini.fallback3-model:gemini-2.5-flash}")
        private String geminiFallback3ModelName;

        @Value("${ai.gemini.fallback4-model:gemini-3-flash}")
        private String geminiFallback4ModelName;

        @Value("${ai.github.fallback-model:gpt-4o}")
        private String fallbackModelName;

        @Value("${ai.github.reasoning-model:DeepSeek-R1}")
        private String reasoningModelName;

        @Value("${ai.groq.api-key:}")
        private String groqApiKey;

        @Value("${ai.groq.base-url:https://api.groq.com/openai/v1}")
        private String groqBaseUrl;

        @Value("${ai.groq.reasoning-model:openai/gpt-oss-120b}")
        private String groqReasoningModelName;

        @Value("${ai.groq.gatekeeper-model:llama-3.1-8b-instant}")
        private String groqGatekeeperModelName;

        @Value("${ai.model.timeout-seconds:60}")
        private int timeoutSeconds;

        @Value("${ai.gemini.timeout-seconds:20}")
        private int geminiTimeoutSeconds;

        @Primary
        @Bean("geminiFlashModel")
        public StreamingChatModel geminiFlashModel() {
                log.info("[AI Config] Initializing PRIMARY Gemini model: {} (GoogleAiGemini)", geminiModelName);
                return geminiStreamingModel(geminiModelName);
        }

        @Bean("geminiFallback1Model")
        public StreamingChatModel geminiFallback1Model() {
                log.info("[AI Config] Initializing Gemini fallback-1: {}", geminiFallback1ModelName);
                return geminiStreamingModel(geminiFallback1ModelName);
        }

        @Bean("geminiFallback2Model")
        public StreamingChatModel geminiFallback2Model() {
                log.info("[AI Config] Initializing Gemini fallback-2: {}", geminiFallback2ModelName);
                return geminiStreamingModel(geminiFallback2ModelName);
        }

        @Bean("geminiFallback3Model")
        public StreamingChatModel geminiFallback3Model() {
                log.info("[AI Config] Initializing Gemini fallback-3: {}", geminiFallback3ModelName);
                return geminiStreamingModel(geminiFallback3ModelName);
        }

        @Bean("geminiFallback4Model")
        public StreamingChatModel geminiFallback4Model() {
                log.info("[AI Config] Initializing Gemini fallback-4: {}", geminiFallback4ModelName);
                return geminiStreamingModel(geminiFallback4ModelName);
        }

        private StreamingChatModel geminiStreamingModel(String modelName) {
                return GoogleAiGeminiStreamingChatModel.builder()
                                .apiKey(geminiApiKey)
                                .modelName(modelName)
                                .toolConfig(GeminiMode.AUTO)
                                .temperature(0.3)
                                .timeout(Duration.ofSeconds(geminiTimeoutSeconds))
                                .build();
        }

        @Bean("gpt4oFallbackModel")
        public StreamingChatModel gpt4oFallbackModel() {
                log.info("[AI Config] Initializing FALLBACK model: {} (GitHub Models via OpenAI Official SDK)",
                                fallbackModelName);
                return OpenAiOfficialStreamingChatModel.builder()
                                .apiKey(githubToken)
                                .modelName(fallbackModelName)
                                .isGitHubModels(true)
                                .temperature(0.3)
                                .parallelToolCalls(true)
                                .timeout(Duration.ofSeconds(timeoutSeconds))
                                .build();
        }

        @Bean("gpt4oFallbackTextModel")
        public StreamingChatModel gpt4oFallbackTextModel() {
                log.info("[AI Config] Initializing FALLBACK TEXT model: {} (GitHub Models via OpenAI Official SDK)",
                                fallbackModelName);
                return OpenAiOfficialStreamingChatModel.builder()
                                .apiKey(githubToken)
                                .modelName(fallbackModelName)
                                .isGitHubModels(true)
                                .temperature(0.3)
                                .parallelToolCalls(false)
                                .timeout(Duration.ofSeconds(timeoutSeconds))
                                .build();
        }

        @Bean("deepSeekReasoningModel")
        public StreamingChatModel deepSeekReasoningModel() {
                log.info("[AI Config] Initializing REASONING model: {} (GitHub Models via OpenAI Official SDK)",
                                reasoningModelName);
                return OpenAiOfficialStreamingChatModel.builder()
                                .apiKey(githubToken)
                                .modelName(reasoningModelName)
                                .isGitHubModels(true)
                                .temperature(0.5)
                                .parallelToolCalls(true)
                                .timeout(Duration.ofSeconds(timeoutSeconds * 2))
                                .build();
        }

        @Bean("deepSeekReasoningTextModel")
        public StreamingChatModel deepSeekReasoningTextModel() {
                log.info("[AI Config] Initializing REASONING TEXT model: {} (GitHub Models via OpenAI Official SDK)",
                                reasoningModelName);
                return OpenAiOfficialStreamingChatModel.builder()
                                .apiKey(githubToken)
                                .modelName(reasoningModelName)
                                .isGitHubModels(true)
                                .temperature(0.5)
                                .parallelToolCalls(false)
                                .timeout(Duration.ofSeconds(timeoutSeconds * 2))
                                .build();
        }

        @Bean("groqOssReasoningModel")
        @ConditionalOnProperty(value = "ai.groq.enabled", havingValue = "true")
        public StreamingChatModel groqOssReasoningModel() {
                if (groqApiKey == null || groqApiKey.isBlank()) {
                        throw new IllegalStateException("ai.groq.enabled=true but ai.groq.api-key is missing");
                }

                log.info("[AI Config] Initializing OSS REASONING model: {} (Groq OpenAI-compatible API)",
                                groqReasoningModelName);
                return OpenAiOfficialStreamingChatModel.builder()
                                .apiKey(groqApiKey)
                                .baseUrl(groqBaseUrl)
                                .modelName(groqReasoningModelName)
                                .temperature(0.4)
                                .parallelToolCalls(true)
                                .timeout(Duration.ofSeconds(timeoutSeconds * 2))
                                .build();
        }

        @Bean("groqOssReasoningTextModel")
        @ConditionalOnProperty(value = "ai.groq.enabled", havingValue = "true")
        public StreamingChatModel groqOssReasoningTextModel() {
                if (groqApiKey == null || groqApiKey.isBlank()) {
                        throw new IllegalStateException("ai.groq.enabled=true but ai.groq.api-key is missing");
                }

                log.info("[AI Config] Initializing OSS REASONING TEXT model: {} (Groq OpenAI-compatible API)",
                                groqReasoningModelName);
                return OpenAiOfficialStreamingChatModel.builder()
                                .apiKey(groqApiKey)
                                .baseUrl(groqBaseUrl)
                                .modelName(groqReasoningModelName)
                                .temperature(0.4)
                                .parallelToolCalls(false)
                                .timeout(Duration.ofSeconds(timeoutSeconds * 2))
                                .build();
        }

        @Bean("groqGatekeeperModel")
        @ConditionalOnProperty(value = "ai.groq.enabled", havingValue = "true")
        public ChatModel groqGatekeeperModel() {
                if (groqApiKey == null || groqApiKey.isBlank()) {
                        throw new IllegalStateException("ai.groq.enabled=true but ai.groq.api-key is missing");
                }

                log.info("[AI Config] Initializing GATEKEEPER model: {} (Groq OpenAI-compatible API)",
                                groqGatekeeperModelName);
                return OpenAiOfficialChatModel.builder()
                                .apiKey(groqApiKey)
                                .baseUrl(groqBaseUrl)
                                .modelName(groqGatekeeperModelName)
                                .temperature(0.0)
                                .timeout(Duration.ofSeconds(timeoutSeconds))
                                .build();
        }
}
