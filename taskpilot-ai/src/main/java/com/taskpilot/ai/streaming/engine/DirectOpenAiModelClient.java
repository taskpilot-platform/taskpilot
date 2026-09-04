package com.taskpilot.ai.streaming.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.ai.prompt.SystemPromptBuilder;
import com.taskpilot.ai.service.SmartRoutingService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Direct HTTP client for interacting with OpenAI-compatible endpoints (Gemma, Groq, Gemini OpenAI proxy).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DirectOpenAiModelClient {

    private final ObjectMapper objectMapper;
    private final SmartRoutingService routingService;
    private final SystemPromptBuilder promptBuilder;

    @Value("${ai.gemini.api-key:}")
    private String geminiApiKey;

    @Value("${ai.gemini.api-keys:}")
    private String geminiApiKeys;

    public ChatResponse callGemmaDirectly(ChatRequest chatRequest, String modelName, int toolRound, boolean requiresTools) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", modelName);
            body.put("temperature", 0.1);
            if (requiresTools) {
                body.put("max_tokens", 3500);
            } else {
                body.put("max_tokens", 1000);
            }

            String userText = "";
            if (chatRequest.messages() != null) {
                for (int i = chatRequest.messages().size() - 1; i >= 0; i--) {
                    if (chatRequest.messages().get(i) instanceof UserMessage userMsg) {
                        userText = userMsg.singleText();
                        break;
                    }
                }
            }

            boolean isWriteIntent = false;
            if (userText != null && !userText.isBlank()) {
                String normalizedMsg = routingService.normalize(userText);
                isWriteIntent = routingService.isWriteIntent(normalizedMsg);
            }
            log.info("[GeminiToolFix] callGemmaDirectly: userText='{}', isWriteIntent={}, toolRound={}", userText, isWriteIntent, toolRound);

            List<Map<String, Object>> openAiMessages = new ArrayList<>();
            if (chatRequest.messages() != null) {
                for (ChatMessage msg : chatRequest.messages()) {
                    if (msg instanceof SystemMessage sysMsg) {
                        String text = sysMsg.text();
                        String modified = text;
                        if (!requiresTools) {
                            modified = """
                                You are the Assistant of the TaskPilot system. Your purpose is to answer the user's question directly and concisely in Vietnamese based on the provided tool results in the conversation history.
                                DO NOT write any thinking process or explanation inside <think> or <thought> tags. Provide your final answer in Vietnamese directly and concisely to optimize response speed.
                                DO NOT call any tools.
                                """;
                        }
                        openAiMessages.add(mapMessageToOpenAi(SystemMessage.from(modified)));
                    } else {
                        openAiMessages.add(mapMessageToOpenAi(msg));
                    }
                }
            }
            body.put("messages", openAiMessages);

            if (chatRequest.toolSpecifications() != null && !chatRequest.toolSpecifications().isEmpty()) {
                List<Map<String, Object>> openAiTools = new ArrayList<>();
                for (var spec : chatRequest.toolSpecifications()) {
                    openAiTools.add(mapToolToOpenAi(spec));
                }
                body.put("tools", openAiTools);
            }

            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            List<String> keys = new ArrayList<>();
            if (geminiApiKeys != null && !geminiApiKeys.isBlank()) {
                for (String k : geminiApiKeys.split(",")) {
                    String trimmed = k.trim();
                    if (!trimmed.isEmpty()) {
                        keys.add(trimmed);
                    }
                }
            }
            if (keys.isEmpty() && geminiApiKey != null && !geminiApiKey.isBlank()) {
                keys.add(geminiApiKey.trim());
            }

            int timeoutSeconds = 95;
            if (chatRequest.toolSpecifications() != null) {
                for (var spec : chatRequest.toolSpecifications()) {
                    if ("smartQuery".equals(spec.name())) {
                        timeoutSeconds = 105;
                        break;
                    }
                }
            }

            int maxAttempts = Math.max(2, keys.size());
            HttpResponse<String> httpResponse = null;
            Exception lastEx = null;
            int currentKeyIndex = 0;
            boolean useSimplerPrompt = false;

            long stepStartTime = System.currentTimeMillis();

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                String activeKey = keys.get(currentKeyIndex);
                try {
                    long elapsedMs = System.currentTimeMillis() - stepStartTime;
                    long budgetRemainingMs = 200000 - elapsedMs;
                    if (budgetRemainingMs < 5000) {
                        log.warn("[GeminiToolFix] Step time budget exceeded, stopping retries. Remaining budget: {}ms", budgetRemainingMs);
                        break;
                    }

                    int currentRequestTimeout = Math.min(timeoutSeconds, (int) (budgetRemainingMs / 1000));

                    Map<String, Object> bodyToUse = body;
                    if (useSimplerPrompt) {
                        log.info("[GeminiToolFix] Rebuilding request messages with simpler system prompt for attempt {}", attempt);
                        Map<String, Object> newBody = new LinkedHashMap<>(body);
                        List<Map<String, Object>> newMessages = new ArrayList<>();
                        if (chatRequest.messages() != null) {
                            for (ChatMessage msg : chatRequest.messages()) {
                                if (msg instanceof SystemMessage) {
                                    String simplifiedPrompt = promptBuilder.buildSimplerGemmaSystemPrompt(isWriteIntent);
                                    newMessages.add(mapMessageToOpenAi(SystemMessage.from(simplifiedPrompt)));
                                } else {
                                    newMessages.add(mapMessageToOpenAi(msg));
                                }
                            }
                        }
                        newBody.put("messages", newMessages);
                        newBody.put("temperature", 0.3);
                        bodyToUse = newBody;
                    }

                    String requestBodyJson = objectMapper.writeValueAsString(bodyToUse);
                    HttpRequest httpRequest = HttpRequest.newBuilder()
                            .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/openai/v1/chat/completions"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + activeKey)
                            .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                            .timeout(Duration.ofSeconds(currentRequestTimeout))
                            .build();

                    log.info("[GeminiToolFix] Direct HTTP POST request to model: {}, attempt {}/{} with key index {} (timeout: {}s)",
                            modelName, attempt, maxAttempts, currentKeyIndex + 1, currentRequestTimeout);
                    httpResponse = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());

                    if (httpResponse.statusCode() >= 500 || httpResponse.statusCode() >= 400) {
                        log.warn("[GeminiToolFix] Attempt {} failed with status code {}: {}", attempt, httpResponse.statusCode(), httpResponse.body());
                        lastEx = new RuntimeException("API returned error status: " + httpResponse.statusCode());
                        currentKeyIndex = (currentKeyIndex + 1) % keys.size();
                        if (attempt < maxAttempts) {
                            Thread.sleep(500L);
                            continue;
                        }
                    } else {
                        String responseBody = httpResponse.body();
                        JsonNode root = objectMapper.readTree(responseBody);
                        JsonNode choice = root.path("choices").get(0);
                        if (!choice.isMissingNode() && !choice.isNull()) {
                            String finishReason = choice.path("finish_reason").asText("");
                            if (finishReason.contains("MALFORMED_FUNCTION_CALL") || finishReason.contains("malformed")) {
                                log.warn("[GeminiToolFix] Attempt {} returned finish_reason: {}. Retrying with simplified prompt...", attempt, finishReason);
                                useSimplerPrompt = true;
                                lastEx = new RuntimeException("Gemini returned malformed function call: " + finishReason);
                                currentKeyIndex = (currentKeyIndex + 1) % keys.size();
                                if (attempt < maxAttempts) {
                                    Thread.sleep(500L);
                                    continue;
                                }
                            }
                        }
                        break;
                    }
                } catch (Exception e) {
                    log.warn("[GeminiToolFix] Attempt {} hit exception: {}", attempt, e.getMessage());
                    lastEx = e;
                    currentKeyIndex = (currentKeyIndex + 1) % keys.size();
                    if (attempt < maxAttempts) {
                        Thread.sleep(500L);
                        continue;
                    }
                }
            }

            if (httpResponse == null || httpResponse.statusCode() >= 400) {
                throw new RuntimeException("Gemini OpenAI API call failed after " + maxAttempts + " attempts", lastEx);
            }

            String responseBody = httpResponse.body();
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choice = root.path("choices").get(0);
            if (choice.isMissingNode() || choice.isNull()) {
                throw new RuntimeException("Invalid API response: choices array is empty or null. Response: " + responseBody);
            }

            JsonNode messageNode = choice.path("message");
            String content = messageNode.path("content").asText(null);

            List<ToolExecutionRequest> toolRequests = new ArrayList<>();
            JsonNode toolCallsNode = messageNode.path("tool_calls");
            if (toolCallsNode.isArray()) {
                for (JsonNode tc : toolCallsNode) {
                    String id = tc.path("id").asText();
                    JsonNode fn = tc.path("function");
                    String name = fn.path("name").asText();
                    String args = fn.path("arguments").asText();
                    toolRequests.add(ToolExecutionRequest.builder()
                            .id(id)
                            .name(name)
                            .arguments(args)
                            .build());
                }
            }

            AiMessage aiMessage;
            if (!toolRequests.isEmpty()) {
                aiMessage = AiMessage.from(content == null ? "" : content, toolRequests);
            } else {
                String safeContent = (content == null || content.isBlank()) ? "Không có phản hồi từ mô hình." : content;
                aiMessage = AiMessage.from(safeContent);
            }

            int promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
            int completionTokens = root.path("usage").path("completion_tokens").asInt(0);
            int totalTokens = root.path("usage").path("total_tokens").asInt(promptTokens + completionTokens);
            TokenUsage tokenUsage = new TokenUsage(promptTokens, completionTokens, totalTokens);

            return ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .tokenUsage(tokenUsage)
                    .build();

        } catch (Exception e) {
            log.error("[GeminiToolFix] Direct HTTP call failed: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public Map<String, Object> mapMessageToOpenAi(ChatMessage message) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (message instanceof SystemMessage sys) {
            map.put("role", "system");
            map.put("content", sys.text());
        } else if (message instanceof UserMessage user) {
            map.put("role", "user");
            map.put("content", user.singleText());
        } else if (message instanceof AiMessage ai) {
            map.put("role", "assistant");
            if (ai.text() != null) {
                map.put("content", ai.text());
            }
            if (ai.hasToolExecutionRequests()) {
                List<Map<String, Object>> toolCalls = new ArrayList<>();
                for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                    Map<String, Object> tc = new LinkedHashMap<>();
                    tc.put("id", req.id());
                    tc.put("type", "function");
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", req.name());
                    fn.put("arguments", req.arguments());
                    tc.put("function", fn);
                    toolCalls.add(tc);
                }
                map.put("tool_calls", toolCalls);
            }
        } else if (message instanceof ToolExecutionResultMessage result) {
            map.put("role", "tool");
            map.put("tool_call_id", result.id());
            map.put("name", result.toolName());
            map.put("content", result.text());
        }
        return map;
    }

    public Map<String, Object> mapToolToOpenAi(ToolSpecification spec) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "function");
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", spec.name());
        if (spec.description() != null) {
            fn.put("description", spec.description().replace("\n", " ").replaceAll("\\s+", " ").trim());
        }
        if (spec.parameters() != null) {
            fn.put("parameters", jsonSchemaElementToMap(spec.parameters()));
        } else {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("type", "object");
            params.put("additionalProperties", true);
            fn.put("parameters", params);
        }
        map.put("function", fn);
        return map;
    }

    public Map<String, Object> jsonSchemaElementToMap(JsonSchemaElement element) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (element == null) {
            return map;
        }

        String type = "string";
        if (element instanceof JsonObjectSchema) {
            type = "object";
        } else if (element instanceof JsonArraySchema) {
            type = "array";
        } else if (element instanceof JsonIntegerSchema) {
            type = "integer";
        } else if (element instanceof JsonNumberSchema) {
            type = "number";
        } else if (element instanceof JsonBooleanSchema) {
            type = "boolean";
        }

        map.put("type", type);

        if (element.description() != null) {
            map.put("description", element.description().replace("\n", " ").replaceAll("\\s+", " ").trim());
        }

        if (element instanceof JsonObjectSchema objSchema) {
            if (objSchema.properties() != null && !objSchema.properties().isEmpty()) {
                Map<String, Object> props = new LinkedHashMap<>();
                for (var entry : objSchema.properties().entrySet()) {
                    props.put(entry.getKey(), jsonSchemaElementToMap(entry.getValue()));
                }
                map.put("properties", props);
            } else {
                map.put("additionalProperties", true);
            }
            if (objSchema.required() != null) {
                List<String> cleanedRequired = new ArrayList<>();
                for (String reqField : objSchema.required()) {
                    var fieldSchema = objSchema.properties().get(reqField);
                    if (fieldSchema != null && fieldSchema.description() != null) {
                        String desc = fieldSchema.description().toLowerCase();
                        if (desc.startsWith("optional") || desc.contains("(optional)") || desc.contains("is optional")) {
                            continue;
                        }
                    }
                    cleanedRequired.add(reqField);
                }
                map.put("required", cleanedRequired);
            }
        } else if (element instanceof JsonArraySchema arrSchema) {
            if (arrSchema.items() != null) {
                map.put("items", jsonSchemaElementToMap(arrSchema.items()));
            }
        }
        return map;
    }
}
