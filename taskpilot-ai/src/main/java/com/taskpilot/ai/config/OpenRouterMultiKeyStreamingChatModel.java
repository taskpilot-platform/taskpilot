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
public class OpenRouterMultiKeyStreamingChatModel implements StreamingChatModel {

    private static final long DAILY_QUOTA_COOLDOWN_MS = 24L * 60L * 60L * 1000L;
    private static final long GENERIC_429_COOLDOWN_MS = 10L * 60L * 1000L;
    private static final Map<String, Long> DAILY_EXHAUSTED_UNTIL_BY_KEY_LABEL = new ConcurrentHashMap<>();
    private static final Map<String, Long> TRANSIENT_EXHAUSTED_UNTIL_BY_MODEL_AND_KEY = new ConcurrentHashMap<>();

    private final String modelName;
    private final List<KeyedModel> keyedModels;
    private final AtomicInteger nextStartIndex = new AtomicInteger();

    OpenRouterMultiKeyStreamingChatModel(String modelName, List<KeyedModel> keyedModels) {
        if (keyedModels == null || keyedModels.isEmpty()) {
            throw new IllegalArgumentException("OpenRouter model " + modelName + " requires at least one API key");
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
                    : new IllegalStateException("All OpenRouter keys are temporarily exhausted for " + modelName));
            return;
        }

        KeyedModel keyedModel = keyedModels.get(keyIndex);
        log.info("[OpenRouter] Calling model {} with key {} ({}/{})",
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
                    if (!emittedPartial.get() && isRetryableOpenRouter429(error)) {
                        markKeyTemporarilyExhausted(keyIndex, keyedModel.keyLabel(), error);
                        chatWithAvailableKey(chatRequest, handler, keyIndex + 1, attemptedKeys + 1, error);
                        return;
                    }
                    handler.onError(error);
                }
            });
        } catch (Throwable error) {
            if (!emittedPartial.get() && isRetryableOpenRouter429(error)) {
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
            Long dailyExhaustedUntil = DAILY_EXHAUSTED_UNTIL_BY_KEY_LABEL.get(keyLabel);
            if (dailyExhaustedUntil != null && dailyExhaustedUntil > now) {
                continue;
            }
            if (dailyExhaustedUntil != null) {
                DAILY_EXHAUSTED_UNTIL_BY_KEY_LABEL.remove(keyLabel);
            }

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
        boolean dailyQuotaError = isFreeDailyQuotaError(error);
        long cooldownMs = dailyQuotaError ? DAILY_QUOTA_COOLDOWN_MS : GENERIC_429_COOLDOWN_MS;
        if (dailyQuotaError) {
            DAILY_EXHAUSTED_UNTIL_BY_KEY_LABEL.put(keyLabel, System.currentTimeMillis() + cooldownMs);
        } else {
            TRANSIENT_EXHAUSTED_UNTIL_BY_MODEL_AND_KEY.put(
                    transientExhaustionKey(modelName, keyLabel),
                    System.currentTimeMillis() + cooldownMs);
        }
        log.warn("[OpenRouter] Key {} hit 429 for model {}. Cooling down {} for {}s and trying next key. Cause: {}",
                keyLabel, modelName, dailyQuotaError ? "globally" : "for this model", cooldownMs / 1000,
                error.getMessage());
    }

    private String transientExhaustionKey(String modelName, String keyLabel) {
        return modelName + "|" + keyLabel;
    }

    private boolean isRetryableOpenRouter429(Throwable error) {
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

    private boolean isFreeDailyQuotaError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("429")
                        && (lower.contains("free-models-per-day")
                        || lower.contains("free model requests per day"))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    record KeyedModel(String keyLabel, StreamingChatModel model) {
    }
}
