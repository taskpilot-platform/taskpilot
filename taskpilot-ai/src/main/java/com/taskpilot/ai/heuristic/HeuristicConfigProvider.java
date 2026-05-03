package com.taskpilot.ai.heuristic;

import com.taskpilot.contracts.assignment.port.out.SystemSettingPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class HeuristicConfigProvider {

    private static final String WEIGHTS_KEY = "heuristic.weights";
    private static final String NORMALIZATION_KEY = "heuristic.normalization";
    private static final String CURRENT_MODE_KEY = "heuristic.current_mode";
    private static final String DEFAULT_MODE = "BALANCED";

    private final SystemSettingPort systemSettingPort;

    @Value("${ai.heuristic.cache-ttl-seconds:60}")
    private int cacheTtlSeconds;

    private volatile Instant cacheLoadedAt = Instant.EPOCH;
    private volatile Map<String, HeuristicConfig> cache = Map.of();

    public HeuristicConfig getConfig(String mode) {
        refreshCacheIfNeeded();

        HeuristicConfig config = cache.get(mode.toUpperCase(Locale.ROOT));
        if (config == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    "Missing heuristic config for mode: " + mode);
        }
        return config;
    }

    public String getCurrentMode() {
        Map<String, Object> raw = systemSettingPort.findJsonObjectByKey(CURRENT_MODE_KEY)
                .orElse(Map.of());

        if (raw.isEmpty()) {
            return DEFAULT_MODE;
        }

        Object value = raw.getOrDefault("mode", raw.get("current_mode"));
        if (value == null) {
            value = raw.get("value");
        }

        if (value == null) {
            return DEFAULT_MODE;
        }

        String normalized = String.valueOf(value).trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "URGENT", "TRAINING", "BALANCED" -> normalized;
            default -> DEFAULT_MODE;
        };
    }

    private synchronized void refreshCacheIfNeeded() {
        Duration age = Duration.between(cacheLoadedAt, Instant.now());
        if (!cache.isEmpty() && age.toSeconds() < cacheTtlSeconds) {
            return;
        }

        Map<String, Object> weightsRaw = systemSettingPort.findJsonObjectByKey(WEIGHTS_KEY)
                .orElseThrow(() -> new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "Missing system setting: " + WEIGHTS_KEY));

        Map<String, Object> normalizationRaw = systemSettingPort.findJsonObjectByKey(NORMALIZATION_KEY)
                .orElse(null);

        Map<String, HeuristicWeights> weightsByMode = parseWeights(weightsRaw);
        Map<String, HeuristicNormalizationConfig> normalizationByMode = (normalizationRaw == null
                || normalizationRaw.isEmpty())
                        ? defaultNormalizationByMode()
                        : parseNormalization(normalizationRaw);

        if (normalizationRaw == null || normalizationRaw.isEmpty()) {
            log.warn("[Heuristic] Missing {} config. Falling back to BENCHMARK_BENEFIT for all modes.",
                    NORMALIZATION_KEY);
        }

        Map<String, HeuristicConfig> updated = new HashMap<>();
        for (Map.Entry<String, HeuristicWeights> entry : weightsByMode.entrySet()) {
            String mode = entry.getKey();
            HeuristicNormalizationConfig normalization = normalizationByMode.get(mode);
            if (normalization == null) {
                throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "Missing heuristic normalization for mode: " + mode);
            }
            updated.put(mode, new HeuristicConfig(entry.getValue(), normalization));
        }

        cache = Map.copyOf(updated);
        cacheLoadedAt = Instant.now();
    }

    @SuppressWarnings("unchecked")
    private Map<String, HeuristicWeights> parseWeights(Map<String, Object> raw) {
        Map<String, HeuristicWeights> byMode = new HashMap<>();

        if (raw.containsKey("BALANCED") || raw.containsKey("URGENT") || raw.containsKey("TRAINING")) {
            for (String mode : new String[] { "BALANCED", "URGENT", "TRAINING" }) {
                Object modeNode = raw.get(mode);
                if (modeNode instanceof Map<?, ?> modeMapRaw) {
                    Map<String, Object> modeMap = (Map<String, Object>) modeMapRaw;
                    byMode.put(mode, parseWeightsMap(modeMap, mode));
                }
            }
        } else {
            HeuristicWeights weights = parseWeightsMap(raw, "root");
            byMode.put("BALANCED", weights);
            byMode.put("URGENT", weights);
            byMode.put("TRAINING", weights);
        }

        if (byMode.isEmpty()) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    "No heuristic weights configured");
        }

        return byMode;
    }

    private Map<String, HeuristicNormalizationConfig> defaultNormalizationByMode() {
        Map<String, HeuristicNormalizationConfig> defaults = new HashMap<>();
        HeuristicNormalizationConfig config = new HeuristicNormalizationConfig(
                HeuristicNormalization.BENCHMARK_BENEFIT,
                HeuristicNormalization.BENCHMARK_BENEFIT,
                HeuristicNormalization.BENCHMARK_BENEFIT);
        defaults.put("BALANCED", config);
        defaults.put("URGENT", config);
        defaults.put("TRAINING", config);
        return defaults;
    }

    @SuppressWarnings("unchecked")
    private Map<String, HeuristicNormalizationConfig> parseNormalization(Map<String, Object> raw) {
        Map<String, HeuristicNormalizationConfig> byMode = new HashMap<>();

        for (String mode : new String[] { "BALANCED", "URGENT", "TRAINING" }) {
            Object modeNode = raw.get(mode);
            if (!(modeNode instanceof Map<?, ?> modeMapRaw)) {
                continue;
            }

            Map<String, Object> modeMap = (Map<String, Object>) modeMapRaw;
            HeuristicNormalization fit = HeuristicNormalization.fromString(valueAsString(modeMap.get("fit")),
                    mode + ".fit");
            HeuristicNormalization load = HeuristicNormalization.fromString(
                    valueAsString(modeMap.getOrDefault("load", "BENCHMARK_BENEFIT")),
                    mode + ".load");
            HeuristicNormalization perf = HeuristicNormalization.fromString(
                    valueAsString(modeMap.getOrDefault("perf", "BENCHMARK_BENEFIT")),
                    mode + ".perf");

            byMode.put(mode, new HeuristicNormalizationConfig(fit, load, perf));
        }

        return byMode;
    }

    private HeuristicWeights parseWeightsMap(Map<String, Object> map, String contextName) {
        double fit = requiredDouble(firstPresent(map, "fit", "skill", "w_fit"),
                contextName + ".fit|skill|w_fit");
        double load = requiredDouble(firstPresent(map, "load", "workload", "w_load"),
                contextName + ".load|workload|w_load");
        double perf = requiredDouble(firstPresent(map, "perf", "performance", "availability", "w_perf"),
                contextName + ".perf|performance|availability|w_perf");

        return normalizeWeights(fit, load, perf, contextName);
    }

    private HeuristicWeights normalizeWeights(double fit, double load, double perf, String contextName) {
        if (fit < 0 || load < 0 || perf < 0) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    "Invalid negative heuristic weights in " + contextName);
        }

        double total = fit + load + perf;
        if (total <= 0) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    "Invalid heuristic weight sum in " + contextName + ": " + total);
        }

        return new HeuristicWeights(fit / total, load / total, perf / total);
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private double requiredDouble(Object value, String field) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Missing/invalid numeric heuristic field: " + field);
    }

    private String valueAsString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
