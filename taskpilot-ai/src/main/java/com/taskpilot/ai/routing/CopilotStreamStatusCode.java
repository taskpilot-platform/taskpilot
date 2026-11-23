package com.taskpilot.ai.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Locale-neutral status codes emitted by the Copilot backend over SSE / status API
 * and localized by the frontend via vi.json / en.json.
 */
public enum CopilotStreamStatusCode {

    COPILOT_MODELS_EXHAUSTED,
    COPILOT_TURN_DEADLINE_EXCEEDED,
    COPILOT_MAX_ATTEMPTS_REACHED,
    COPILOT_NO_AVAILABLE_MODEL,
    COPILOT_CONFIRMATION_EXPLANATION_INCOMPLETE;

    private static final ObjectMapper COMPACT_MAPPER = new ObjectMapper();

    public String code() {
        return name();
    }

    public String toJsonPayload(Map<String, Object> params) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", name());
        if (params != null && !params.isEmpty()) {
            payload.put("params", params);
        }
        try {
            return COMPACT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"code\":\"" + name() + "\"}";
        }
    }

    public String toJsonPayload() {
        return toJsonPayload(Map.of());
    }
}
