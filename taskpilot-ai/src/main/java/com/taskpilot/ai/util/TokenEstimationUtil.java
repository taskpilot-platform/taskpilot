package com.taskpilot.ai.util;
import org.springframework.stereotype.Component;
@Component
public class TokenEstimationUtil {
    private static final int CHARS_PER_TOKEN = 4;
    public int estimate(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(1, text.length() / CHARS_PER_TOKEN);
    }
    public int estimateTotal(String... messages) {
        int total = 0;
        for (String msg : messages) {
            total += estimate(msg);
        }
        return total;
    }
}

