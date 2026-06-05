package com.taskpilot.ai.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GeminiMode;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialChatModel;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

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
        @Value("${ai.gemini.fallback1-model:gemini-2.5-flash}")
        private String geminiFallback1ModelName;

        @Value("${ai.gemini.fallback2-model:gemini-3.1-flash-lite}")
        private String geminiFallback2ModelName;

        @Value("${ai.gemini.fallback3-model:gemini-2.5-flash-lite}")
        private String geminiFallback3ModelName;

        @Value("${ai.gemini.fallback4-model:gemini-2.5-pro}")
        private String geminiFallback4ModelName;

        @Value("${ai.gemini.fallback5-model:gemini-2.0-flash}")
        private String geminiFallback5ModelName;

        @Value("${ai.gemini.fallback6-model:gemini-2.0-flash-lite}")
        private String geminiFallback6ModelName;

        @Value("${ai.gemini.fallback7-model:gemini-3.1-pro-preview}")
        private String geminiFallback7ModelName;

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

        @Value("${ai.openrouter.api-key:}")
        private String openRouterApiKey;

        @Value("${ai.openrouter.api-keys:}")
        private String openRouterApiKeys;

        @Value("${ai.openrouter.base-url:https://openrouter.ai/api/v1}")
        private String openRouterBaseUrl;

        @Value("${ai.openrouter.reasoning-model:nvidia/nemotron-3-ultra-550b-a55b:free}")
        private String openRouterReasoningModelName;

        @Value("${ai.openrouter.reasoning-fallback1-model:nvidia/nemotron-3-super-120b-a12b:free}")
        private String openRouterReasoningFallback1ModelName;

        @Value("${ai.openrouter.reasoning-fallback2-model:poolside/laguna-m.1:free}")
        private String openRouterReasoningFallback2ModelName;

        @Value("${ai.openrouter.reasoning-fallback3-model:openai/gpt-oss-120b:free}")
        private String openRouterReasoningFallback3ModelName;

        @Value("${ai.openrouter.reasoning-fallback4-model:moonshotai/kimi-k2.6:free}")
        private String openRouterReasoningFallback4ModelName;

        @Value("${ai.openrouter.reasoning-fallback5-model:z-ai/glm-4.5-air:free}")
        private String openRouterReasoningFallback5ModelName;

        @Value("${ai.openrouter.reasoning-fallback6-model:poolside/laguna-xs.2:free}")
        private String openRouterReasoningFallback6ModelName;

        @Value("${ai.openrouter.reasoning-fallback7-model:nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free}")
        private String openRouterReasoningFallback7ModelName;

        @Value("${ai.openrouter.reasoning-fallback8-model:google/gemma-4-31b-it:free}")
        private String openRouterReasoningFallback8ModelName;

        @Value("${ai.openrouter.reasoning-fallback9-model:google/gemma-4-26b-a4b-it:free}")
        private String openRouterReasoningFallback9ModelName;

        @Value("${ai.openrouter.reasoning-fallback10-model:openai/gpt-oss-20b:free}")
        private String openRouterReasoningFallback10ModelName;

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

        @Bean("geminiFallback5Model")
        public StreamingChatModel geminiFallback5Model() {
                log.info("[AI Config] Initializing Gemini fallback-5: {}", geminiFallback5ModelName);
                return geminiStreamingModel(geminiFallback5ModelName);
        }

        @Bean("geminiFallback6Model")
        public StreamingChatModel geminiFallback6Model() {
                log.info("[AI Config] Initializing Gemini fallback-6: {}", geminiFallback6ModelName);
                return geminiStreamingModel(geminiFallback6ModelName);
        }

        @Bean("geminiFallback7Model")
        public StreamingChatModel geminiFallback7Model() {
                log.info("[AI Config] Initializing Gemini fallback-7: {}", geminiFallback7ModelName);
                return geminiStreamingModel(geminiFallback7ModelName);
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

        @Bean("openRouterReasoningModel")
        @ConditionalOnProperty(value = "ai.openrouter.enabled", havingValue = "true")
        public StreamingChatModel openRouterReasoningModel() {
                return openRouterReasoningModel(openRouterReasoningModelName, true);
        }

        @Bean("openRouterReasoningFallback1Model")
        @ConditionalOnProperty(value = "ai.openrouter.enabled", havingValue = "true")
        public StreamingChatModel openRouterReasoningFallback1Model() {
                return openRouterReasoningModel(openRouterReasoningFallback1ModelName, true);
        }

        @Bean("openRouterReasoningFallback2Model")
        @ConditionalOnProperty(value = "ai.openrouter.enabled", havingValue = "true")
        public StreamingChatModel openRouterReasoningFallback2Model() {
                return openRouterReasoningModel(openRouterReasoningFallback2ModelName, true);
        }

        @Bean("openRouterReasoningFallback3Model")
        @ConditionalOnProperty(value = "ai.openrouter.enabled", havingValue = "true")
        public StreamingChatModel openRouterReasoningFallback3Model() {
                return openRouterReasoningModel(openRouterReasoningFallback3ModelName, true);
        }

        @Bean("openRouterReasoningFallback4Model")
        @ConditionalOnProperty(value = "ai.openrouter.enabled", havingValue = "true")
        public StreamingChatModel openRouterReasoningFallback4Model() {
                return openRouterReasoningModel(openRouterReasoningFallback4ModelName, true);
        }

        @Bean("openRouterReasoningFallback5Model")
        @ConditionalOnProperty(value = "ai.openrouter.enabled", havingValue = "true")
        public StreamingChatModel openRouterReasoningFallback5Model() {
                return openRouterReasoningModel(openRouterReasoningFallback5ModelName, true);
        }

        @Bean("openRouterReasoningFallback6Model")
        @ConditionalOnProperty(value = "ai.openrouter.enabled", havingValue = "true")
        public StreamingChatModel openRouterReasoningFallback6Model() {
                return openRouterReasoningModel(openRouterReasoningFallback6ModelName, true);
        }

        @Bean("openRouterReasoningFallback7Model")
        @ConditionalOnProperty(value = "ai.openrouter.enabled", havingValue = "true")
        public StreamingChatModel openRouterReasoningFallback7Model() {
                return openRouterReasoningModel(openRouterReasoningFallback7ModelName, true);
        }

        @Bean("openRouterReasoningFallback8Model")
        @ConditionalOnProperty(value = "ai.openrouter.enabled", havingValue = "true")
        @ConditionalOnExpression("'${ai.openrouter.reasoning-fallback8-model:}'.trim().length() > 0")
        public StreamingChatModel openRouterReasoningFallback8Model() {
                return openRouterReasoningModel(openRouterReasoningFallback8ModelName, true);
        }

        @Bean("openRouterReasoningFallback9Model")
        @ConditionalOnProperty(value = "ai.openrouter.enabled", havingValue = "true")
        @ConditionalOnExpression("'${ai.openrouter.reasoning-fallback9-model:}'.trim().length() > 0")
        public StreamingChatModel openRouterReasoningFallback9Model() {
                return openRouterReasoningModel(openRouterReasoningFallback9ModelName, true);
        }

        @Bean("openRouterReasoningFallback10Model")
        @ConditionalOnProperty(value = "ai.openrouter.enabled", havingValue = "true")
        @ConditionalOnExpression("'${ai.openrouter.reasoning-fallback10-model:}'.trim().length() > 0")
        public StreamingChatModel openRouterReasoningFallback10Model() {
                return openRouterReasoningModel(openRouterReasoningFallback10ModelName, true);
        }

        @Bean("openRouterReasoningTextModel")
        @ConditionalOnProperty(value = "ai.openrouter.enabled", havingValue = "true")
        public StreamingChatModel openRouterReasoningTextModel() {
                return openRouterReasoningModel(openRouterReasoningModelName, false);
        }

        private StreamingChatModel openRouterReasoningModel(String modelName, boolean parallelToolCalls) {
                List<String> apiKeys = openRouterApiKeyPool();
                if (apiKeys.isEmpty()) {
                        throw new IllegalStateException("ai.openrouter.enabled=true but no OpenRouter API keys are configured");
                }

                log.info("[AI Config] Initializing OpenRouter model: {} with {} API key(s)", modelName, apiKeys.size());
                List<OpenRouterMultiKeyStreamingChatModel.KeyedModel> keyedModels = apiKeys.stream()
                                .map(apiKey -> new OpenRouterMultiKeyStreamingChatModel.KeyedModel(
                                                maskOpenRouterKey(apiKey),
                                                singleOpenRouterModel(apiKey, modelName, parallelToolCalls)))
                                .toList();
                return new OpenRouterMultiKeyStreamingChatModel(modelName, keyedModels);
        }

        private StreamingChatModel singleOpenRouterModel(String apiKey, String modelName, boolean parallelToolCalls) {
                if (parallelToolCalls) {
                        return OpenAiOfficialStreamingChatModel.builder()
                                        .apiKey(apiKey)
                                        .baseUrl(openRouterBaseUrl)
                                        .modelName(modelName)
                                        .temperature(0.4)
                                        .parallelToolCalls(true)
                                        .timeout(Duration.ofSeconds(timeoutSeconds * 2))
                                        .build();
                }
                return OpenAiOfficialStreamingChatModel.builder()
                                .apiKey(apiKey)
                                .baseUrl(openRouterBaseUrl)
                                .modelName(modelName)
                                .temperature(0.4)
                                .timeout(Duration.ofSeconds(timeoutSeconds * 2))
                                .build();
        }

        private List<String> openRouterApiKeyPool() {
                String raw = String.join(",",
                                openRouterApiKey == null ? "" : openRouterApiKey,
                                openRouterApiKeys == null ? "" : openRouterApiKeys);
                return Arrays.stream(raw.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isBlank())
                                .distinct()
                                .toList();
        }

        private String maskOpenRouterKey(String apiKey) {
                if (apiKey == null || apiKey.length() < 12) {
                        return "<redacted>";
                }
                return apiKey.substring(0, 8) + "..." + apiKey.substring(apiKey.length() - 4);
        }
}
