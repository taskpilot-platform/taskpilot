package com.taskpilot.ai.service;

import com.taskpilot.ai.util.TokenEstimationUtil;
import dev.langchain4j.model.chat.StreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SmartRoutingService {

    private final StreamingChatModel geminiFlashModel;
    private final StreamingChatModel gpt4oFallbackModel;
    private final TokenEstimationUtil tokenEstimationUtil;

    @Value("${ai.gemini.model-name}")
    private String geminiModelName;

    @Value("${ai.github.fallback-model:gpt-4o}")
    private String fallbackModelName;

    @Value("${ai.routing.token-threshold:8000}")
    private int tokenThreshold;

    public SmartRoutingService(
            StreamingChatModel geminiFlashModel,
            @Qualifier("gpt4oFallbackModel") StreamingChatModel gpt4oFallbackModel,
            TokenEstimationUtil tokenEstimationUtil) {
        this.geminiFlashModel = geminiFlashModel;
        this.gpt4oFallbackModel = gpt4oFallbackModel;
        this.tokenEstimationUtil = tokenEstimationUtil;
    }

    public StreamingChatModel selectModel(String userMessage, String contextHistory) {
        int estimatedTokens = tokenEstimationUtil.estimateTotal(userMessage, contextHistory);
        log.debug("[SmartRouting] Estimated tokens: {}, threshold: {}", estimatedTokens, tokenThreshold);

        if (estimatedTokens > tokenThreshold) {
            log.info("[SmartRouting] Routing to HEAVY model ({}) — tokens: {}", geminiModelName, estimatedTokens);
            return geminiFlashModel;
        }

        log.info("[SmartRouting] Routing to LIGHT model ({}) — tokens: {}", fallbackModelName, estimatedTokens);
        return gpt4oFallbackModel;
    }

    public StreamingChatModel getFallbackModel() {
        return gpt4oFallbackModel;
    }

    public StreamingChatModel getPrimaryModel() {
        return geminiFlashModel;
    }

    public String getModelName(StreamingChatModel model) {
        if (model == gpt4oFallbackModel)
            return fallbackModelName;
        if (model == geminiFlashModel)
            return geminiModelName;
        return model.getClass().getSimpleName();
    }
}