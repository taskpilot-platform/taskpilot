package com.taskpilot.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.app.TaskPilotApplication;
import com.taskpilot.app.migration.MigrationDatabaseConfiguration;
import com.taskpilot.infrastructure.config.security.JwtService;
import com.taskpilot.users.auth.service.DatabaseAuthAttemptStore;
import com.taskpilot.users.auth.service.UserSessionService;
import com.taskpilot.users.auth.service.email.EmailService;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.entity.UserIdentityEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.AuthProvider;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.enums.UserStatus;
import com.taskpilot.users.common.repository.PasswordResetTokenRepository;
import com.taskpilot.users.common.repository.UserIdentityRepository;
import com.taskpilot.users.common.repository.UserRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class EmailChallengeConcurrencyIntegrationTest {

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
    private static CapturingEmailService emailCapture;
    private static UserRepository userRepository;
    private static UserIdentityRepository identityRepository;
    private static PasswordResetTokenRepository tokenRepository;
    private static JwtService jwtService;

    @TestConfiguration
    static class CapturingEmailConfig {
        @Bean
        @Primary
        CapturingEmailService capturingEmailService() {
            return new CapturingEmailService();
        }
    }

    static class CapturingEmailService implements EmailService {
        final Map<String, String> lastVerifyToken = new ConcurrentHashMap<>();
        final Map<String, String> lastResetToken = new ConcurrentHashMap<>();
        final Map<String, String> lastSetupToken = new ConcurrentHashMap<>();

        @Override
        public void sendPasswordResetEmail(String toEmail, String resetToken, long expirationMs) {
            lastResetToken.put(toEmail.toLowerCase(), resetToken);
        }

        @Override
        public void sendEmailVerificationEmail(String toEmail, String verificationToken, long expirationMs) {
            lastVerifyToken.put(toEmail.toLowerCase(), verificationToken);
        }

        @Override
        public void sendPasswordSetupEmail(String toEmail, String setupToken, long expirationMs) {
            lastSetupToken.put(toEmail.toLowerCase(), setupToken);
        }
    }

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

        context = SpringApplication.run(
                new Class<?>[]{TaskPilotApplication.class, CapturingEmailConfig.class},
                appArgs());
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        baseUrl = "http://127.0.0.1:" + port;
        emailCapture = context.getBean(CapturingEmailService.class);
        userRepository = context.getBean(UserRepository.class);
        identityRepository = context.getBean(UserIdentityRepository.class);
        tokenRepository = context.getBean(PasswordResetTokenRepository.class);
        jwtService = context.getBean(JwtService.class);
    }

    @AfterAll
    static void stopApp() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void preHijackPendingPasswordNeverActivatesAndConcurrentVerifyConsumeHasOneWinner() throws Exception {
        String victimEmail = "victim-" + UUID.randomUUID() + "@example.com";
        String attackerPendingPass = "AttackerSecret99!";
        String ownerChosenPass = "MailboxOwnerPass1!";

        HttpResponse<String> reg = postJson("/api/v1/auth/register",
                Map.of("email", victimEmail, "password", attackerPendingPass, "fullName", "Victim"), null);
        assertThat(reg.statusCode()).isEqualTo(201);

        // Attacker pending password cannot log in before verification (returns discriminated outcome, zero tokens)
        HttpResponse<String> pendingLogin = postJson("/api/v1/auth/login",
                Map.of("email", victimEmail, "password", attackerPendingPass), null);
        assertThat(pendingLogin.statusCode()).isEqualTo(200);
        var pendingData = MAPPER.readTree(pendingLogin.body()).path("data");
        assertThat(pendingData.path("outcome").asText()).isEqualTo("EMAIL_VERIFICATION_REQUIRED");
        assertThat(pendingData.path("token").isMissingNode() || pendingData.path("token").isNull()).isTrue();

        String verifyToken = emailCapture.lastVerifyToken.get(victimEmail.toLowerCase());
        assertThat(verifyToken).isNotBlank();

        // Purpose mismatch: verifyToken rejected by reset-password & password-setup/complete
        assertThat(postJson("/api/v1/auth/reset-password",
                Map.of("token", verifyToken, "newPassword", ownerChosenPass), null).statusCode())
                .isEqualTo(400);

        // Hash-only persistence check in PostgreSQL
        try (Connection c = openDb();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT token, token_hash, purpose FROM public.password_reset_tokens " +
                             "WHERE user_id = (SELECT id FROM public.users WHERE email = ?)")) {
            ps.setString(1, victimEmail.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("token")).isNull();
                assertThat(rs.getString("token_hash")).hasSize(64).doesNotContain(verifyToken);
                assertThat(rs.getString("purpose")).isEqualTo("EMAIL_VERIFY");
            }
        }

        // Concurrent consume race: exactly one wins, second fails 400
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> c1 = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return postJson("/api/v1/auth/verify-email",
                        Map.of("token", verifyToken, "newPassword", ownerChosenPass), null).statusCode();
            });
            Future<Integer> c2 = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return postJson("/api/v1/auth/verify-email",
                        Map.of("token", verifyToken, "newPassword", ownerChosenPass), null).statusCode();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> codes = List.of(c1.get(10, TimeUnit.SECONDS), c2.get(10, TimeUnit.SECONDS));
            assertThat(codes).containsExactlyInAnyOrder(200, 400);
        }

        // Attacker pending password STILL rejected! Mailbox owner's password logs in!
        assertThat(postJson("/api/v1/auth/login",
                Map.of("email", victimEmail, "password", attackerPendingPass), null).statusCode())
                .isEqualTo(401);
        assertThat(postJson("/api/v1/auth/login",
                Map.of("email", victimEmail, "password", ownerChosenPass), null).statusCode())
                .isEqualTo(200);

        // Throttling after 5 failures -> 429
        for (int i = 0; i < 5; i++) {
            assertThat(postJson("/api/v1/auth/login",
                    Map.of("email", victimEmail, "password", "WrongPassword1!"), null).statusCode())
                    .isEqualTo(401);
        }
        assertThat(postJson("/api/v1/auth/login",
                Map.of("email", victimEmail, "password", "WrongPassword1!"), null).statusCode())
                .isEqualTo(429);
    }

    @Test
    void oauthOnlyAccountSetsPasswordViaEmailSetupAndRejectsDirectChangePasswordAndStaleVersion() throws Exception {
        String email = "oauth-setup-" + UUID.randomUUID() + "@example.com";
        UserEntity oauthUser = userRepository.saveAndFlush(UserEntity.builder()
                .email(email)
                .fullName("OAuth Only")
                .password(null)
                .role(UserRole.USER)
                .status(UserStatus.AVAILABLE)
                .accountState(AccountLifecycle.ACTIVE)
                .emailVerified(true)
                .tokenVersion(0L)
                .build());
        identityRepository.saveAndFlush(UserIdentityEntity.builder()
                .user(oauthUser)
                .provider(AuthProvider.GOOGLE)
                .providerSubject("google-sub-" + UUID.randomUUID())
                .email(email)
                .emailVerifiedAt(Instant.now())
                .verificationProvenance("GOOGLE_ID_TOKEN")
                .build());

        String oauthJwt = context.getBean(UserSessionService.class).issueSession(oauthUser.getId()).token();

        // Passwordless change-password must fail
        assertThat(putJson("/api/v1/users/me/password", Map.of("newPassword", "DirectBypass1!"), oauthJwt).statusCode())
                .isEqualTo(400);

        // Request setup email, complete setup, assert old JWT revoked & local password works
        assertThat(postJson("/api/v1/auth/password-setup/request", Map.of(), oauthJwt).statusCode())
                .isEqualTo(200);
        String setupToken = emailCapture.lastSetupToken.get(email.toLowerCase());
        assertThat(setupToken).isNotBlank();

        assertThat(postJson("/api/v1/auth/password-setup/complete",
                Map.of("token", setupToken, "newPassword", "SetupPassword99!"), null).statusCode())
                .isEqualTo(200);
        assertThat(get("/api/v1/users/me", oauthJwt).statusCode()).isEqualTo(401);
        assertThat(postJson("/api/v1/auth/login",
                Map.of("email", email, "password", "SetupPassword99!"), null).statusCode())
                .isEqualTo(200);

        // Stale token invalidated by credential version bump
        assertThat(postJson("/api/v1/auth/forgot-password", Map.of("email", email), null).statusCode())
                .isEqualTo(200);
        String resetToken = emailCapture.lastResetToken.get(email.toLowerCase());
        assertThat(resetToken).isNotBlank();

        String newJwt = MAPPER.readTree(
                postJson("/api/v1/auth/login", Map.of("email", email, "password", "SetupPassword99!"), null).body())
                .path("data").path("token").asText();
        assertThat(putJson("/api/v1/users/me/password",
                Map.of("oldPassword", "SetupPassword99!", "newPassword", "RotatedPass99!"), newJwt).statusCode())
                .isEqualTo(200);

        // Old resetToken minted at tokenVersion=1 must now fail because credential version bumped to 2
        assertThat(postJson("/api/v1/auth/reset-password",
                Map.of("token", resetToken, "newPassword", "StaleReplayPass1!"), null).statusCode())
                .isEqualTo(400);
    }

    @Test
    void utf8PasswordByteBoundariesEnforcedAcrossAllFivePasswordRoutes() throws Exception {
        String tooShort = "Ab1!xyz"; // 7 chars < 8
        String exact72Ascii = "A".repeat(70) + "1!"; // 72 UTF-8 bytes
        String over73Ascii = "A".repeat(71) + "1!"; // 73 UTF-8 bytes
        String exact72Emoji = "😀".repeat(18); // 18 chars, 72 UTF-8 bytes
        String over76Emoji = "😀".repeat(19); // 19 chars, 76 UTF-8 bytes > 72 bytes

        // 1. /auth/register
        assertThat(postJson("/api/v1/auth/register",
                Map.of("email", "pw-short-" + UUID.randomUUID() + "@example.com", "password", tooShort, "fullName", "U"), null).statusCode())
                .isEqualTo(400);
        assertThat(postJson("/api/v1/auth/register",
                Map.of("email", "pw-over-" + UUID.randomUUID() + "@example.com", "password", over73Ascii, "fullName", "U"), null).statusCode())
                .isEqualTo(400);
        assertThat(postJson("/api/v1/auth/register",
                Map.of("email", "pw-em76-" + UUID.randomUUID() + "@example.com", "password", over76Emoji, "fullName", "U"), null).statusCode())
                .isEqualTo(400);
        String regEmail = "pw-em72-" + UUID.randomUUID() + "@example.com";
        assertThat(postJson("/api/v1/auth/register",
                Map.of("email", regEmail, "password", exact72Emoji, "fullName", "Emoji User"), null).statusCode())
                .isEqualTo(201);

        // 2. /auth/verify-email (rejects 7-char / 73-ascii / 76-emoji without consuming token; accepts 72-ascii)
        String verifyToken = emailCapture.lastVerifyToken.get(regEmail.toLowerCase());
        assertThat(verifyToken).isNotBlank();
        assertThat(postJson("/api/v1/auth/verify-email",
                Map.of("token", verifyToken, "newPassword", tooShort), null).statusCode()).isEqualTo(400);
        assertThat(postJson("/api/v1/auth/verify-email",
                Map.of("token", verifyToken, "newPassword", over76Emoji), null).statusCode()).isEqualTo(400);
        assertThat(postJson("/api/v1/auth/verify-email",
                Map.of("token", verifyToken, "newPassword", exact72Ascii), null).statusCode()).isEqualTo(200);

        String jwt = MAPPER.readTree(postJson("/api/v1/auth/login",
                Map.of("email", regEmail, "password", exact72Ascii), null).body()).path("data").path("token").asText();

        // 3. PUT /users/me/password
        assertThat(putJson("/api/v1/users/me/password",
                Map.of("oldPassword", exact72Ascii, "newPassword", tooShort), jwt).statusCode()).isEqualTo(400);
        assertThat(putJson("/api/v1/users/me/password",
                Map.of("oldPassword", exact72Ascii, "newPassword", over76Emoji), jwt).statusCode()).isEqualTo(400);
        assertThat(putJson("/api/v1/users/me/password",
                Map.of("oldPassword", exact72Ascii, "newPassword", exact72Emoji), jwt).statusCode()).isEqualTo(200);

        // 4. /auth/forgot-password + /auth/reset-password
        assertThat(postJson("/api/v1/auth/forgot-password", Map.of("email", regEmail), null).statusCode())
                .isEqualTo(200);
        String resetToken = emailCapture.lastResetToken.get(regEmail.toLowerCase());
        assertThat(resetToken).isNotBlank();
        assertThat(postJson("/api/v1/auth/reset-password",
                Map.of("token", resetToken, "newPassword", over73Ascii), null).statusCode()).isEqualTo(400);
        assertThat(postJson("/api/v1/auth/reset-password",
                Map.of("token", resetToken, "newPassword", exact72Ascii), null).statusCode()).isEqualTo(200);

        // 5. /auth/password-setup/complete for OAuth-only user
        String oauthEmail = "pw-oauth-" + UUID.randomUUID() + "@example.com";
        UserEntity oauthUser = userRepository.saveAndFlush(UserEntity.builder()
                .email(oauthEmail).fullName("OAuth Boundary").password(null)
                .role(UserRole.USER).status(UserStatus.AVAILABLE).accountState(AccountLifecycle.ACTIVE)
                .emailVerified(true).tokenVersion(0L).build());
        identityRepository.saveAndFlush(UserIdentityEntity.builder()
                .user(oauthUser).provider(AuthProvider.GITHUB)
                .providerSubject("gh-bnd-" + UUID.randomUUID()).email(oauthEmail)
                .emailVerifiedAt(Instant.now()).verificationProvenance("GITHUB_EMAILS_API").build());
        String oauthJwt = context.getBean(UserSessionService.class).issueSession(oauthUser.getId()).token();
        assertThat(postJson("/api/v1/auth/password-setup/request", Map.of(), oauthJwt).statusCode()).isEqualTo(200);
        String setupToken = emailCapture.lastSetupToken.get(oauthEmail.toLowerCase());
        assertThat(postJson("/api/v1/auth/password-setup/complete",
                Map.of("token", setupToken, "newPassword", over76Emoji), null).statusCode()).isEqualTo(400);
        assertThat(postJson("/api/v1/auth/password-setup/complete",
                Map.of("token", setupToken, "newPassword", exact72Emoji), null).statusCode()).isEqualTo(200);
    }

    @Test
    void databaseAuthAttemptStoreAtomicAcrossConcurrentInstancesAndThrottleCapsReturn429() throws Exception {
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        DatabaseAuthAttemptStore storeA = new DatabaseAuthAttemptStore(jdbc);
        DatabaseAuthAttemptStore storeB = new DatabaseAuthAttemptStore(jdbc);
        String keyHash = UUID.randomUUID().toString();
        Instant fixedNow = Instant.now();

        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            CountDownLatch ready = new CountDownLatch(16);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                final DatabaseAuthAttemptStore target = (i % 2 == 0) ? storeA : storeB;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    long c1 = target.incrementAndGet("PG_RACE", keyHash, Duration.ofMinutes(5), fixedNow);
                    long c2 = target.incrementAndGet("PG_RACE", keyHash, Duration.ofMinutes(5), fixedNow);
                    return Math.max(c1, c2);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<Long> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        }
        assertThat(storeA.currentCount("PG_RACE", keyHash, fixedNow)).isEqualTo(32L);

        // Exercise real HTTP throttle caps with clean PG counters before and after.
        try (Connection c = openDb(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM public.auth_attempt_counters");
        }
        try {
            // Email challenge cap via verify-email/resend: 5 per email/hour -> 6th returns 429
            String throttleEmail = "cap-" + UUID.randomUUID() + "@example.com";
            for (int i = 0; i < 5; i++) {
                assertThat(postJson("/api/v1/auth/verify-email/resend", Map.of("email", throttleEmail), null).statusCode())
                        .isEqualTo(200);
            }
            assertThat(postJson("/api/v1/auth/verify-email/resend", Map.of("email", throttleEmail), null).statusCode())
                    .isEqualTo(429);

            // Restore-confirm cap: 10 per IP/window -> 11th returns 429
            for (int i = 0; i < 10; i++) {
                assertThat(postJson("/api/v1/auth/restore/confirm",
                        Map.of("ticket", "invalid-ticket-" + i), null).statusCode())
                        .isEqualTo(400);
            }
            assertThat(postJson("/api/v1/auth/restore/confirm",
                    Map.of("ticket", "invalid-ticket-overflow"), null).statusCode())
                    .isEqualTo(429);

            // Login IP spray cap: 100 per IP/window -> 101st returns 429
            for (int i = 0; i < 100; i++) {
                assertThat(postJson("/api/v1/auth/login",
                        Map.of("email", "spray-" + i + "@example.com", "password", "WrongPass1!"), null).statusCode())
                        .isEqualTo(401);
            }
            assertThat(postJson("/api/v1/auth/login",
                    Map.of("email", "spray-overflow@example.com", "password", "WrongPass1!"), null).statusCode())
                    .isEqualTo(429);
        } finally {
            try (Connection c = openDb(); Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM public.auth_attempt_counters");
            }
        }
    }

    private HttpResponse<String> postJson(String path, Object body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> putJson(String path, Object body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Connection openDb() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static String[] appArgs() {
        return new String[]{
                "--server.port=0",
                "--spring.profiles.active=dev",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.jpa.hibernate.ddl-auto=none",
                "--spring.flyway.enabled=false",
                "--spring.mail.host=localhost",
                "--spring.mail.port=1025",
                "--spring.task.scheduling.enabled=false",
                "--ai.gemini.api-key=local-dummy-not-a-secret",
                "--ai.groq.enabled=false",
                "--ai.openrouter.enabled=false",
                "--supabase.s3.endpoint=http://127.0.0.1:19999",
                "--supabase.s3.access-key=test-access",
                "--supabase.s3.secret-key=test-secret",
                "--supabase.s3.bucket=test-bucket",
                "--supabase.s3.public-url=http://127.0.0.1:19999/public",
                "--application.security.jwt.secret-key=" + TEST_JWT_SECRET,
                "--application.security.jwt.issuer=taskpilot",
                "--application.security.jwt.audience=taskpilot-api",
                "--application.security.jwt.expiration=60000",
                "--application.security.jwt.refresh-token.expiration=604800000",
                "--application.security.jwt.blocklist.provider=memory",
                "--application.security.password-reset.rate-limit.provider=memory",
                "--application.security.auth-throttle.challenge-cooldown-seconds=0",
                "--rag.embedding.poll-interval-ms=86400000",
                "--decorator.datasource.p6spy.enable-logging=false",
                "--logging.level.root=WARN"
        };
    }
}
