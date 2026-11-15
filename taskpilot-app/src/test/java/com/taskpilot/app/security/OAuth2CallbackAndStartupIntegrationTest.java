package com.taskpilot.app.security;

import com.taskpilot.app.TaskPilotApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class OAuth2CallbackAndStartupIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("oauth2_app_callback")
            .withUsername("test")
            .withPassword("test");

    @Test
    @Timeout(180)
    void realSpringSecurityOAuth2LifecycleSupportsTwoTabsCrossInstanceAndCorsEnforcement() throws Exception {
        String[] args1 = buildIsolatedArgs();
        String[] args2 = buildIsolatedArgs();

        try (var ctx1 = SpringApplication.run(TaskPilotApplication.class, args1);
             var ctx2 = SpringApplication.run(TaskPilotApplication.class, args2)) {

            int port1 = ((WebServerApplicationContext) ctx1).getWebServer().getPort();
            int port2 = ((WebServerApplicationContext) ctx2).getWebServer().getPort();
            JdbcTemplate jdbc = ctx1.getBean(JdbcTemplate.class);
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

            // 1. Initiate two simultaneous OAuth2 tabs on instance 1 (shared external ingress Host)
            int ingressPort = 18080;
            RawHttpResponse initTab1 = sendGetWithHost(
                    port1, ingressPort, "/oauth2/authorization/google", null);
            assertThat(initTab1.statusCode()).isEqualTo(302);
            String state1 = extractQueryParam(initTab1.firstHeader("Location"), "state");
            String cookie1 = extractOpaqueRequestCookie(initTab1.headers("Set-Cookie"));
            assertThat(cookie1).startsWith("tp_oauth2_req_");

            RawHttpResponse initTab2 = sendGetWithHost(
                    port1, ingressPort, "/oauth2/authorization/google", cookie1);
            assertThat(initTab2.statusCode()).isEqualTo(302);
            String state2 = extractQueryParam(initTab2.firstHeader("Location"), "state");
            String cookie2 = extractOpaqueRequestCookie(initTab2.headers("Set-Cookie"));
            assertThat(state2).isNotEqualTo(state1);
            assertThat(cookie2).isNotEqualTo(cookie1);

            Long pendingBefore = jdbc.queryForObject(
                    "SELECT count(*) FROM public.oauth2_authorization_requests WHERE consumed_at IS NULL",
                    Long.class);
            assertThat(pendingBefore).isEqualTo(2L);

            // 2. Callback for tab 1 hits instance 2 (cross-instance!), sending both cookies.
            // Uses error=access_denied so OAuth2LoginAuthenticationFilter consumes the DB row before
            // OAuth2LoginAuthenticationProvider fails fast locally with zero external provider HTTP calls.
            String encodedState1 = URLEncoder.encode(state1, StandardCharsets.UTF_8);
            RawHttpResponse callbackTab1 = sendGetWithHost(
                    port2, ingressPort,
                    "/login/oauth2/code/google?error=access_denied&state=" + encodedState1,
                    cookie1 + "; " + cookie2);
            assertThat(callbackTab1.statusCode()).isEqualTo(302);
            String redirect1 = callbackTab1.firstHeader("Location");
            assertThat(redirect1).isEqualTo("http://localhost:5173/auth/callback?error=access_denied");

            Long pendingAfterTab1 = jdbc.queryForObject(
                    "SELECT count(*) FROM public.oauth2_authorization_requests WHERE consumed_at IS NULL",
                    Long.class);
            assertThat(pendingAfterTab1).isEqualTo(1L);

            // 3. Replaying tab 1 callback fails as invalid_state
            RawHttpResponse replayTab1 = sendGetWithHost(
                    port2, ingressPort,
                    "/login/oauth2/code/google?error=access_denied&state=" + encodedState1,
                    cookie1 + "; " + cookie2);
            assertThat(replayTab1.statusCode()).isEqualTo(302);
            assertThat(replayTab1.firstHeader("Location"))
                    .isEqualTo("http://localhost:5173/auth/callback?error=invalid_state");

            // 4. Tab 2 callback still succeeds in reaching OAuth2LoginAuthenticationFilter on instance 1
            String encodedState2 = URLEncoder.encode(state2, StandardCharsets.UTF_8);
            RawHttpResponse callbackTab2 = sendGetWithHost(
                    port1, ingressPort,
                    "/login/oauth2/code/google?error=access_denied&state=" + encodedState2,
                    cookie2);
            assertThat(callbackTab2.statusCode()).isEqualTo(302);
            assertThat(callbackTab2.firstHeader("Location"))
                    .isEqualTo("http://localhost:5173/auth/callback?error=access_denied");

            Long pendingFinal = jdbc.queryForObject(
                    "SELECT count(*) FROM public.oauth2_authorization_requests WHERE consumed_at IS NULL",
                    Long.class);
            assertThat(pendingFinal).isZero();

            // 5. Real HTTP CORS enforcement: allowed origin vs hostile origin
            HttpResponse<String> allowedCors = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port1 + "/actuator/health"))
                            .header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", "GET")
                            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(allowedCors.headers().firstValue("Access-Control-Allow-Origin"))
                    .contains("http://localhost:5173");
            assertThat(allowedCors.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();

            HttpResponse<String> hostileCors = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port1 + "/actuator/health"))
                            .header("Origin", "https://evil.attacker.invalid")
                            .header("Access-Control-Request-Method", "GET")
                            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(hostileCors.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        }
    }

    private static String[] buildIsolatedArgs() {
        return new String[] {
                "--spring.profiles.active=dev",
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
                "--application.security.jwt.secret-key=taskpilot-test-only-oauth2-callback-jwt-signing-key-2026",
                "--application.security.oauth2.google.client-id=dummy-google-client-id",
                "--application.security.oauth2.google.client-secret=dummy-google-client-secret",
                "--application.security.oauth2.github.client-id=",
                "--application.security.oauth2.github.client-secret=",
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
                "--logging.level.root=WARN"
        };
    }

    private static RawHttpResponse sendGetWithHost(int targetPort, int externalPort, String pathAndQuery, String cookieHeader)
            throws Exception {
        StringBuilder req = new StringBuilder();
        req.append("GET ").append(pathAndQuery).append(" HTTP/1.1\r\n");
        req.append("Host: 127.0.0.1:").append(externalPort).append("\r\n");
        if (cookieHeader != null && !cookieHeader.isBlank()) {
            req.append("Cookie: ").append(cookieHeader).append("\r\n");
        }
        req.append("Connection: close\r\n\r\n");

        try (Socket socket = new Socket("127.0.0.1", targetPort)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            InputStream in = socket.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            String raw = buf.toString(StandardCharsets.ISO_8859_1);
            int headerEnd = raw.indexOf("\r\n\r\n");
            String head = headerEnd >= 0 ? raw.substring(0, headerEnd) : raw;
            String[] lines = head.split("\r\n");
            int status = Integer.parseInt(lines[0].split(" ", 3)[1]);
            List<String[]> headers = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    headers.add(new String[] {
                            lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            lines[i].substring(colon + 1).trim()
                    });
                }
            }
            return new RawHttpResponse(status, headers);
        }
    }

    private record RawHttpResponse(int statusCode, List<String[]> rawHeaders) {
        String firstHeader(String name) {
            String key = name.toLowerCase(Locale.ROOT);
            return rawHeaders.stream()
                    .filter(h -> h[0].equals(key))
                    .map(h -> h[1])
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Missing header: " + name));
        }

        List<String> headers(String name) {
            String key = name.toLowerCase(Locale.ROOT);
            return rawHeaders.stream()
                    .filter(h -> h[0].equals(key))
                    .map(h -> h[1])
                    .toList();
        }
    }

    private static String extractOpaqueRequestCookie(List<String> setCookieHeaders) {
        return setCookieHeaders.stream()
                .map(h -> h.split(";", 2)[0].trim())
                .filter(nv -> nv.startsWith("tp_oauth2_req_") && !nv.endsWith("="))
                .findFirst()
                .orElseThrow();
    }

    private static String extractQueryParam(String url, String key) {
        URI uri = URI.create(url);
        for (String pair : uri.getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Missing query param " + key);
    }
}
