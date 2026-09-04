package com.taskpilot.ai.streaming.sse;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Utility for formatting event payloads for SSE streaming.
 */
@Component
public class AiStreamEventFormatter {

    public Map<String, Object> token(String text) {
        return Map.of("token", text != null ? text : "");
    }

    public Map<String, Object> thinking(String chunk) {
        return Map.of("thinking", chunk != null ? chunk : "");
    }

    public Map<String, Object> thought(String thoughtText) {
        return Map.of("thought", thoughtText != null ? thoughtText : "");
    }

    public Map<String, Object> status(String statusText) {
        return Map.of("status", statusText != null ? statusText : "");
    }

    public Map<String, Object> error(String errorMessage) {
        return Map.of("error", errorMessage != null ? errorMessage : "Unknown error");
    }
}
