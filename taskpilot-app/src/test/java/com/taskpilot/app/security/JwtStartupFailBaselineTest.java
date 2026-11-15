package com.taskpilot.app.security;

import com.taskpilot.app.TaskPilotApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JwtStartupFailBaselineTest {

    private static final String KNOWN_PUBLIC_SAMPLE_JWT_KEY =
            "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("jwt_startup_gate")
            .withUsername("test")
            .withPassword("test");

    @Test
    @Timeout(150)
    void prodProfileRejectsKnownPublicSampleJwtKey() {
        assertStartupFails("--application.security.jwt.secret-key=" + KNOWN_PUBLIC_SAMPLE_JWT_KEY,
                "forbidden public sample key");
        assertStartupFails("--application.security.jwt.secret-key=  " + KNOWN_PUBLIC_SAMPLE_JWT_KEY + "  ",
                "forbidden public sample key");
    }

    @Test
    @Timeout(150)
    void prodProfileRejectsShortJwtKey() {
        assertStartupFails("--application.security.jwt.secret-key=too-short-key",
                "at least 32 UTF-8 bytes");
        assertStartupFails("--application.security.jwt.secret-key=too-short-key" + " ".repeat(30),
                "at least 32 UTF-8 bytes");
    }

    @Test
    @Timeout(150)
    void prodProfileRejectsMissingAndDevMarkerJwtKey() {
        assertStartupFails("--application.security.jwt.secret-key=",
                "JWT signing key is missing");
        assertStartupFails("--application.security.jwt.secret-key=taskpilot-dev-only-local-loopback-jwt-signing-key-2026-01",
                "Non-production JWT signing key marker");
    }

    private void assertStartupFails(String secretArg, String expectedCauseSubstring) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.profiles.active=prod",
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=test",
                "--spring.datasource.password=test",
                "--spring.flyway.url=" + postgres.getJdbcUrl(),
                "--spring.flyway.user=test",
                "--spring.flyway.password=test",
                "--spring.jpa.hibernate.ddl-auto=none",
                "--spring.output.ansi.enabled=never",
                "--ai.gemini.api-key=local-dummy-not-a-secret",
                "--ai.gemini.api-keys=",
                "--ai.groq.enabled=false",
                "--ai.groq.api-key=",
                "--ai.groq.api-keys=",
                "--ai.openrouter.enabled=false",
                "--ai.openrouter.api-key=",
                "--ai.openrouter.api-keys=",
                "--google.drive.client-id=",
                "--google.drive.client-secret=",
                "--google.drive.refresh-token=",
                "--application.security.oauth2.google.client-id=",
                "--application.security.oauth2.google.client-secret=",
                "--application.security.oauth2.github.client-id=",
                "--application.security.oauth2.github.client-secret=",
                "--onesignal.app-id=",
                "--onesignal.api-key=",
                "--livekit.api-key=local-dummy",
                "--livekit.api-secret=local-dummy",
                "--supabase.s3.endpoint=http://127.0.0.1:9",
                "--supabase.s3.access-key=local-dummy",
                "--supabase.s3.secret-key=local-dummy",
                "--application.security.jwt.blocklist.provider=memory",
                "--application.security.password-reset.rate-limit.provider=memory",
                "--rag.embedding.poll-interval-ms=86400000",
                "--decorator.datasource.p6spy.enable-logging=false",
                "--logging.level.root=WARN",
                secretArg
        ));

        assertThatThrownBy(() -> {
            ConfigurableApplicationContext ctx = SpringApplication.run(
                    TaskPilotApplication.class, args.toArray(String[]::new));
            ctx.close();
        }).satisfies(ex -> {
            Throwable root = ex;
            StringBuilder messages = new StringBuilder();
            while (root != null) {
                if (root.getMessage() != null) {
                    messages.append(root.getMessage()).append(" | ");
                }
                if (root instanceof IllegalStateException && root.getMessage() != null
                        && root.getMessage().contains(expectedCauseSubstring)) {
                    break;
                }
                root = root.getCause();
            }
            assertThat(messages.toString()).contains(expectedCauseSubstring);
        });
    }
}
