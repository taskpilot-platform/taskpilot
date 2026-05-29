package com.taskpilot.ai.service;

import com.taskpilot.ai.gatekeeper.GatekeeperService;
import com.taskpilot.ai.util.TokenEstimationUtil;
import dev.langchain4j.model.chat.StreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class SmartRoutingService {

    // Quality-first primary, then responsiveness-first Gemini fallbacks.
    private final StreamingChatModel geminiPrimaryModel;
    private final StreamingChatModel geminiFallback1Model;
    private final StreamingChatModel geminiFallback2Model;
    private final StreamingChatModel geminiFallback3Model;
    private final StreamingChatModel geminiFallback4Model;

    private final StreamingChatModel gpt4oFallbackModel;
    private final StreamingChatModel deepSeekReasoningModel;
    private final StreamingChatModel groqOssReasoningModel;

    private final StreamingChatModel gpt4oFallbackTextModel;
    private final StreamingChatModel deepSeekReasoningTextModel;
    private final StreamingChatModel groqOssReasoningTextModel;

    private final TokenEstimationUtil tokenEstimationUtil;
    private final GatekeeperService gatekeeperService;

    @Value("${ai.gemini.model-name:gemini-3.5-flash}")
    private String geminiModelName;

    @Value("${ai.gemini.fallback1-model:gemini-3.1-flash-lite}")
    private String geminiFallback1ModelName;

    @Value("${ai.gemini.fallback2-model:gemini-2.5-flash-lite}")
    private String geminiFallback2ModelName;

    @Value("${ai.gemini.fallback3-model:gemini-2.5-flash}")
    private String geminiFallback3ModelName;

    @Value("${ai.gemini.fallback4-model:gemini-3-flash}")
    private String geminiFallback4ModelName;

    @Value("${ai.gemini.waterfall-max-attempts:3}")
    private int geminiWaterfallMaxAttempts;

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

    @Value("${ai.routing.tool-keywords:project status,workload,assign,candidates,task list,project progress,confirm,confirm_action,xac nhan}")
    private String toolKeywordsRaw;

    @Value("${ai.routing.ahp-fallback-keywords:phan cong,giao viec,chia viec,chon ai,chon nguoi,tim nguoi,ai ranh,ung vien,assign,candidate,workload,chia task}")
    private String ahpFallbackKeywordsRaw;

    public SmartRoutingService(
            @Qualifier("geminiFlashModel") StreamingChatModel geminiPrimaryModel,
            @Qualifier("geminiFallback1Model") StreamingChatModel geminiFallback1Model,
            @Qualifier("geminiFallback2Model") StreamingChatModel geminiFallback2Model,
            @Qualifier("geminiFallback3Model") StreamingChatModel geminiFallback3Model,
            @Qualifier("geminiFallback4Model") StreamingChatModel geminiFallback4Model,
            @Qualifier("gpt4oFallbackModel") StreamingChatModel gpt4oFallbackModel,
            @Qualifier("deepSeekReasoningModel") StreamingChatModel deepSeekReasoningModel,
            @Qualifier("groqOssReasoningModel") @Nullable StreamingChatModel groqOssReasoningModel,
            @Qualifier("gpt4oFallbackTextModel") StreamingChatModel gpt4oFallbackTextModel,
            @Qualifier("deepSeekReasoningTextModel") StreamingChatModel deepSeekReasoningTextModel,
            @Qualifier("groqOssReasoningTextModel") @Nullable StreamingChatModel groqOssReasoningTextModel,
            TokenEstimationUtil tokenEstimationUtil,
            GatekeeperService gatekeeperService) {
        this.geminiPrimaryModel = geminiPrimaryModel;
        this.geminiFallback1Model = geminiFallback1Model;
        this.geminiFallback2Model = geminiFallback2Model;
        this.geminiFallback3Model = geminiFallback3Model;
        this.geminiFallback4Model = geminiFallback4Model;
        this.gpt4oFallbackModel = gpt4oFallbackModel;
        this.deepSeekReasoningModel = deepSeekReasoningModel;
        this.groqOssReasoningModel = groqOssReasoningModel;
        this.gpt4oFallbackTextModel = gpt4oFallbackTextModel;
        this.deepSeekReasoningTextModel = deepSeekReasoningTextModel;
        this.groqOssReasoningTextModel = groqOssReasoningTextModel;
        this.tokenEstimationUtil = tokenEstimationUtil;
        this.gatekeeperService = gatekeeperService;
    }

    public record RoutingDecision(StreamingChatModel model, String modelName, boolean requiresAHP) {
    }

    public RoutingDecision route(String userMessage) {
        return route(userMessage, null);
    }

    public RoutingDecision route(String userMessage, String contextHistory) {
        String normalized = normalize(userMessage);
        boolean directAssignmentExecution = isDirectAssignmentExecution(normalized);
        boolean pendingActionConfirmation = isPendingActionConfirmation(normalized);
        boolean requiresAHP = directAssignmentExecution || pendingActionConfirmation ? false : resolveRequiresAHP(userMessage);

        int estimatedTokens = tokenEstimationUtil.estimateTotal(userMessage, contextHistory);
        log.debug("[SmartRouting] Estimated tokens: {}, threshold: {}", estimatedTokens, tokenThreshold);

        boolean likelyNeedsTools = pendingActionConfirmation || directAssignmentExecution
                || containsAny(normalized, splitKeywords(toolKeywordsRaw));
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
            log.info("[SmartRouting] Routing to REASONING model ({}) - tokens: {}", name, estimatedTokens);
            return new RoutingDecision(reasoningModel, name, false);
        }

        if (likelyNeedsTools) {
            log.info("[SmartRouting] Routing to TOOL-FRIENDLY model ({}) - tokens: {}",
                    geminiModelName, estimatedTokens);
            return new RoutingDecision(geminiPrimaryModel, geminiModelName, false);
        }

        log.info("[SmartRouting] Routing to LIGHT model ({}) - tokens: {}", fallbackModelName, estimatedTokens);
        return new RoutingDecision(gpt4oFallbackModel, fallbackModelName, false);
    }

    private boolean isDirectAssignmentExecution(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }

        boolean hasAssignmentIntent = containsAny(normalized, List.of(
                "phan cong", "giao task", "giao viec", "gan task", "gan viec", "gan luon",
                "chia viec", "assign", "assignment", "assignee"));
        boolean hasExecutionIntent = containsAny(normalized, List.of(
                "thuc hien", "tien hanh", "ap dung", "lam di", "cap nhat", "xong gan",
                "gan luon", "assign it", "immediately", "execute", "apply", "do it"));

        return hasAssignmentIntent && hasExecutionIntent;
    }

    private boolean isPendingActionConfirmation(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        return normalized.contains("confirm_action")
                || (normalized.matches(".*\\b[a-f0-9-]{24,}\\b.*")
                        && containsAny(normalized, List.of(
                                "confirm", "confirmed", "xac nhan", "dong y", "thuc hien", "apply")));
    }

    /**
     * Quality-first, then response-first. By default, only the primary and two
     * fast Gemini fallbacks are attempted before switching to the external model.
     */
    public StreamingChatModel getNextGeminiFallback(StreamingChatModel currentModel) {
        if (currentModel == geminiPrimaryModel) {
            if (maxGeminiAttempts() <= 1) {
                return externalFallbackAfter("primary");
            }
            log.info("[SmartRouting] Gemini waterfall: {} -> {}", geminiModelName, geminiFallback1ModelName);
            return geminiFallback1Model;
        }
        if (currentModel == geminiFallback1Model) {
            if (maxGeminiAttempts() <= 2) {
                return externalFallbackAfter(geminiFallback1ModelName);
            }
            log.info("[SmartRouting] Gemini waterfall: {} -> {}", geminiFallback1ModelName, geminiFallback2ModelName);
            return geminiFallback2Model;
        }
        if (currentModel == geminiFallback2Model) {
            if (maxGeminiAttempts() <= 3) {
                return externalFallbackAfter(geminiFallback2ModelName);
            }
            log.info("[SmartRouting] Gemini waterfall: {} -> {}", geminiFallback2ModelName, geminiFallback3ModelName);
            return geminiFallback3Model;
        }
        if (currentModel == geminiFallback3Model) {
            if (maxGeminiAttempts() <= 4) {
                return externalFallbackAfter(geminiFallback3ModelName);
            }
            log.info("[SmartRouting] Gemini waterfall: {} -> {}", geminiFallback3ModelName, geminiFallback4ModelName);
            return geminiFallback4Model;
        }
        return externalFallbackAfter(geminiFallback4ModelName);
    }

    private int maxGeminiAttempts() {
        return Math.max(1, Math.min(5, geminiWaterfallMaxAttempts));
    }

    private StreamingChatModel externalFallbackAfter(String previousModelName) {
        log.info("[SmartRouting] Gemini waterfall stopping after {} -> external fallback: {}",
                previousModelName, fallbackModelName);
        return gpt4oFallbackModel;
    }

    public boolean isGeminiModel(StreamingChatModel model) {
        return model == geminiPrimaryModel
                || model == geminiFallback1Model
                || model == geminiFallback2Model
                || model == geminiFallback3Model
                || model == geminiFallback4Model;
    }

    public StreamingChatModel getFallbackModel() {
        return gpt4oFallbackModel;
    }

    public StreamingChatModel getPrimaryModel() {
        return geminiPrimaryModel;
    }

    public StreamingChatModel getReasoningModel() {
        if (groqEnabled && groqOssReasoningModel != null) {
            return groqOssReasoningModel;
        }
        return deepSeekReasoningModel;
    }

    public StreamingChatModel getReasoningTextModel() {
        if (groqEnabled && groqOssReasoningTextModel != null) {
            return groqOssReasoningTextModel;
        }
        return deepSeekReasoningTextModel;
    }

    public StreamingChatModel getFallbackTextModel() {
        return gpt4oFallbackTextModel;
    }

    public StreamingChatModel getTextModel(String modelName) {
        if (modelName == null) {
            return getPrimaryModel();
        }
        if (modelName.equals(fallbackModelName)) {
            return getFallbackTextModel();
        }
        if (modelName.equals(deepSeekReasoningModelName) || modelName.equals(groqReasoningModelName)) {
            return getReasoningTextModel();
        }
        return getPrimaryModel();
    }

    public String getModelName(StreamingChatModel model) {
        if (model == geminiPrimaryModel) return geminiModelName;
        if (model == geminiFallback1Model) return geminiFallback1ModelName;
        if (model == geminiFallback2Model) return geminiFallback2ModelName;
        if (model == geminiFallback3Model) return geminiFallback3ModelName;
        if (model == geminiFallback4Model) return geminiFallback4ModelName;
        if (model == gpt4oFallbackModel || model == gpt4oFallbackTextModel) return fallbackModelName;
        if (model == deepSeekReasoningModel || model == deepSeekReasoningTextModel) return deepSeekReasoningModelName;
        if (model == groqOssReasoningModel || model == groqOssReasoningTextModel) return groqReasoningModelName;
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
        if (text == null) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT)
                .replace('\u0111', 'd')
                .replace('\u0110', 'd');
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
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
                .map(s -> normalize(s.trim()))
                .filter(s -> !s.isBlank())
                .toList();
    }
}
