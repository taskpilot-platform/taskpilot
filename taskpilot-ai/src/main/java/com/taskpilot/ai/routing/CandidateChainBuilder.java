package com.taskpilot.ai.routing;

import com.taskpilot.ai.config.AiModelConfig;
import com.taskpilot.ai.service.SmartRoutingService;
import com.taskpilot.contracts.assignment.port.out.SystemSettingPort;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the ordered, filtered, deduplicated Copilot candidate chain for a turn.
 *
 * When DB setting {@code ai.model_priority} is absent or {@code {"models":[]}},
 * builds the default Groq-first chain:
 *   Groq GPT-OSS 120B -> verified Gemini in configured order -> verified free OpenRouter (if enabled).
 * When DB setting has entries, preserves the admin's configured order while dropping
 * retired, blocked, unverified-Gemini, paid-OpenRouter, non-tool-capable (when tools required),
 * unavailable, or duplicate entries.
 */
@Slf4j
public class CandidateChainBuilder {

    public record ProviderSlots(
            boolean groqEnabled,
            String groqReasoningModel,
            boolean useGeminiForTools,
            List<String> geminiModels,
            boolean openRouterEnabled,
            boolean openRouterAllowPaidModels,
            List<String> openRouterModels) {
    }

    private final SystemSettingPort systemSettingPort;
    private final AiModelConfig aiModelConfig;
    private final CopilotRoutingProperties properties;

    public CandidateChainBuilder(
            SystemSettingPort systemSettingPort,
            AiModelConfig aiModelConfig,
            CopilotRoutingProperties properties) {
        this.systemSettingPort = systemSettingPort;
        this.aiModelConfig = aiModelConfig;
        this.properties = properties != null ? properties : new CopilotRoutingProperties();
    }

    public List<ModelCandidate> build(boolean requiresTools, ProviderSlots slots) {
        List<RawEntry> rawEntries = readDbPriorityEntries();
        boolean fromDb = !rawEntries.isEmpty();
        ModelCandidate.Source source = fromDb ? ModelCandidate.Source.DB_PRIORITY : ModelCandidate.Source.DEFAULT_CHAIN;

        if (!fromDb) {
            rawEntries = buildDefaultSlots(slots, requiresTools);
        }

        List<ModelCandidate> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (RawEntry entry : rawEntries) {
            String provider = entry.provider == null ? "" : entry.provider.trim().toUpperCase(Locale.ROOT);
            String model = entry.model == null ? "" : entry.model.trim();
            String dropReason = dropReason(provider, model, fromDb, requiresTools, slots, seen);
            if (dropReason != null) {
                log.info("[CopilotChain] drop provider={} model={} reason={}", provider, model, dropReason);
                continue;
            }
            boolean toolCapable = isToolCapable(provider, model);
            ModelCandidate candidate = new ModelCandidate(provider, model, toolCapable, source);
            chain.add(candidate);
            seen.add(candidate.identity());
        }

        int maxAttempts = maxModelAttempts(chain);
        if (properties.getTurn().getMaxModelAttemptsCap() > 0 && properties.getTurn().getMaxModelAttemptsCap() < chain.size()) {
            List<String> cutOff = chain.subList(maxAttempts, chain.size()).stream()
                    .map(ModelCandidate::label)
                    .toList();
            log.warn("[CopilotChain] cap={} cuts off candidates={}", properties.getTurn().getMaxModelAttemptsCap(), cutOff);
        }

        log.info("[CopilotChain] chain source={} requiresTools={} maxModelAttempts={} models=[{}]",
                fromDb ? "DB" : "DEFAULT",
                requiresTools,
                maxAttempts,
                chain.stream().map(ModelCandidate::label).collect(Collectors.joining(", ")));

        return List.copyOf(chain);
    }

    public int maxModelAttempts(List<ModelCandidate> chain) {
        if (chain == null || chain.isEmpty()) {
            return 0;
        }
        int cap = properties.getTurn().getMaxModelAttemptsCap();
        return cap > 0 ? Math.min(cap, chain.size()) : chain.size();
    }

    public int deadlineSeconds() {
        return Math.max(1, properties.getTurn().getDeadlineSeconds());
    }

    private record RawEntry(String provider, String model) {}

