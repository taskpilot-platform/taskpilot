package com.taskpilot.ai.service;

import com.taskpilot.ai.gatekeeper.GatekeeperService;
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
    private final GatekeeperService gatekeeperService;

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

    @Value("${ai.routing.tool-keywords:project status,workload,assign,candidates,task list,project progress}")
    private String toolKeywordsRaw;

    @Value("${ai.routing.ahp-fallback-keywords:phân công,phan cong,giao việc,giao viec,chia việc,chia viec,chọn ai,chon ai,chọn người,chon nguoi,tìm người,tim nguoi,ai rảnh,ai ranh,ứng viên,ung vien,assign,candidate,workload,chia task}")
    private String ahpFallbackKeywordsRaw;

    public SmartRoutingService(
            @Qualifier("geminiFlashModel") StreamingChatModel geminiFlashModel,
            @Qualifier("gpt4oFallbackModel") StreamingChatModel gpt4oFallbackModel,
            @Qualifier("deepSeekReasoningModel") StreamingChatModel deepSeekReasoningModel,
            @Qualifier("groqOssReasoningModel") @Nullable StreamingChatModel groqOssReasoningModel,
            TokenEstimationUtil tokenEstimationUtil,
            GatekeeperService gatekeeperService) {
        this.geminiFlashModel = geminiFlashModel;
        this.gpt4oFallbackModel = gpt4oFallbackModel;
        this.deepSeekReasoningModel = deepSeekReasoningModel;
        this.groqOssReasoningModel = groqOssReasoningModel;
        this.tokenEstimationUtil = tokenEstimationUtil;
        this.gatekeeperService = gatekeeperService;
    }

    public record RoutingDecision(StreamingChatModel model, String modelName, boolean requiresAHP) {
    }

    public RoutingDecision route(String userMessage) {
        return route(userMessage, null);
    }

    public RoutingDecision route(String userMessage, String contextHistory) {
        boolean requiresAHP = resolveRequiresAHP(userMessage);

        int estimatedTokens = tokenEstimationUtil.estimateTotal(userMessage, contextHistory);
        log.debug("[SmartRouting] Estimated tokens: {}, threshold: {}", estimatedTokens, tokenThreshold);

        String normalized = normalize(userMessage);
        boolean likelyNeedsTools = containsAny(normalized, splitKeywords(toolKeywordsRaw));
        boolean heavyContext = estimatedTokens > tokenThreshold;

        if (requiresAHP) {
            StreamingChatModel reasoningModel = getReasoningModel();
            String name = getModelName(reasoningModel);
            log.info("[SmartRouting] Gatekeeper requires AHP -> REASONING model ({})", name);
            return new RoutingDecision(reasoningModel, name, true);
        }

        if (heavyContext) {
            StreamingChatModel reasoningModel = getReasoningModel();
            String name = getModelName(reasoningModel);
            log.info("[SmartRouting] Routing to REASONING model ({}) — tokens: {}",
                    name, estimatedTokens);
            return new RoutingDecision(reasoningModel, name, false);
        }

        if (likelyNeedsTools) {
            log.info("[SmartRouting] Routing to TOOL-FRIENDLY model ({}) — tokens: {}",
                    geminiModelName, estimatedTokens);
            return new RoutingDecision(geminiFlashModel, geminiModelName, false);
        }

        log.info("[SmartRouting] Routing to LIGHT model ({}) — tokens: {}", fallbackModelName, estimatedTokens);
        return new RoutingDecision(gpt4oFallbackModel, fallbackModelName, false);
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

    private boolean resolveRequiresAHP(String userMessage) {
        try {
            boolean requiresAHP = gatekeeperService.requiresAHP(userMessage);
            log.info("[SmartRouting] Gatekeeper requiresAHP={}", requiresAHP);
            return requiresAHP;
        } catch (Exception ex) {
            log.warn("[SmartRouting] Gatekeeper failed; using keyword fallback: {}", ex.getMessage());
            boolean requiresAHP = containsAny(normalize(userMessage), splitKeywords(ahpFallbackKeywordsRaw));
            log.info("[SmartRouting] Keyword fallback requiresAHP={}", requiresAHP);
            return requiresAHP;
        }
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
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