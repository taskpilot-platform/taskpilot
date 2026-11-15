package com.taskpilot.app.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.app.TaskPilotApplication;
import com.taskpilot.app.migration.MigrationDatabaseConfiguration;
import com.taskpilot.infrastructure.config.security.JwtService;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.repository.UserRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class RefreshTokenRotationIntegrationTest {

    private static final String TEST_JWT_SECRET = "taskpilot-test-only-app-security-jwt-signing-key-2026-32bytes";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("taskpilot")
            .withUsername("test")
            .withPassword("test");

    private static ConfigurableApplicationContext context;
    private static String baseUrl;
    private static JwtService jwtService;
    private static UserRepository userRepository;

    @BeforeAll
    static void startApp() throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE ROLE anon NOLOGIN");
            s.execute("CREATE ROLE authenticated NOLOGIN");
            s.execute("ALTER DEFAULT PRIVILEGES GRANT ALL ON TABLES TO anon, authenticated");
        }

        MigrationDatabaseConfiguration.configure(
                Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        ).load().migrate();

        context = SpringApplication.run(TaskPilotApplication.class, appArgs());
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        baseUrl = "http://127.0.0.1:" + port;
        jwtService = context.getBean(JwtService.class);
        userRepository = context.getBean(UserRepository.class);
    }

    @AfterAll
    static void stopApp() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void loginStoresHashOnlyRefreshTokenAndIssuesJwtV2() throws Exception {
        String email = "rt-hash-" + UUID.randomUUID() + "@example.com";
        registerAndLogin(email, "Pass!12345", "Hash Tester");
        AuthTokens tokens = login(email, "Pass!12345");
        UserEntity user = userRepository.findByEmail(email).orElseThrow();

        JwtService.JwtClaims parsed = jwtService.parseAndValidate(tokens.accessToken()).orElseThrow();
        assertThat(parsed.userId()).isEqualTo(user.getId());
        assertThat(parsed.tokenVersion()).isEqualTo(0L);
        assertThat(parsed.tokenSchema()).isEqualTo(2);
        assertThat(parsed.role()).isEqualTo("USER");

        JsonNode jwtPayload = decodeJwtPayload(tokens.accessToken());
        assertThat(jwtPayload.path("sub").asText()).isEqualTo(String.valueOf(user.getId()));
        assertThat(jwtPayload.path("token_schema").asInt()).isEqualTo(2);
        assertThat(jwtPayload.path("ver").asLong()).isEqualTo(0L);
        assertThat(jwtPayload.path("sid").asText()).isEqualTo(parsed.sessionId().toString());
        assertThat(jwtPayload.path("iss").asText()).isEqualTo("taskpilot");
        assertThat(jwtPayload.path("aud").toString()).contains("taskpilot-api");
        assertThat(jwtPayload.has("email")).isFalse();

        String expectedHash = sha256Hex(tokens.refreshToken());
        try (Connection c = openDb();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT token, token_hash, session_id, token_version, revoked_at FROM public.refresh_tokens WHERE session_id = ?")) {
            ps.setObject(1, parsed.sessionId());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("token")).isNull();
                assertThat(rs.getString("token_hash")).isEqualTo(expectedHash);
                assertThat(UUID.fromString(rs.getString("session_id"))).isEqualTo(parsed.sessionId());
                assertThat(rs.getLong("token_version")).isEqualTo(0L);
                assertThat(rs.getTimestamp("revoked_at")).isNull();
            }
        }
    }

    @Test
    void concurrentDoubleRefreshPreservesOneSuccessTriggersTargetedReplayAndSparesOtherSessions() throws Exception {
        String emailA = "rt-replay-a-" + UUID.randomUUID() + "@example.com";
        String emailB = "rt-replay-b-" + UUID.randomUUID() + "@example.com";
        registerAndLogin(emailA, "Pass!12345", "Replay User A");
        AuthTokens userB = registerAndLogin(emailB, "Pass!12345", "Innocent User B");

        AuthTokens initialA = login(emailA, "Pass!12345");
        UUID sidA1 = jwtService.parseAndValidate(initialA.accessToken()).orElseThrow().sessionId();

        // First sequential rotation moves initialA.refreshToken() into refresh_token_history
        HttpResponse<String> firstRotate = postJson("/api/v1/auth/refresh", Map.of("refreshToken", initialA.refreshToken()), null);
        assertThat(firstRotate.statusCode()).isEqualTo(200);
        JsonNode rotatedData = MAPPER.readTree(firstRotate.body()).path("data");
        String rt2 = rotatedData.path("refreshToken").asText();

        String rt1Hash = sha256Hex(initialA.refreshToken());
        String rt2Hash = sha256Hex(rt2);
        try (Connection c = openDb();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT session_id, token_hash, expires_at FROM public.refresh_token_history WHERE token_hash = ?")) {
            ps.setString(1, rt1Hash);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(UUID.fromString(rs.getString("session_id"))).isEqualTo(sidA1);
                assertThat(rs.getString("token_hash")).isEqualTo(rt1Hash);
                assertThat(rs.getTimestamp("expires_at")).isNotNull();
            }
        }

        // Concurrent double-refresh of rt2: 1 winner (200) and 1 loser (401); replay revokes sidA1
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<HttpResponse<String>> task = () -> {
                ready.countDown();
                assertThat(go.await(5, TimeUnit.SECONDS)).isTrue();
                return postJson("/api/v1/auth/refresh", Map.of("refreshToken", rt2), null);
            };
            Future<HttpResponse<String>> f1 = pool.submit(task);
            Future<HttpResponse<String>> f2 = pool.submit(task);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Integer> statuses = List.of(f1.get(10, TimeUnit.SECONDS).statusCode(), f2.get(10, TimeUnit.SECONDS).statusCode());
            assertThat(statuses).containsExactlyInAnyOrder(200, 401);
        } finally {
            pool.shutdownNow();
        }

        // Wait briefly if needed, then verify sidA1 was revoked due to REFRESH_TOKEN_REPLAYED (even if loser caught it)
        try (Connection c = openDb();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT revoked_at, revocation_reason FROM public.refresh_tokens WHERE session_id = ?")) {
            ps.setObject(1, sidA1);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getTimestamp("revoked_at")).isNotNull();
                assertThat(rs.getString("revocation_reason")).isEqualTo("REPLAY_DETECTED");
            }
        }

        // Targeted revocation invariant: User B's unrelated session stays completely valid
        HttpResponse<String> userBMe = get("/api/v1/users/me", userB.accessToken());
        assertThat(userBMe.statusCode()).isEqualTo(200);

        // New login for User A gets a fresh session sidA2; replaying old sidA1 hash (rt2) must NOT revoke sidA2
        AuthTokens freshA = login(emailA, "Pass!12345");
        UUID sidA2 = jwtService.parseAndValidate(freshA.accessToken()).orElseThrow().sessionId();
        assertThat(sidA2).isNotEqualTo(sidA1);

        HttpResponse<String> staleReplay = postJson("/api/v1/auth/refresh", Map.of("refreshToken", rt2), null);
        assertThat(staleReplay.statusCode()).isEqualTo(401);

        HttpResponse<String> freshAMe = get("/api/v1/users/me", freshA.accessToken());
        assertThat(freshAMe.statusCode()).isEqualTo(200);
        assertThat(rt2Hash).isNotBlank();
    }

    @Test
    void logoutRevokesSessionAndRejectsSubsequentJwtAndRefresh() throws Exception {
        String email = "rt-logout-" + UUID.randomUUID() + "@example.com";
        AuthTokens tokens = registerAndLogin(email, "Pass!12345", "Logout User");
        UUID sid = jwtService.parseAndValidate(tokens.accessToken()).orElseThrow().sessionId();

        assertThat(get("/api/v1/users/me", tokens.accessToken()).statusCode()).isEqualTo(200);

        HttpResponse<String> logoutRes = postJson(
                "/api/v1/auth/logout",
                Map.of("refreshToken", tokens.refreshToken()),
                tokens.accessToken()
        );
        assertThat(logoutRes.statusCode()).isEqualTo(200);

        try (Connection c = openDb();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT revoked_at, revocation_reason FROM public.refresh_tokens WHERE session_id = ?")) {
            ps.setObject(1, sid);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getTimestamp("revoked_at")).isNotNull();
                assertThat(rs.getString("revocation_reason")).isEqualTo("LOGOUT");
            }
        }

        assertThat(get("/api/v1/users/me", tokens.accessToken()).statusCode()).isEqualTo(401);
        assertThat(postJson("/api/v1/auth/refresh", Map.of("refreshToken", tokens.refreshToken()), null).statusCode())
                .isEqualTo(401);
    }

    @Test
    void passwordChangeIncrementsTokenVersionAndInvalidatesExistingSessions() throws Exception {
        String email = "rt-pwd-" + UUID.randomUUID() + "@example.com";
        AuthTokens before = registerAndLogin(email, "Pass!12345", "Pwd Changer");
        UUID sidBefore = jwtService.parseAndValidate(before.accessToken()).orElseThrow().sessionId();

        HttpResponse<String> changeResp = putJson(
                "/api/v1/users/me/password",
                Map.of("oldPassword", "Pass!12345", "newPassword", "NewPass!6789"),
                before.accessToken()
        );
        assertThat(changeResp.statusCode()).isEqualTo(200);

        UserEntity updated = userRepository.findByEmail(email).orElseThrow();
        assertThat(updated.getTokenVersion()).isEqualTo(1L);

        try (Connection c = openDb();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT revoked_at, revocation_reason FROM public.refresh_tokens WHERE session_id = ?")) {
            ps.setObject(1, sidBefore);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getTimestamp("revoked_at")).isNotNull();
                assertThat(rs.getString("revocation_reason")).isEqualTo("SECURITY_VERSION_INCREMENT");
            }
        }

        assertThat(get("/api/v1/users/me", before.accessToken()).statusCode()).isEqualTo(401);
        assertThat(postJson("/api/v1/auth/refresh", Map.of("refreshToken", before.refreshToken()), null).statusCode())
                .isEqualTo(401);

        AuthTokens after = login(email, "NewPass!6789");
        JwtService.JwtClaims claimsAfter = jwtService.parseAndValidate(after.accessToken()).orElseThrow();
        assertThat(claimsAfter.tokenVersion()).isEqualTo(1L);
        assertThat(claimsAfter.sessionId()).isNotEqualTo(sidBefore);
    }

    @Test
    void nonPrivilegedDbRolesCannotAccessRefreshTokenHistory() throws Exception {
        try (Connection c = openDb(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery(
                    "SELECT relrowsecurity, " +
                            "(SELECT count(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = 'refresh_token_history' AND policyname = 'deny_all_refresh_token_history') " +
                            "FROM pg_class WHERE relname = 'refresh_token_history'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isTrue();
                assertThat(rs.getLong(2)).isEqualTo(1L);
            }

            c.setAutoCommit(false);
            try {
                s.execute("SET LOCAL ROLE authenticated");
                try (ResultSet rs = s.executeQuery("SELECT count(*) FROM public.refresh_token_history")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong(1)).isZero();
                }
                assertThatThrownBy(() -> s.executeUpdate(
                        "INSERT INTO public.refresh_token_history(session_id, user_id, token_hash, consumed_at, expires_at) " +
                                "VALUES ('00000000-0000-0000-0000-000000000001', 1, repeat('b', 64), now(), now() + interval '1 day')"))
                        .hasMessageContaining("row-level security");
            } finally {
                c.rollback();
            }
        }
    }

    private AuthTokens registerAndLogin(String email, String password, String fullName) throws Exception {
        HttpResponse<String> reg = postJson("/api/v1/auth/register",
                Map.of("email", email, "password", password, "fullName", fullName), null);
        assertThat(reg.statusCode()).isEqualTo(201);
        return login(email, password);
    }

    private AuthTokens login(String email, String password) throws Exception {
        HttpResponse<String> resp = postJson("/api/v1/auth/login",
                Map.of("email", email, "password", password), null);
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode data = MAPPER.readTree(resp.body()).path("data");
        return new AuthTokens(data.path("token").asText(), data.path("refreshToken").asText());
    }

    private HttpResponse<String> postJson(String path, Object body, String bearerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> putJson(String path, Object body, String bearerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String bearerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .GET();
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Connection openDb() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static JsonNode decodeJwtPayload(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
        return MAPPER.readTree(new String(decoded, StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String raw) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private static String[] appArgs() {
        List<String> args = new ArrayList<>();
        args.add("--server.port=0");
        args.add("--spring.profiles.active=dev");
        args.add("--spring.datasource.url=" + postgres.getJdbcUrl());
        args.add("--spring.datasource.username=" + postgres.getUsername());
        args.add("--spring.datasource.password=" + postgres.getPassword());
        args.add("--spring.jpa.hibernate.ddl-auto=none");
        args.add("--ai.gemini.api-key=local-dummy-not-a-secret");
        args.add("--ai.gemini.api-keys=");
        args.add("--ai.groq.enabled=false");
        args.add("--ai.groq.api-key=");
        args.add("--ai.groq.api-keys=");
        args.add("--ai.openrouter.enabled=false");
        args.add("--ai.openrouter.api-key=");
        args.add("--ai.openrouter.api-keys=");
        args.add("--google.drive.client-id=");
        args.add("--google.drive.client-secret=");
        args.add("--google.drive.refresh-token=");
        args.add("--application.security.oauth2.google.client-id=");
        args.add("--application.security.oauth2.google.client-secret=");
        args.add("--application.security.oauth2.github.client-id=");
        args.add("--application.security.oauth2.github.client-secret=");
        args.add("--onesignal.app-id=");
        args.add("--onesignal.api-key=");
        args.add("--livekit.api-key=local-dummy");
        args.add("--livekit.api-secret=local-dummy");
        args.add("--supabase.s3.endpoint=http://127.0.0.1:9");
        args.add("--supabase.s3.access-key=local-dummy");
        args.add("--supabase.s3.secret-key=local-dummy");
        args.add("--application.security.jwt.secret-key=" + TEST_JWT_SECRET);
        args.add("--application.security.jwt.issuer=taskpilot");
        args.add("--application.security.jwt.audience=taskpilot-api");
        args.add("--application.security.jwt.blocklist.provider=memory");
        args.add("--application.security.password-reset.rate-limit.provider=memory");
        args.add("--rag.embedding.poll-interval-ms=86400000");
        args.add("--decorator.datasource.p6spy.enable-logging=false");
        args.add("--logging.level.root=WARN");
        return args.toArray(String[]::new);
    }

    private record AuthTokens(String accessToken, String refreshToken) {}
}