    private List<RawEntry> readDbPriorityEntries() {
        if (systemSettingPort == null) {
            return List.of();
        }
        try {
            Optional<Map<String, Object>> priorityRaw = systemSettingPort.findJsonObjectByKey("ai.model_priority");
            if (priorityRaw.isEmpty() || priorityRaw.get().isEmpty()) {
                return List.of();
            }
            Object modelsObj = priorityRaw.get().get("models");
            if (!(modelsObj instanceof List<?> list) || list.isEmpty()) {
                return List.of();
            }
            List<RawEntry> entries = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String provider = map.containsKey("provider") && map.get("provider") != null
                            ? String.valueOf(map.get("provider")).trim() : "";
                    String model = map.containsKey("model") && map.get("model") != null
                            ? String.valueOf(map.get("model")).trim() : "";
                    if (!provider.isEmpty() && !model.isEmpty()) {
                        entries.add(new RawEntry(provider, model));
                    }
                }
            }
            return entries;
        } catch (Exception e) {
            log.warn("[CopilotChain] Failed to read ai.model_priority from DB settings: {}", e.getMessage());
            return List.of();
        }
    }

    private List<RawEntry> buildDefaultSlots(ProviderSlots slots, boolean requiresTools) {
        List<RawEntry> entries = new ArrayList<>();
        if (slots == null) {
            return entries;
        }
        // 1. Groq GPT-OSS 120B first
        if (slots.groqEnabled() && slots.groqReasoningModel() != null && !slots.groqReasoningModel().isBlank()) {
            entries.add(new RawEntry("GROQ", slots.groqReasoningModel().trim()));
        }
        // 2. All configured Gemini models in configured order
        if (!requiresTools || slots.useGeminiForTools()) {
            if (slots.geminiModels() != null) {
                for (String geminiModel : slots.geminiModels()) {
                    if (geminiModel != null && !geminiModel.isBlank()) {
                        entries.add(new RawEntry("GEMINI", geminiModel.trim()));
                    }
                }
            }
        }
        // 3. Free OpenRouter models if enabled
        if (slots.openRouterEnabled() && slots.openRouterModels() != null) {
            for (String openRouterModel : slots.openRouterModels()) {
                if (openRouterModel != null && !openRouterModel.isBlank()) {
                    entries.add(new RawEntry("OPENROUTER", openRouterModel.trim()));
                }
            }
        }
        return entries;
    }

    private String dropReason(
            String provider,
            String model,
            boolean fromDb,
            boolean requiresTools,
            ProviderSlots slots,
            Set<String> seen) {
        if (provider.isEmpty() || model.isEmpty()) {
            return "INVALID";
        }
        if (SmartRoutingService.isRetiredModel(model)) {
            return "RETIRED";
        }
        if (properties.isBlocked(provider, model)) {
            return "BLOCKED";
        }
        if ("OPENROUTER".equals(provider)
                && !model.toLowerCase(Locale.ROOT).endsWith(":free")
                && (slots == null || !slots.openRouterAllowPaidModels())
                && (!fromDb || properties.hasVerifiedRegistry())) {
            return "PAID_NOT_ENABLED";
        }
        if (properties.hasVerifiedRegistry()) {
            Optional<CopilotRoutingProperties.VerifiedModel> verified = properties.findVerified(provider, model);
            if ("GEMINI".equals(provider) && verified.isEmpty()) {
                return "GEMINI_UNVERIFIED";
            }
            if (!fromDb && "OPENROUTER".equals(provider) && verified.isEmpty()) {
                return "OPENROUTER_UNVERIFIED";
            }
            if (requiresTools && (verified.isEmpty() || !verified.get().isTools())) {
                return "NO_VERIFIED_TOOLS";
            }
        }
        if (!isProviderAvailable(provider, slots)) {
            return "PROVIDER_UNAVAILABLE";
        }
        String identity = ModelCandidate.identityOf(provider, model);
        if (seen.contains(identity)) {
            return "DUPLICATE";
        }
        return null;
    }

    private boolean isProviderAvailable(String provider, ProviderSlots slots) {
        if ("GROQ".equals(provider)) {
            if (slots != null && !slots.groqEnabled()) {
                return false;
            }
            return hasKeysOrUnreported("GROQ");
        }
        if ("OPENROUTER".equals(provider)) {
            if (slots != null && !slots.openRouterEnabled()) {
                return false;
            }
            return hasKeysOrUnreported("OPENROUTER");
        }
        if ("GEMINI".equals(provider)) {
            return hasKeysOrUnreported("GEMINI");
        }
        return false;
    }

    private boolean hasKeysOrUnreported(String provider) {
        if (aiModelConfig == null) {
            return true;
        }
        Optional<Boolean> configured = aiModelConfig.hasConfiguredKeys(provider);
        return configured.orElse(true);
    }

    private boolean isToolCapable(String provider, String model) {
        if (!properties.hasVerifiedRegistry()) {
            return true;
        }
        return properties.findVerified(provider, model)
                .map(CopilotRoutingProperties.VerifiedModel::isTools)
                .orElse(false);
    }
}
