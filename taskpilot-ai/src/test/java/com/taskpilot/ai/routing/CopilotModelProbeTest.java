package com.taskpilot.ai.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.taskpilot.ai.config.AiModelConfig;
import com.taskpilot.ai.context.ChatMessageSanitizer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Tag("provider-live")
class CopilotModelProbeTest {

    private static final String PROBE_TOKEN = "TP-PROBE-4271";
    private static final long STEP_TIMEOUT_SECONDS = 30L;

    record Target(String provider, String model) {}

    @Test
    void probeCandidateModelsAndWriteReport() throws Exception {
        AiModelConfig config = new AiModelConfig();
        ReflectionTestUtils.setField(config, "geminiApiKey", env("GEMINI_API_KEY"));
        ReflectionTestUtils.setField(config, "groqApiKey", env("GROQ_API_KEY"));
        ReflectionTestUtils.setField(config, "groqApiKeys", env("GROQ_API_KEYS"));
        ReflectionTestUtils.setField(config, "groqBaseUrl", envOrDefault("AI_GROQ_BASE_URL", "https://api.groq.com/openai/v1"));
        ReflectionTestUtils.setField(config, "openRouterApiKey", env("OPENROUTER_API_KEY"));
        ReflectionTestUtils.setField(config, "openRouterApiKeys", env("OPENROUTER_API_KEYS"));
        ReflectionTestUtils.setField(config, "openRouterBaseUrl", envOrDefault("AI_OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1"));
        ReflectionTestUtils.setField(config, "timeoutSeconds", 25);
        ReflectionTestUtils.setField(config, "geminiTimeoutSeconds", 30);

        ChatMessageSanitizer sanitizer = new ChatMessageSanitizer();
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        List<Target> targets = List.of(
                new Target("GROQ", "openai/gpt-oss-120b"),
                new Target("GROQ", "openai/gpt-oss-20b"),
                new Target("GEMINI", "gemini-3.5-flash"),
                new Target("GEMINI", "gemini-2.5-flash"),
                new Target("GEMINI", "gemini-3.1-flash-lite"),
                new Target("GEMINI", "gemini-2.5-flash-lite"),
                new Target("GEMINI", "gemini-2.5-pro"),
                new Target("GEMINI", "gemini-3.5-flash-lite"),
                new Target("GEMINI", "gemini-3.8-flash"),
                new Target("GEMINI", "gemini-3.1-pro-preview"),
                new Target("OPENROUTER", "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free"),
                new Target("OPENROUTER", "google/gemma-4-26b-a4b-it:free")
        );

        ToolSpecification probeTool = ToolSpecification.builder()
                .name("getProbeValue")
                .description("Look up the secret probe value for a given key.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("key", "Lookup key name")
                        .required("key")
                        .build())
                .build();

        List<Map<String, Object>> results = new ArrayList<>();
        for (Target target : targets) {
            Map<String, Object> record = probeTarget(config, sanitizer, mapper, probeTool, target);
            results.add(record);
            System.out.printf("[PROBE] %-10s | %-52s | step1=%-10s | step2=%-10s | text=%-5s | ttftMs=%-6s | err=%s%n",
                    target.provider(), target.model(),
                    record.get("step1ToolCall"), record.get("step2ToolOutput"),
                    record.get("textOk"), record.get("ttftMs"), record.get("error"));
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("results", results);

        Path reportPath = Path.of("target", "copilot-model-probe", "report.json");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, mapper.writeValueAsString(report));
        System.out.println("[PROBE] Wrote report to " + reportPath.toAbsolutePath());
    }

    private Map<String, Object> probeTarget(
            AiModelConfig config,
            ChatMessageSanitizer sanitizer,
            ObjectMapper mapper,
            ToolSpecification probeTool,
            Target target) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", target.provider());
        out.put("model", target.model());

        StreamingChatModel model = config.getOrCreateDynamicModel(target.provider(), target.model(), "reasoning");
        if (model == null) {
            out.put("step1ToolCall", "FAIL");
            out.put("step2ToolOutput", "SKIPPED");
            out.put("textOk", false);
            out.put("error", "model_null");
            return out;
        }

        boolean isGemini = "GEMINI".equalsIgnoreCase(target.provider());
        SystemMessage sysMsg = SystemMessage.from("You are a test assistant. Always use available tools when asked, then report exact returned values.");
        UserMessage userMsg = UserMessage.from("Call getProbeValue with key 'alpha' and then reply with the exact returned value.");

