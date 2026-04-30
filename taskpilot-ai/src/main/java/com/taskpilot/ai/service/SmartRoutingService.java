package com.taskpilot.ai.service;

import com.taskpilot.ai.util.TokenEstimationUtil;
import dev.langchain4j.model.chat.StreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class SmartRoutingService {

    private final StreamingChatModel geminiFlashModel;
    private final StreamingChatModel gpt4oFallbackModel;
    private final StreamingChatModel deepSeekReasoningModel;
    private final StreamingChatModel groqOssReasoningModel;
    private final TokenEstimationUtil tokenEstimationUtil;

    @Value("${ai.gemini.model-name}")
    private String geminiModelName;

    @Value("${ai.github.fallback-model:gpt-4o}")
    private String fallbackModelName;

    @Value("${ai.github.reasoning-model:DeepSeek-R1}")
    private String deepSeekReasoningModelName;

    @Value("${ai.groq.reasoning-model:openai/gpt-oss-120b}")
    private String groqReasoningModelName;

    @Value("${ai.groq.enabled:false}")
    private boolean groqEnabled;

    @Value("${ai.routing.token-threshold:8000}")
    private int tokenThreshold;

    @Value("${ai.routing.reasoning-keywords:analyze,reason,strategy,architecture,tradeoff,root cause,plan,optimize,design,deep dive}")
    private String reasoningKeywordsRaw;

    @Value("${ai.routing.tool-keywords:project status,workload,assign,candidates,task list,project progress}")
    private String toolKeywordsRaw;

    public SmartRoutingService(
            @Qualifier("geminiFlashModel") StreamingChatModel geminiFlashModel,
            @Qualifier("gpt4oFallbackModel") StreamingChatModel gpt4oFallbackModel,
            @Qualifier("deepSeekReasoningModel") StreamingChatModel deepSeekReasoningModel,
            @Qualifier("groqOssReasoningModel") @Nullable StreamingChatModel groqOssReasoningModel,
            TokenEstimationUtil tokenEstimationUtil) {
        this.geminiFlashModel = geminiFlashModel;
        this.gpt4oFallbackModel = gpt4oFallbackModel;
        this.deepSeekReasoningModel = deepSeekReasoningModel;
        this.groqOssReasoningModel = groqOssReasoningModel;
        this.tokenEstimationUtil = tokenEstimationUtil;
    }

    public StreamingChatModel selectModel(String userMessage, String contextHistory) {
        int estimatedTokens = tokenEstimationUtil.estimateTotal(userMessage, contextHistory);
        log.debug("[SmartRouting] Estimated tokens: {}, threshold: {}", estimatedTokens, tokenThreshold);

        String normalized = (userMessage == null ? "" : userMessage).toLowerCase(Locale.ROOT);
        boolean asksForReasoning = containsAny(normalized, splitKeywords(reasoningKeywordsRaw));
        boolean likelyNeedsTools = containsAny(normalized, splitKeywords(toolKeywordsRaw));
        boolean heavyContext = estimatedTokens > tokenThreshold;

        if (heavyContext || asksForReasoning) {
            StreamingChatModel reasoningModel = getReasoningModel();
            log.info("[SmartRouting] Routing to REASONING model ({}) — tokens: {}, asksForReasoning: {}",
                    getModelName(reasoningModel), estimatedTokens, asksForReasoning);
            return reasoningModel;
        }

        if (likelyNeedsTools) {
            log.info("[SmartRouting] Routing to TOOL-FRIENDLY model ({}) — tokens: {}",
                    geminiModelName, estimatedTokens);
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

    public StreamingChatModel getReasoningModel() {
        if (groqEnabled && groqOssReasoningModel != null) {
            return groqOssReasoningModel;
        }
        return deepSeekReasoningModel;
    }

    public String getModelName(StreamingChatModel model) {
        if (model == gpt4oFallbackModel)
            return fallbackModelName;
        if (model == geminiFlashModel)
            return geminiModelName;
        if (model == deepSeekReasoningModel)
            return deepSeekReasoningModelName;
        if (model == groqOssReasoningModel)
            return groqReasoningModelName;
        return model.getClass().getSimpleName();
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }

        for (String keyword : keywords) {
            if (!keyword.isBlank() && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private List<String> splitKeywords(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isBlank())
                .toList();
    }
}