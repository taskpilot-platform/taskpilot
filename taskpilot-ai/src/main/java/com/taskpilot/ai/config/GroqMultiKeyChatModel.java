package com.taskpilot.ai.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class GroqMultiKeyChatModel implements ChatModel {

    private static final long COOLDOWN_MS = 10L * 60L * 1000L; // 10 minutes
    private static final Map<String, Long> TRANSIENT_EXHAUSTED_UNTIL_BY_MODEL_AND_KEY = new ConcurrentHashMap<>();

    private final String modelName;
    private final List<KeyedModel> keyedModels;
    private final AtomicInteger nextStartIndex = new AtomicInteger();

    public GroqMultiKeyChatModel(String modelName, List<KeyedModel> keyedModels) {
        if (keyedModels == null || keyedModels.isEmpty()) {
            throw new IllegalArgumentException("Groq model " + modelName + " requires at least one API key");
        }
        this.modelName = modelName;
        this.keyedModels = List.copyOf(keyedModels);
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        int startIndex = Math.floorMod(nextStartIndex.getAndIncrement(), keyedModels.size());
        int total = keyedModels.size();
        Throwable lastError = null;

        for (int attemptedKeys = 0; attemptedKeys < total; attemptedKeys++) {
            int keyIndex = nextAvailableKeyIndex(startIndex, attemptedKeys);
            if (keyIndex < 0) {
                break;
            }

            KeyedModel keyedModel = keyedModels.get(keyIndex);
            log.info("[Groq] Calling non-streaming model {} with key {} ({}/{})",
                    modelName, keyedModel.keyLabel(), attemptedKeys + 1, total);
            try {
                return keyedModel.model().chat(chatRequest);
            } catch (Throwable error) {
                lastError = error;
                if (isRetryableGroq429(error)) {
                    markKeyTemporarilyExhausted(keyIndex, keyedModel.keyLabel(), error);
                    // continue to next key
                } else {
                    throw error;
                }
            }
        }

        throw new IllegalStateException("All Groq keys are temporarily exhausted for " + modelName, lastError);
    }

    public int keyCount() {
        return keyedModels.size();
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

    private void markKeyTemporarilyExhausted(int keyIndex, String keyLabel, Throwable error) {
        TRANSIENT_EXHAUSTED_UNTIL_BY_MODEL_AND_KEY.put(
                transientExhaustionKey(modelName, keyLabel),
                System.currentTimeMillis() + COOLDOWN_MS);
        log.warn("[Groq] Key {} hit 429 for non-streaming model {}. Cooling down for {}s. Cause: {}",
                keyLabel, modelName, COOLDOWN_MS / 1000, error.getMessage());
    }

    private String transientExhaustionKey(String modelName, String keyLabel) {
        return modelName + "|" + keyLabel;
    }

    private boolean isRetryableGroq429(Throwable error) {
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

    public record KeyedModel(String keyLabel, ChatModel model) {
    }
}
