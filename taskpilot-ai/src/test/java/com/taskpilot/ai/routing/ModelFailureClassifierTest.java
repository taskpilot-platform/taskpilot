package com.taskpilot.ai.routing;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelFailureClassifierTest {

    private static final Instant NOW = Instant.parse("2026-11-23T00:00:00Z");

    static class FakeRateLimitException extends RuntimeException {
        FakeRateLimitException(String msg) { super(msg); }
    }

    static class FakeHeaders {
        private final String name;
        private final String value;
        FakeHeaders(String name, String value) { this.name = name; this.value = value; }
        public List<String> values(String key) {
            return key.equalsIgnoreCase(name) ? List.of(value) : List.of();
        }
    }

    static class FakeOpenAiException extends RuntimeException {
        private final FakeHeaders headers;
        private final String body;
        FakeOpenAiException(String msg, FakeHeaders headers, String body) {
            super(msg);
            this.headers = headers;
            this.body = body;
        }
        public FakeHeaders headers() { return headers; }
        public String body() { return body; }
    }

    @Test
    void bare429_neverInfersProjectWideQuotaExhaustion() {
        var f1 = ModelFailureClassifier.classify(new FakeRateLimitException("429: null"), false, false, NOW);
        var f2 = ModelFailureClassifier.classify(new RuntimeException("HTTP 429 Too Many Requests"), false, false, NOW);

        assertThat(f1.kind()).isEqualTo(ModelFailureClassifier.Kind.RATE_LIMITED);
        assertThat(f1.scope()).isEqualTo(ModelFailureClassifier.QuotaScope.MODEL);
        assertThat(f2.kind()).isEqualTo(ModelFailureClassifier.Kind.RATE_LIMITED);
        assertThat(f2.scope()).isEqualTo(ModelFailureClassifier.QuotaScope.MODEL);
    }

    @Test
    void distinguishesPerModelVsPerProjectQuotaFromProviderMetadata() {
        String perModelBody = """
                {"error":{"details":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","retryDelay":"35s"}]}}
                """;
        var perModel = ModelFailureClassifier.classify(
                new FakeOpenAiException("429", null, perModelBody), false, false, NOW);
        assertThat(perModel.scope()).isEqualTo(ModelFailureClassifier.QuotaScope.MODEL);
        assertThat(perModel.notBefore()).contains(NOW.plusSeconds(35));

        String perProjectBody = """
                {"error":{"details":[{"quotaId":"GenerateRequestsPerDayPerProject","retryDelay":"30s"}]}}
                """;
        var perProject = ModelFailureClassifier.classify(
                new FakeOpenAiException("429", null, perProjectBody), false, false, NOW);
        assertThat(perProject.scope()).isEqualTo(ModelFailureClassifier.QuotaScope.PROJECT);
        assertThat(perProject.notBefore()).contains(NOW.plusSeconds(30));
    }

    @Test
    void parsesRetryAfterHeadersAndGroqTryAgainMessage() {
        var fromHeader = ModelFailureClassifier.classify(
                new FakeOpenAiException("429", new FakeHeaders("retry-after", "45"), null), false, false, NOW);
        assertThat(fromHeader.notBefore()).contains(NOW.plusSeconds(45));

        var fromMsHeader = ModelFailureClassifier.classify(
                new FakeOpenAiException("429", new FakeHeaders("retry-after-ms", "2500"), null), false, false, NOW);
        assertThat(fromMsHeader.notBefore()).contains(NOW.plusMillis(2500));

        var fromGroqMsg = ModelFailureClassifier.classify(
                new RuntimeException("429 Rate limit reached. Please try again in 1m2.5s."), false, false, NOW);
        assertThat(fromGroqMsg.notBefore()).contains(NOW.plusMillis(62_500L));
    }

    @Test
    void classifies404KeysExhaustedAndRedactsSecretsFromSummary() {
        var f404 = ModelFailureClassifier.classify(new RuntimeException("404 model_not_found"), false, false, NOW);
        assertThat(f404.kind()).isEqualTo(ModelFailureClassifier.Kind.NON_RETRYABLE);

        var fKeys = ModelFailureClassifier.classify(
                new IllegalStateException("All Groq keys are temporarily exhausted for openai/gpt-oss-120b"),
                false, false, NOW);
        assertThat(fKeys.kind()).isEqualTo(ModelFailureClassifier.Kind.KEYS_EXHAUSTED);

        var fSecret = ModelFailureClassifier.classify(
                new RuntimeException("503 failed with key gsk_secret123456789 and AIzaSyA1234567890"),
                false, false, NOW);
        assertThat(fSecret.summary()).doesNotContain("gsk_secret").doesNotContain("AIzaSyA").contains("[REDACTED]");
    }
}
