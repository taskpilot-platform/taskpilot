package com.taskpilot.ai.config;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class GroqMultiKeyStreamingChatModel implements StreamingChatModel {

    private static final long COOLDOWN_MS = 10L * 60L * 1000L; // 10 minutes
    private static final Map<String, Long> TRANSIENT_EXHAUSTED_UNTIL_BY_MODEL_AND_KEY = new ConcurrentHashMap<>();

    private final String modelName;
    private final List<KeyedModel> keyedModels;
    private final AtomicInteger nextStartIndex = new AtomicInteger();

    public GroqMultiKeyStreamingChatModel(String modelName, List<KeyedModel> keyedModels) {
        if (keyedModels == null || keyedModels.isEmpty()) {
            throw new IllegalArgumentException("Groq model " + modelName + " requires at least one API key");
        }
        this.modelName = modelName;
        this.keyedModels = List.copyOf(keyedModels);
    }

    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        int startIndex = Math.floorMod(nextStartIndex.getAndIncrement(), keyedModels.size());
        chatWithAvailableKey(chatRequest, handler, startIndex, 0, null);
    }

    public int keyCount() {
        return keyedModels.size();
    }

    private void chatWithAvailableKey(
            ChatRequest chatRequest,
            StreamingChatResponseHandler handler,
            int searchStartIndex,
            int attemptedKeys,
            Throwable lastError) {
        int keyIndex = nextAvailableKeyIndex(searchStartIndex, attemptedKeys);
        if (keyIndex < 0) {
            handler.onError(lastError != null ? lastError
                    : new IllegalStateException("All Groq keys are temporarily exhausted for " + modelName));
            return;
        }

        KeyedModel keyedModel = keyedModels.get(keyIndex);
        log.info("[Groq] Calling streaming model {} with key {} ({}/{})",
                modelName, keyedModel.keyLabel(), attemptedKeys + 1, keyedModels.size());
        AtomicBoolean emittedPartial = new AtomicBoolean(false);
        try {
            keyedModel.model().chat(chatRequest, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    emittedPartial.set(true);
                    handler.onPartialResponse(partialResponse);
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    handler.onCompleteResponse(completeResponse);
                }

                @Override
                public void onError(Throwable error) {
                    if (!emittedPartial.get() && isRetryableGroq429(error)) {
                        markKeyTemporarilyExhausted(keyIndex, keyedModel.keyLabel(), error);
                        chatWithAvailableKey(chatRequest, handler, keyIndex + 1, attemptedKeys + 1, error);
                        return;
                    }
                    handler.onError(error);
                }
            });
        } catch (Throwable error) {
            if (!emittedPartial.get() && isRetryableGroq429(error)) {
                markKeyTemporarilyExhausted(keyIndex, keyedModel.keyLabel(), error);
                chatWithAvailableKey(chatRequest, handler, keyIndex + 1, attemptedKeys + 1, error);
                return;
            }
            throw error;
        }
    }

    private int nextAvailableKeyIndex(int searchStartIndex, int attemptedKeys) {
        long now = System.currentTimeMillis();
        int total = keyedModels.size();
        for (int offset = 0; offset < total; offset++) {
            int index = Math.floorMod(searchStartIndex + offset, total);
            if (attemptedKeys >= total) {
                return -1;
            }
            String keyLabel = keyedModels.get(index).keyLabel();
            String transientKey = transientExhaustionKey(modelName, keyLabel);
            Long transientExhaustedUntil = TRANSIENT_EXHAUSTED_UNTIL_BY_MODEL_AND_KEY.get(transientKey);
            if (transientExhaustedUntil == null || transientExhaustedUntil <= now) {
                if (transientExhaustedUntil != null) {
                    TRANSIENT_EXHAUSTED_UNTIL_BY_MODEL_AND_KEY.remove(transientKey);
                }
                return index;
            }
        }
        return -1;
    }

    private static final long MIN_COOLDOWN_MS = 60_000L;     // 1 min for transient errors
    private static final long RATE_LIMIT_COOLDOWN_MS = 120_000L; // 2 min for 429
    private static final long MAX_COOLDOWN_MS = 600_000L;     // 10 min max

    private void markKeyTemporarilyExhausted(int keyIndex, String keyLabel, Throwable error) {
        long baseCooldown = is429Error(error) ? RATE_LIMIT_COOLDOWN_MS : MIN_COOLDOWN_MS;
        String tKey = transientExhaustionKey(modelName, keyLabel);
        
        Long existingCooldownEnd = TRANSIENT_EXHAUSTED_UNTIL_BY_MODEL_AND_KEY.get(tKey);
        long now = System.currentTimeMillis();
        long cooldown = baseCooldown;
        
        if (existingCooldownEnd != null && existingCooldownEnd > now) {
            long remaining = existingCooldownEnd - now;
            cooldown = Math.min(remaining * 2, MAX_COOLDOWN_MS);
        }
        
        TRANSIENT_EXHAUSTED_UNTIL_BY_MODEL_AND_KEY.put(tKey, now + cooldown);
        log.warn("[Groq] Key {} hit error for streaming model {}. Cooling down for {}s and trying next key. Cause: {}",
                keyLabel, modelName, cooldown / 1000, error.getMessage());
    }

    private String transientExhaustionKey(String modelName, String keyLabel) {
        return modelName + "|" + keyLabel;
    }

    private boolean isRetryableGroq429(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("429")           // Rate limit
                    || lower.contains("404")         // Model not found / temp unavailable
                    || lower.contains("403")         // Permission denied / Blocked
                    || lower.contains("blocked")     // Model blocked
                    || lower.contains("503")         // Service unavailable
                    || lower.contains("502")         // Bad gateway
                    || lower.contains("timeout")     // Request timeout
                    || lower.contains("connection")  // Connection error
                    || lower.contains("refused")) {  // Connection refused
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
    
    private boolean is429Error(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("429")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public record KeyedModel(String keyLabel, StreamingChatModel model) {
    }
}