        for (int attempt = 1; attempt <= 2; attempt++) {
            out.put("attempts", attempt);
            StepOutcome step1 = callModel(model, ChatRequest.builder()
                    .messages(List.of(sysMsg, userMsg))
                    .toolSpecifications(List.of(probeTool))
                    .toolChoice(ToolChoice.AUTO)
                    .maxOutputTokens(512)
                    .build());

            out.put("ttftMs", step1.ttftMs());
            if (step1.error() != null) {
                out.put("step1ToolCall", "ERROR");
                out.put("step2ToolOutput", "SKIPPED");
                out.put("textOk", false);
                out.put("error", summarizeError(step1.error()));
                if (attempt == 1 && isTransient429(step1.error())) {
                    sleepQuietly(15_000L);
                    continue;
                }
                return out;
            }

            AiMessage ai1 = step1.response() != null ? step1.response().aiMessage() : null;
            List<ToolExecutionRequest> requests = ai1 != null && ai1.hasToolExecutionRequests()
                    ? ai1.toolExecutionRequests()
                    : List.of();
            ToolExecutionRequest matchingReq = requests.stream()
                    .filter(r -> "getProbeValue".equals(r.name()) && isValidJsonArgs(mapper, r.arguments()))
                    .findFirst()
                    .orElse(null);

            if (matchingReq == null) {
                String text1 = ai1 != null && ai1.text() != null ? ai1.text().trim() : "";
                boolean textOk = !text1.isBlank() || !step1.streamedText().isBlank();
                out.put("step1ToolCall", "NO_TOOL_CALL");
                out.put("step2ToolOutput", "SKIPPED");
                out.put("textOk", textOk);
                out.put("error", textOk ? "returned_text_without_tool_call" : "empty_response");
                return out;
            }

            out.put("step1ToolCall", "PASS");
            List<ChatMessage> rawHistory = List.of(
                    sysMsg,
                    userMsg,
                    ai1,
                    ToolExecutionResultMessage.from(matchingReq, "{\"value\":\"" + PROBE_TOKEN + "\"}")
            );
            List<ChatMessage> round2Messages = sanitizer.cleanAndAlternateRoles(
                    sanitizer.sanitizeHistoryForTools(rawHistory), isGemini);

            StepOutcome step2 = callModel(model, ChatRequest.builder()
                    .messages(round2Messages)
                    .toolSpecifications(List.of(probeTool))
                    .maxOutputTokens(512)
                    .build());

            if (step2.error() != null) {
                out.put("step2ToolOutput", "ERROR");
                out.put("textOk", false);
                out.put("error", "step2: " + summarizeError(step2.error()));
                if (attempt == 1 && isTransient429(step2.error())) {
                    sleepQuietly(15_000L);
                    continue;
                }
                return out;
            }

            String text2 = "";
            if (step2.response() != null && step2.response().aiMessage() != null && step2.response().aiMessage().text() != null) {
                text2 = step2.response().aiMessage().text();
            }
            if (text2.isBlank()) {
                text2 = step2.streamedText();
            }
            boolean hasToken = text2.contains(PROBE_TOKEN);
            out.put("step2ToolOutput", hasToken ? "PASS" : "FAIL_MISSING_TOKEN");
            out.put("textOk", !text2.isBlank());
            out.put("error", hasToken ? null : "step2_missing_probe_token");
            return out;
        }
        return out;
    }

    private record StepOutcome(ChatResponse response, String streamedText, Long ttftMs, Throwable error) {}

    private StepOutcome callModel(StreamingChatModel model, ChatRequest request) {
        CompletableFuture<StepOutcome> future = new CompletableFuture<>();
        long start = System.currentTimeMillis();
        AtomicLong ttft = new AtomicLong(-1L);
        StringBuilder streamed = new StringBuilder();
        try {
            model.chat(request, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    ttft.compareAndSet(-1L, System.currentTimeMillis() - start);
                    if (partialResponse != null) {
                        streamed.append(partialResponse);
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    ttft.compareAndSet(-1L, System.currentTimeMillis() - start);
                    future.complete(new StepOutcome(completeResponse, streamed.toString(),
                            ttft.get() >= 0 ? ttft.get() : null, null));
                }

                @Override
                public void onError(Throwable error) {
                    future.complete(new StepOutcome(null, streamed.toString(),
                            ttft.get() >= 0 ? ttft.get() : null, error));
                }
            });
            return future.get(STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Throwable t) {
            return new StepOutcome(null, streamed.toString(), ttft.get() >= 0 ? ttft.get() : null, t);
        }
    }

    private boolean isValidJsonArgs(ObjectMapper mapper, String args) {
        if (args == null || args.isBlank()) return false;
        try {
            mapper.readTree(args);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isTransient429(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            String msg = cur.getMessage();
            if (cur.getClass().getSimpleName().contains("RateLimit")
                    || (msg != null && (msg.contains("429") || msg.toLowerCase().contains("rate limit")))) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private String summarizeError(Throwable error) {
        if (error == null) return null;
        Throwable cur = error;
        while (cur.getCause() != null && (cur instanceof java.util.concurrent.CompletionException
                || cur instanceof java.util.concurrent.ExecutionException)) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage() == null ? "" : cur.getMessage().replaceAll("\\s+", " ");
        if (msg.length() > 180) {
            msg = msg.substring(0, 180) + "...";
        }
        return cur.getClass().getSimpleName() + ": " + msg;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String env(String key) {
        String val = System.getenv(key);
        return val != null ? val : "";
    }

    private String envOrDefault(String key, String fallback) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : fallback;
    }
}
