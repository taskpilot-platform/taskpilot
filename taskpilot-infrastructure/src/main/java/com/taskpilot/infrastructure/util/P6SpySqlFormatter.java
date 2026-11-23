package com.taskpilot.infrastructure.util;

import com.p6spy.engine.spy.appender.MessageFormattingStrategy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

/**
 * Redacted P6Spy formatter: emits timing and operation metadata only.
 * Never logs bound SQL parameter values, raw SQL bodies, or JDBC URLs.
 */
@Component
public class P6SpySqlFormatter implements MessageFormattingStrategy {

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private static final Set<String> SAFE_SQL_VERBS = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "WITH",
            "CREATE", "ALTER", "DROP", "BEGIN", "COMMIT", "ROLLBACK"
    );

    @Override
    public String formatMessage(
            int connectionId,
            String now,
            long elapsed,
            String category,
            String prepared,
            String sql,
            String url) {
        if ((sql == null || sql.isBlank()) && (prepared == null || prepared.isBlank())) {
            return "";
        }
        String op = extractSafeVerb(prepared, sql);
        String safeCategory = category != null ? category : "unknown";
        return String.format("[%s] | %d ms | %s | connection %d | op=%s",
                safeCategory, elapsed, TS_FORMAT.format(Instant.now()), connectionId, op);
    }

    private static String extractSafeVerb(String prepared, String sql) {
        String source = (prepared != null && !prepared.isBlank()) ? prepared : sql;
        if (source == null || source.isBlank()) {
            return "NONE";
        }
        String trimmed = source.trim();
        int end = 0;
        while (end < trimmed.length() && Character.isLetter(trimmed.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return "OTHER";
        }
        String verb = trimmed.substring(0, end).toUpperCase(Locale.ROOT);
        return SAFE_SQL_VERBS.contains(verb) ? verb : "OTHER";
    }
}
