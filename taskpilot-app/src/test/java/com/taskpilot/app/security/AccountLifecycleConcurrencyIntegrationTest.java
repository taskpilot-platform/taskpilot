package com.taskpilot.app.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.ai.dto.CandidateScore;
import com.taskpilot.ai.service.AutoAssignmentService;
import com.taskpilot.app.TaskPilotApplication;
import com.taskpilot.app.migration.MigrationDatabaseConfiguration;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.users.auth.oauth2.CredentialLinkService;
import com.taskpilot.users.auth.oauth2.OAuth2AccountService;
import com.taskpilot.users.auth.oauth2.OAuth2UserInfo;
import com.taskpilot.users.auth.service.EmailChallengeService;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.AuthProvider;
import com.taskpilot.users.common.enums.EmailTokenPurpose;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.enums.UserStatus;
import com.taskpilot.users.common.repository.UserIdentityRepository;
import com.taskpilot.users.common.repository.UserRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2AuthorizationRequestStore.Purpose;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
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
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AccountLifecycleConcurrencyIntegrationTest {

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
    private static UserRepository userRepository;
    private static PasswordEncoder passwordEncoder;
    private static EmailChallengeService emailChallengeService;
    private static ProjectRepository projectRepository;
    private static ProjectMemberRepository projectMemberRepository;
    private static AutoAssignmentService autoAssignmentService;
    private static OAuth2AccountService oAuth2AccountService;
    private static CredentialLinkService credentialLinkService;
    private static UserIdentityRepository userIdentityRepository;

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
        userRepository = context.getBean(UserRepository.class);
        passwordEncoder = context.getBean(PasswordEncoder.class);
        emailChallengeService = context.getBean(EmailChallengeService.class);
        projectRepository = context.getBean(ProjectRepository.class);
        projectMemberRepository = context.getBean(ProjectMemberRepository.class);
        autoAssignmentService = context.getBean(AutoAssignmentService.class);
        oAuth2AccountService = context.getBean(OAuth2AccountService.class);
        credentialLinkService = context.getBean(CredentialLinkService.class);
        userIdentityRepository = context.getBean(UserIdentityRepository.class);
    }

    @AfterAll
    static void stopApp() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void selfDeactivatePreservesUidEmailAvailabilityAndUsesSingleUseRestoreTicketWithConcurrentGuard() throws Exception {
        String adminEmail = "life-adm-" + UUID.randomUUID() + "@example.com";
        String userEmail = "life-usr-" + UUID.randomUUID() + "@example.com";
        UserEntity admin = createVerifiedUser(adminEmail, "Pass!12345", "Admin", UserRole.ADMIN);
        UserEntity user = createVerifiedUser(userEmail, "Pass!12345", "User One", UserRole.USER);

        String jwt = login(userEmail, "Pass!12345");
        String adminJwt = login(adminEmail, "Pass!12345");

        // Availability stays independent of lifecycle
        assertThat(putJson("/api/v1/users/me/availability", Map.of("availability", "OOO"), jwt).statusCode())
                .isEqualTo(200);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.OOO);

        // Self-deactivate preserves uid & email without _deleted_ rename
        assertThat(postJson("/api/v1/users/me/self-deactivate",
                Map.of("confirmation", "DEACTIVATE", "currentPassword", "Pass!12345"), jwt).statusCode())
                .isEqualTo(200);
        UserEntity deactivated = userRepository.findById(user.getId()).orElseThrow();
        assertThat(deactivated.getAccountState()).isEqualTo(AccountLifecycle.SELF_DEACTIVATED);
        assertThat(deactivated.getEmail()).isEqualTo(userEmail.toLowerCase(Locale.ROOT));
        assertThat(get("/api/v1/users/me", jwt).statusCode()).isEqualTo(401);

        // Password reset must NEVER reactivate a deactivated user
        assertThat(postJson("/api/v1/auth/forgot-password", Map.of("email", userEmail), null).statusCode())
                .isEqualTo(200);

        // Login returns RESTORE_REQUIRED with ticket, NO tokens, ticket rejected by bearer routes
        HttpResponse<String> restoreLogin = postJson("/api/v1/auth/login",
                Map.of("email", userEmail, "password", "Pass!12345"), null);
        assertThat(restoreLogin.statusCode()).isEqualTo(200);
        JsonNode restoreData = MAPPER.readTree(restoreLogin.body()).path("data");
        assertThat(restoreData.path("outcome").asText()).isEqualTo("RESTORE_REQUIRED");
        assertThat(restoreData.path("token").isMissingNode() || restoreData.path("token").isNull()).isTrue();
        String ticket = restoreData.path("restoreTicket").asText();
        assertThat(get("/api/v1/users/me", ticket).statusCode()).isEqualTo(401);

        // Concurrent restore/confirm calls -> single winner
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> r1 = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return postJson("/api/v1/auth/restore/confirm", Map.of("ticket", ticket), null).statusCode();
            });
            Future<Integer> r2 = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return postJson("/api/v1/auth/restore/confirm", Map.of("ticket", ticket), null).statusCode();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> codes = List.of(r1.get(10, TimeUnit.SECONDS), r2.get(10, TimeUnit.SECONDS));
            assertThat(codes).containsExactlyInAnyOrder(200, 400);
        }

        // Now self-deactivate again, admin suspends concurrently BEFORE restore confirm -> restore confirm fails closed!
        String restoredJwt = login(userEmail, "Pass!12345");
        assertThat(postJson("/api/v1/users/me/self-deactivate",
                Map.of("confirmation", "DEACTIVATE", "currentPassword", "Pass!12345"), restoredJwt).statusCode())
                .isEqualTo(200);
        String ticket2 = MAPPER.readTree(postJson("/api/v1/auth/login",
                Map.of("email", userEmail, "password", "Pass!12345"), null).body())
                .path("data").path("restoreTicket").asText();
        assertThat(postJson("/api/v1/admin/users/" + user.getId() + "/suspend", Map.of(), adminJwt).statusCode())
                .isEqualTo(200);

        HttpResponse<String> blockedRestore = postJson("/api/v1/auth/restore/confirm", Map.of("ticket", ticket2), null);
        assertThat(blockedRestore.statusCode()).isIn(400, 403);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getAccountState())
                .isEqualTo(AccountLifecycle.ADMIN_SUSPENDED);
    }

    @Test
    void legacyFixtureRehearsalEnforcesConservativeLegacyDisabledAndVerificationFailClosed() throws Exception {
        String legacyDeletedEmail = "_deleted_99_old@example.com";
        UserEntity legacyRow = userRepository.saveAndFlush(UserEntity.builder()
                .email(legacyDeletedEmail)
                .fullName("Legacy Disabled Fixture")
                .password(passwordEncoder.encode("Pass!12345"))
                .role(UserRole.USER)
                .status(UserStatus.DEACTIVATED)
                .accountState(AccountLifecycle.LEGACY_DISABLED)
                .emailVerified(false)
                .legacyReviewRequired(true)
                .tokenVersion(0L)
                .build());

        assertThat(postJson("/api/v1/auth/login",
                Map.of("email", legacyDeletedEmail, "password", "Pass!12345"), null).statusCode())
                .isEqualTo(403);

        // Admin cannot blindly reactivate an unverified legacy fixture
        String adminEmail = "fixture-adm-" + UUID.randomUUID() + "@example.com";
        createVerifiedUser(adminEmail, "Pass!12345", "Fixture Admin", UserRole.ADMIN);
        String adminJwt = login(adminEmail, "Pass!12345");
        assertThat(postJson("/api/v1/admin/users/" + legacyRow.getId() + "/reactivate", Map.of(), adminJwt).statusCode())
                .isEqualTo(409);
    }

    @Test
    void restoreCancelAndDisabledAccountResetAndRefreshFailClosed() throws Exception {
        String adminEmail = "rc-adm-" + UUID.randomUUID() + "@example.com";
        String userEmail = "rc-usr-" + UUID.randomUUID() + "@example.com";
        createVerifiedUser(adminEmail, "Pass!12345", "Restore Cancel Admin", UserRole.ADMIN);
        UserEntity user = createVerifiedUser(userEmail, "Pass!12345", "Restore Cancel User", UserRole.USER);
        String adminJwt = login(adminEmail, "Pass!12345");

        JsonNode loginData = MAPPER.readTree(
                postJson("/api/v1/auth/login", Map.of("email", userEmail, "password", "Pass!12345"), null).body()
        ).path("data");
        String userJwt = loginData.path("token").asText();
        String refreshToken = loginData.path("refreshToken").asText();

        // Self-deactivate revokes refresh token immediately and returns RESTORE_REQUIRED ticket on login
        assertThat(postJson("/api/v1/users/me/self-deactivate",
                Map.of("confirmation", "DEACTIVATE", "currentPassword", "Pass!12345"), userJwt).statusCode())
                .isEqualTo(200);
        assertThat(postJson("/api/v1/auth/refresh", Map.of("refreshToken", refreshToken), null).statusCode())
                .isIn(401, 403);

        String cancelTicket = MAPPER.readTree(
                postJson("/api/v1/auth/login", Map.of("email", userEmail, "password", "Pass!12345"), null).body()
        ).path("data").path("restoreTicket").asText();

        assertThat(postJson("/api/v1/auth/restore/cancel", Map.of("ticket", cancelTicket), null).statusCode())
                .isEqualTo(200);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getAccountState())
                .isEqualTo(AccountLifecycle.SELF_DEACTIVATED);
        assertThat(postJson("/api/v1/auth/restore/confirm", Map.of("ticket", cancelTicket), null).statusCode())
                .isIn(400, 403);

        // Even if a PASSWORD_RESET token exists before admin suspension, reset-password must NOT activate suspended account
        UserEntity lockedActive = userRepository.findById(user.getId()).orElseThrow();
        String resetRaw = emailChallengeService.issueForLockedUser(
                lockedActive, EmailTokenPurpose.PASSWORD_RESET, 60_000L, null).rawToken();
        assertThat(postJson("/api/v1/admin/users/" + user.getId() + "/suspend", Map.of(), adminJwt).statusCode())
                .isEqualTo(200);
        assertThat(postJson("/api/v1/auth/reset-password",
                Map.of("token", resetRaw, "newPassword", "SuspendedBypass1!"), null).statusCode())
                .isIn(400, 403);
        assertThat(postJson("/api/v1/auth/refresh", Map.of("refreshToken", refreshToken), null).statusCode())
                .isIn(401, 403);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getAccountState())
                .isEqualTo(AccountLifecycle.ADMIN_SUSPENDED);
    }

    @Test
    void oauth2ResolveAndExplicitLinkEnforceCollisionRestoreAndNegativeProofChecks() throws Exception {
        String email = "oauth-link-" + UUID.randomUUID() + "@example.com";
        String otherEmail = "oauth-other-" + UUID.randomUUID() + "@example.com";
        UserEntity user = createVerifiedUser(email, "Pass!12345", "Primary User", UserRole.USER);
        UserEntity other = createVerifiedUser(otherEmail, "Pass!12345", "Other User", UserRole.USER);
        String googleSub = "g-sub-" + UUID.randomUUID();
        OAuth2UserInfo googleInfo = new OAuth2UserInfo(
                AuthProvider.GOOGLE, googleSub, email, true, "Primary User", null);

        // Unlinked same-email OAuth2 callback must NOT auto-merge; yields EmailCollision
        OAuth2AccountService.Resolution collision = oAuth2AccountService.resolveLoginOrRegister(googleInfo);
        assertThat(collision).isInstanceOf(OAuth2AccountService.Resolution.EmailCollision.class);
        assertThat(userIdentityRepository.findByUserId(user.getId())).isEmpty();

        // Explicit link flow with valid password + link intent proof
        var linkResp = credentialLinkService.beginLink(user.getId(), "google", "Pass!12345", null);
        String linkHash = credentialLinkService.verifyIntent(Purpose.LINK, "google", linkResp.linkToken()).tokenHash();

        // Wrong user or wrong provider fails closed WITHOUT consuming link intent
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        credentialLinkService.completeLink(other.getId(), linkHash, googleInfo))
                .isInstanceOf(BusinessException.class);
        OAuth2UserInfo wrongProviderInfo = new OAuth2UserInfo(
                AuthProvider.GITHUB, "gh-" + UUID.randomUUID(), email, true, "Primary User", null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        credentialLinkService.completeLink(user.getId(), linkHash, wrongProviderInfo))
                .isInstanceOf(BusinessException.class);

        credentialLinkService.completeLink(user.getId(), linkHash, googleInfo);
        assertThat(userIdentityRepository.existsByUserIdAndProvider(user.getId(), AuthProvider.GOOGLE)).isTrue();

        // Replay of consumed link token fails closed
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        credentialLinkService.completeLink(user.getId(), linkHash, googleInfo))
                .isInstanceOf(BusinessException.class);

        // Mismatched provider subject on re-auth fails closed
        var reauthResp = credentialLinkService.beginReauth(user.getId(), "google");
        String reauthHash = credentialLinkService.verifyIntent(Purpose.REAUTH, "google", reauthResp.linkToken()).tokenHash();
        OAuth2UserInfo wrongSubject = new OAuth2UserInfo(
                AuthProvider.GOOGLE, "wrong-sub-" + UUID.randomUUID(), email, true, "Primary", null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        credentialLinkService.completeReauth(user.getId(), reauthHash, wrongSubject))
                .isInstanceOf(BusinessException.class);

        // Linked self-deactivated user resolves to RestoreRequired with ticket
        String jwt = login(email, "Pass!12345");
        assertThat(postJson("/api/v1/users/me/self-deactivate",
                Map.of("confirmation", "DEACTIVATE", "currentPassword", "Pass!12345"), jwt).statusCode())
                .isEqualTo(200);
        OAuth2AccountService.Resolution restoreRes = oAuth2AccountService.resolveLoginOrRegister(googleInfo);
        assertThat(restoreRes).isInstanceOfSatisfying(OAuth2AccountService.Resolution.RestoreRequired.class,
                r -> assertThat(r.restoreTicket()).isNotBlank());
    }

    @Test
    void autoAssignmentFiltersLifecycleDisabledButPreservesActiveOooSeparation() throws Exception {
        String activeEmail = "aa-act-" + UUID.randomUUID() + "@example.com";
        String oooEmail = "aa-ooo-" + UUID.randomUUID() + "@example.com";
        String deactEmail = "aa-dec-" + UUID.randomUUID() + "@example.com";

        UserEntity active = createVerifiedUser(activeEmail, "Pass!12345", "Active User", UserRole.USER);
        UserEntity ooo = createVerifiedUser(oooEmail, "Pass!12345", "OOO User", UserRole.USER);
        UserEntity deact = createVerifiedUser(deactEmail, "Pass!12345", "Deact User", UserRole.USER);

        String activeJwt = login(activeEmail, "Pass!12345");
        String oooJwt = login(oooEmail, "Pass!12345");
        String deactJwt = login(deactEmail, "Pass!12345");

        ProjectEntity project = projectRepository.saveAndFlush(
                ProjectEntity.builder().name("AutoAssign Separation").description("d").build());
        projectMemberRepository.saveAndFlush(ProjectMemberEntity.builder()
                .projectId(project.getId()).userId(active.getId()).role(MemberRole.MANAGER).build());
        projectMemberRepository.saveAndFlush(ProjectMemberEntity.builder()
                .projectId(project.getId()).userId(ooo.getId()).role(MemberRole.MEMBER).build());
        projectMemberRepository.saveAndFlush(ProjectMemberEntity.builder()
                .projectId(project.getId()).userId(deact.getId()).role(MemberRole.MEMBER).build());

        assertThat(putJson("/api/v1/users/me/availability", Map.of("availability", "OOO"), oooJwt).statusCode())
                .isEqualTo(200);
        assertThat(postJson("/api/v1/users/me/self-deactivate",
                Map.of("confirmation", "DEACTIVATE", "currentPassword", "Pass!12345"), deactJwt).statusCode())
                .isEqualTo(200);

        // Both OOO (active lifecycle) and SELF_DEACTIVATED excluded initially
        assertThat(autoAssignmentService.recommendCandidates(project.getId(), List.of(), 2, active.getId()).candidates())
                .extracting(CandidateScore::getUserId)
                .containsExactly(active.getId());

        // Returning from OOO -> AVAILABLE restores eligibility without changing lifecycle
        assertThat(putJson("/api/v1/users/me/availability", Map.of("availability", "AVAILABLE"), oooJwt).statusCode())
                .isEqualTo(200);
        assertThat(autoAssignmentService.recommendCandidates(project.getId(), List.of(), 2, active.getId()).candidates())
                .extracting(CandidateScore::getUserId)
                .containsExactlyInAnyOrder(active.getId(), ooo.getId());
    }

    private UserEntity createVerifiedUser(String email, String rawPassword, String fullName, UserRole role) throws Exception {
        HttpResponse<String> reg = postJson("/api/v1/auth/register",
                Map.of("email", email, "password", rawPassword, "fullName", fullName), null);
        assertThat(reg.statusCode()).isEqualTo(201);
        UserEntity user = userRepository.findByEmail(email.toLowerCase(Locale.ROOT)).orElseThrow();
        user.setEmailVerified(true);
        user.setAccountState(AccountLifecycle.ACTIVE);
        user.setRole(role);
        user.setPassword(passwordEncoder.encode(rawPassword));
        return userRepository.saveAndFlush(user);
    }

    private String login(String email, String password) throws Exception {
        HttpResponse<String> resp = postJson("/api/v1/auth/login", Map.of("email", email, "password", password), null);
        assertThat(resp.statusCode()).isEqualTo(200);
        return MAPPER.readTree(resp.body()).path("data").path("token").asText();
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
                "--rag.embedding.poll-interval-ms=86400000",
                "--decorator.datasource.p6spy.enable-logging=false",
                "--logging.level.root=WARN"
        };
    }
}
