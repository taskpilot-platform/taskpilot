package com.taskpilot.ai.routing;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies model streaming failures into actionable categories, extracts Retry-After
 * hints without sleeping, and distinguishes per-model vs per-project 429 quota scopes
 * only when explicit provider metadata is present.
 */
public final class ModelFailureClassifier {

    private static final Pattern GEMINI_RETRY_DELAY_JSON = Pattern.compile(
            "\"retryDelay\"\\s*:\\s*\"([0-9]+(?:\\.[0-9]+)?)s\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern GEMINI_RETRY_IN_TEXT = Pattern.compile(
            "retry\\s+in\\s+([0-9]+(?:\\.[0-9]+)?)s", Pattern.CASE_INSENSITIVE);
    private static final Pattern GROQ_TRY_AGAIN_IN = Pattern.compile(
            "try\\s+again\\s+in\\s+(?:(\\d+)m)?([0-9]+(?:\\.[0-9]+)?)s", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTA_ID_JSON = Pattern.compile(
            "\"quotaId\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_LIKE_PATTERN = Pattern.compile(
            "(?:sk-[A-Za-z0-9_\\-]{8,}|gsk_[A-Za-z0-9_\\-]{8,}|AIza[A-Za-z0-9_\\-]{8,}|Bearer\\s+[A-Za-z0-9._\\-]{8,})");

    public enum Kind {
        CLIENT_ABORT,
        NON_RETRYABLE,
        RATE_LIMITED,
        KEYS_EXHAUSTED,
        FIRST_RESPONSE_TIMEOUT,
        TRANSIENT
    }

    public enum QuotaScope {
        MODEL,
        PROJECT,
        ACCOUNT_DAILY
    }

    public record Failure(
            Kind kind,
            Optional<Instant> notBefore,
            QuotaScope scope,
            String summary) {
    }

    private ModelFailureClassifier() {}

    public static boolean isNonRetryableModelError(Throwable error) {
        Throwable current = error;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && seen.add(current)) {
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("404") || lower.contains("does not exist")
                        || lower.contains("not found") || lower.contains("model_not_found")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    public static boolean isRateLimitError(Throwable error) {
        Throwable current = error;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && seen.add(current)) {
            String className = current.getClass().getName();
            if (className.endsWith("RateLimitException")) {
                return true;
            }
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("429") || lower.contains("rate limit")
                        || lower.contains("rate_limit") || lower.contains("resource_exhausted")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    public static boolean isKeysExhaustedError(Throwable error) {
        Throwable current = error;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && seen.add(current)) {
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("all groq keys are temporarily exhausted")
                        || lower.contains("all openrouter keys are temporarily exhausted")
                        || lower.contains("no api keys are configured")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    public static Failure classify(Throwable error, boolean firstResponseTimeout, boolean clientAbort, Instant now) {
        Instant referenceTime = now != null ? now : Instant.now();
        if (clientAbort) {
            return new Failure(Kind.CLIENT_ABORT, Optional.empty(), QuotaScope.MODEL, sanitizeSummary(error));
        }
        if (firstResponseTimeout) {
            return new Failure(Kind.FIRST_RESPONSE_TIMEOUT, Optional.empty(), QuotaScope.MODEL,
                    error != null ? sanitizeSummary(error) : "first_response_timeout");
        }
        if (isNonRetryableModelError(error)) {
            return new Failure(Kind.NON_RETRYABLE, Optional.empty(), QuotaScope.MODEL, sanitizeSummary(error));
        }
        if (isKeysExhaustedError(error)) {
            return new Failure(Kind.KEYS_EXHAUSTED, Optional.empty(), QuotaScope.MODEL, sanitizeSummary(error));
        }
        if (isRateLimitError(error)) {
            String metadataText = collectProviderMetadataText(error);
            Optional<Instant> notBefore = extractNotBefore(error, metadataText, referenceTime);
            QuotaScope scope = detectQuotaScope(metadataText);
            return new Failure(Kind.RATE_LIMITED, notBefore, scope, sanitizeSummary(error));
        }
        return new Failure(Kind.TRANSIENT, Optional.empty(), QuotaScope.MODEL, sanitizeSummary(error));
    }

    public static Failure classify(Throwable error, boolean firstResponseTimeout, boolean clientAbort) {
        return classify(error, firstResponseTimeout, clientAbort, Instant.now());
    }

    /**
     * Distinguishes per-model vs per-project 429 strictly from explicit provider metadata.
     * Never infers project-wide exhaustion from a bare HTTP 429 status code alone.
     */
    static QuotaScope detectQuotaScope(String metadataText) {
        if (metadataText == null || metadataText.isBlank()) {
            return QuotaScope.MODEL;
        }
        String lower = metadataText.toLowerCase(Locale.ROOT);
        if (lower.contains("free-models-per-day") || lower.contains("free model requests per day")) {
            return QuotaScope.ACCOUNT_DAILY;
        }

        Matcher quotaMatcher = QUOTA_ID_JSON.matcher(metadataText);
        boolean foundProjectOnlyQuota = false;
        while (quotaMatcher.find()) {
            String quotaId = quotaMatcher.group(1);
            String qLower = quotaId.toLowerCase(Locale.ROOT);
            if (qLower.contains("permodel")) {
                return QuotaScope.MODEL;
            }
            if (qLower.contains("perproject")) {
                foundProjectOnlyQuota = true;
            }
        }
        if (foundProjectOnlyQuota) {
            return QuotaScope.PROJECT;
        }

        // Fallback check if quotaId appears in plain text (non-JSON message)
        if (lower.contains("perproject") && !lower.contains("permodel")) {
            return QuotaScope.PROJECT;
        }
        return QuotaScope.MODEL;
    }

    static Optional<Instant> extractNotBefore(Throwable error, String metadataText, Instant now) {
        // 1. Check OpenAIServiceException headers() if available
        Optional<Instant> fromHeader = extractFromHeaders(error, now);
        if (fromHeader.isPresent()) {
            return fromHeader;
        }
        if (metadataText == null || metadataText.isBlank()) {
            return Optional.empty();
        }
        // 2. Gemini JSON retryDelay: "35s"
        Matcher retryDelay = GEMINI_RETRY_DELAY_JSON.matcher(metadataText);
        if (retryDelay.find()) {
            return parseSecondsOffset(retryDelay.group(1), now);
        }
        // 3. Gemini text "retry in 35.2s"
        Matcher retryIn = GEMINI_RETRY_IN_TEXT.matcher(metadataText);
        if (retryIn.find()) {
            return parseSecondsOffset(retryIn.group(1), now);
        }
        // 4. Groq text "try again in 1m2.5s" or "try again in 12.4s"
        Matcher groqMatcher = GROQ_TRY_AGAIN_IN.matcher(metadataText);
        if (groqMatcher.find()) {
            long mins = groqMatcher.group(1) != null ? Long.parseLong(groqMatcher.group(1)) : 0L;
            double secs = Double.parseDouble(groqMatcher.group(2));
            long millis = mins * 60_000L + Math.round(secs * 1000.0);
            if (millis > 0) {
                return Optional.of(now.plusMillis(millis));
            }
        }
        return Optional.empty();
    }

    private static Optional<Instant> extractFromHeaders(Throwable error, Instant now) {
        Throwable current = error;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && seen.add(current)) {
            try {
                Method headersMethod = current.getClass().getMethod("headers");
                Object headers = headersMethod.invoke(current);
                if (headers != null) {
                    Optional<String> retryAfterMs = readHeaderValue(headers, "retry-after-ms");
                    if (retryAfterMs.isPresent()) {
                        try {
                            long ms = Math.round(Double.parseDouble(retryAfterMs.get().trim()));
                            if (ms > 0) return Optional.of(now.plusMillis(ms));
                        } catch (NumberFormatException ignored) {}
                    }
                    Optional<String> retryAfter = readHeaderValue(headers, "retry-after");
                    if (retryAfter.isPresent()) {
                        Optional<Instant> parsed = parseRetryAfterHeader(retryAfter.get().trim(), now);
                        if (parsed.isPresent()) return parsed;
                    }
                }
            } catch (Exception ignored) {
                // Not an OpenAIServiceException or no headers() accessor
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Optional<String> readHeaderValue(Object headers, String headerName) {
        try {
            Method valuesMethod = headers.getClass().getMethod("values", String.class);
            Object result = valuesMethod.invoke(headers, headerName);
            if (result instanceof List<?> list && !list.isEmpty()) {
                return Optional.ofNullable(list.get(0)).map(Object::toString);
            }
        } catch (Exception ignored) {}
        try {
            Method namesMethod = headers.getClass().getMethod("names");
            Object namesObj = namesMethod.invoke(headers);
            if (namesObj instanceof Iterable<?> names) {
                for (Object n : names) {
                    if (n != null && headerName.equalsIgnoreCase(n.toString())) {
                        Method valuesMethod = headers.getClass().getMethod("values", String.class);
                        Object result = valuesMethod.invoke(headers, n.toString());
                        if (result instanceof List<?> list && !list.isEmpty()) {
                            return Optional.ofNullable(list.get(0)).map(Object::toString);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    static Optional<Instant> parseRetryAfterHeader(String raw, Instant now) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            double secs = Double.parseDouble(raw);
            if (secs > 0) {
                return Optional.of(now.plusMillis(Math.round(secs * 1000.0)));
            }
        } catch (NumberFormatException ignored) {}
        try {
            Instant target = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            if (target.isAfter(now)) {
                return Optional.of(target);
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    private static Optional<Instant> parseSecondsOffset(String rawSeconds, Instant now) {
        try {
            double secs = Double.parseDouble(rawSeconds);
            if (secs > 0) {
                return Optional.of(now.plus(Duration.ofMillis(Math.round(secs * 1000.0))));
            }
        } catch (NumberFormatException ignored) {}
        return Optional.empty();
    }

    private static String collectProviderMetadataText(Throwable error) {
        StringBuilder sb = new StringBuilder();
        Throwable current = error;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && seen.add(current)) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append('\n');
            }
            try {
                Method bodyMethod = current.getClass().getMethod("body");
                Object body = bodyMethod.invoke(current);
                if (body != null) {
                    sb.append(body).append('\n');
                }
            } catch (Exception ignored) {}
            current = current.getCause();
        }
        return sb.toString();
    }

    static String sanitizeSummary(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        Throwable cur = error;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (cur.getCause() != null && seen.add(cur)
                && (cur instanceof java.util.concurrent.CompletionException
                || cur instanceof java.util.concurrent.ExecutionException)) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage() == null ? "" : cur.getMessage().replaceAll("[\\r\\n]+", " ").trim();
        msg = SECRET_LIKE_PATTERN.matcher(msg).replaceAll("[REDACTED]");
        if (msg.length() > 200) {
            msg = msg.substring(0, 200) + "...";
        }
        return cur.getClass().getSimpleName() + (msg.isEmpty() ? "" : ": " + msg);
    }
}
