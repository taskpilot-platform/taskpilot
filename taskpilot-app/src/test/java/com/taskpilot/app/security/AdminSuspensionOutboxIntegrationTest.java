package com.taskpilot.app.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.app.TaskPilotApplication;
import com.taskpilot.app.migration.MigrationDatabaseConfiguration;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.ProjectStatus;
import com.taskpilot.projects.common.repository.ProjectManagementAttentionAuditRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.common.repository.UserProjectReconciliationStateRepository;
import com.taskpilot.users.auth.service.UserLifecycleRecoveryOutboxDispatcher;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.entity.UserLifecycleRecoveryOutboxEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.repository.UserLifecycleRecoveryOutboxRepository;
import com.taskpilot.users.common.repository.UserRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AdminSuspensionOutboxIntegrationTest {

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
    private static ProjectRepository projectRepository;
    private static ProjectMemberRepository memberRepository;
    private static UserLifecycleRecoveryOutboxRepository outboxRepository;
    private static UserLifecycleRecoveryOutboxDispatcher outboxDispatcher;
    private static UserProjectReconciliationStateRepository reconciliationRepository;
    private static ProjectManagementAttentionAuditRepository attentionAuditRepository;

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
        projectRepository = context.getBean(ProjectRepository.class);
        memberRepository = context.getBean(ProjectMemberRepository.class);
        outboxRepository = context.getBean(UserLifecycleRecoveryOutboxRepository.class);
        outboxDispatcher = context.getBean(UserLifecycleRecoveryOutboxDispatcher.class);
        reconciliationRepository = context.getBean(UserProjectReconciliationStateRepository.class);
        attentionAuditRepository = context.getBean(ProjectManagementAttentionAuditRepository.class);
    }

    @AfterAll
    static void stopApp() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void adminSuspensionCommitsImmediatelyReconcilesIdempotentlyAndSupportsExplicitTransfer() throws Exception {
        String adminEmail = "adm-" + UUID.randomUUID() + "@example.com";
        String soleMgrEmail = "mgr-" + UUID.randomUUID() + "@example.com";
        String memberEmail = "mem-" + UUID.randomUUID() + "@example.com";

        createVerifiedUser(adminEmail, "Pass!12345", "Admin", UserRole.ADMIN);
        UserEntity soleMgr = createVerifiedUser(soleMgrEmail, "Pass!12345", "Sole Manager", UserRole.USER);
        UserEntity member = createVerifiedUser(memberEmail, "Pass!12345", "Eligible Member", UserRole.USER);

        String adminJwt = login(adminEmail, "Pass!12345");
        String mgrJwt = login(soleMgrEmail, "Pass!12345");
        String memberJwt = login(memberEmail, "Pass!12345");

        long projectId = MAPPER.readTree(
                postJson("/api/v1/projects", Map.of("name", "Managerless Recovery Project"), mgrJwt).body()
        ).path("data").path("id").asLong();

        String inviteToken = MAPPER.readTree(
                postJson("/api/v1/projects/" + projectId + "/invitations", Map.of("email", memberEmail), mgrJwt).body()
        ).path("data").path("inviteToken").asText();
        assertThat(postJson("/api/v1/projects/invitations/accept", Map.of("token", inviteToken), memberJwt).statusCode())
                .isEqualTo(200);

        // Admin suspends sole manager immediately
        HttpResponse<String> suspendResp = postJson(
                "/api/v1/admin/users/" + soleMgr.getId() + "/suspend?reason=policy_breach", Map.of(), adminJwt);
        assertThat(suspendResp.statusCode()).isEqualTo(200);

        // Old session fails closed immediately; cannot self-restore
        assertThat(get("/api/v1/users/me", mgrJwt).statusCode()).isEqualTo(401);
        assertThat(postJson("/api/v1/auth/login", Map.of("email", soleMgrEmail, "password", "Pass!12345"), null).statusCode())
                .isEqualTo(403);

        // Project must NOT be silently archived and Member must NOT be silently auto-promoted
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(project.getNeedsManagerAttention()).isTrue();
        assertThat(project.getManagementAttentionReason()).isEqualTo("NO_ACTIVE_MANAGER");
        assertThat(memberRepository.findByProjectIdAndUserId(projectId, member.getId()).orElseThrow().getRole())
                .isEqualTo(MemberRole.MEMBER);

        // Verify outbox reconciled & stale-version outbox replay is ignored safely
        var state = reconciliationRepository.findById(soleMgr.getId()).orElseThrow();
        assertThat(state.getLastReconciledTokenVersion()).isGreaterThanOrEqualTo(1L);

        UUID staleEventId = UUID.randomUUID();
        outboxRepository.saveAndFlush(UserLifecycleRecoveryOutboxEntity.builder()
                .eventId(staleEventId)
                .userId(soleMgr.getId())
                .tokenVersion(0L)
                .lifecycle("ADMIN_SUSPENDED")
                .occurredAt(Instant.now().minusSeconds(60))
                .status(UserLifecycleRecoveryOutboxEntity.STATUS_PENDING)
                .attemptCount(0)
                .nextAttemptAt(Instant.now().minusSeconds(1))
                .createdAt(Instant.now())
                .build());
        outboxDispatcher.dispatchSingle(staleEventId);
        assertThat(outboxRepository.findById(staleEventId).orElseThrow().getStatus())
                .isEqualTo(UserLifecycleRecoveryOutboxEntity.STATUS_RECONCILED);

        // Admin lists projects needing attention and explicitly transfers management to member
        HttpResponse<String> attentionResp = get("/api/v1/admin/projects/attention-required", adminJwt);
        assertThat(attentionResp.statusCode()).isEqualTo(200);
        JsonNode arr = MAPPER.readTree(attentionResp.body()).path("data");
        assertThat(arr.toString()).contains(String.valueOf(projectId)).contains(memberEmail);

        HttpResponse<String> transferResp = postJson(
                "/api/v1/admin/projects/" + projectId + "/transfer-manager",
                Map.of("targetUserId", member.getId(), "reason", "Explicit admin handoff"),
                adminJwt);
        assertThat(transferResp.statusCode()).isEqualTo(200);

        ProjectEntity resolvedProject = projectRepository.findById(projectId).orElseThrow();
        assertThat(resolvedProject.getNeedsManagerAttention()).isFalse();
        assertThat(memberRepository.findByProjectIdAndUserId(projectId, member.getId()).orElseThrow().getRole())
                .isEqualTo(MemberRole.MANAGER);
        assertThat(attentionAuditRepository.findAll().stream()
                .filter(a -> projectId == a.getProjectId() && "MANAGER_TRANSFERRED".equals(a.getEventType()))
                .count()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void adminSuspensionCommitsImmediatelyWhileProjectRowIsLockedAndFailedOutboxRowRetriesCleanly() throws Exception {
        String adminEmail = "adm-lock-" + UUID.randomUUID() + "@example.com";
        String soleMgrEmail = "mgr-lock-" + UUID.randomUUID() + "@example.com";

        createVerifiedUser(adminEmail, "Pass!12345", "Admin Locker", UserRole.ADMIN);
        UserEntity soleMgr = createVerifiedUser(soleMgrEmail, "Pass!12345", "Locked Project Manager", UserRole.USER);
        String adminJwt = login(adminEmail, "Pass!12345");
        String mgrJwt = login(soleMgrEmail, "Pass!12345");

        long projectId = MAPPER.readTree(
                postJson("/api/v1/projects", Map.of("name", "Locked Project During Suspend"), mgrJwt).body()
        ).path("data").path("id").asLong();

        try (Connection locker = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Connection observer = DriverManager.getConnection(
                     postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             ExecutorService pool = Executors.newSingleThreadExecutor()) {
            locker.setAutoCommit(false);
            try (Statement lockStmt = locker.createStatement()) {
                lockStmt.executeQuery("SELECT id FROM public.projects WHERE id = " + projectId + " FOR UPDATE").close();
            }

            Future<HttpResponse<String>> suspendFuture = pool.submit(() -> postJson(
                    "/api/v1/admin/users/" + soleMgr.getId() + "/suspend?reason=immediate_commit_check",
                    Map.of(), adminJwt));

            // Poll separate JDBC connection WHILE locker still holds projects(projectId) FOR UPDATE:
            // suspension commit (users.account_state, token_version, outbox PENDING) must already be visible!
            long deadline = System.currentTimeMillis() + 8000L;
            boolean observedCommittedWhileLocked = false;
            while (System.currentTimeMillis() < deadline) {
                try (PreparedStatement ps = observer.prepareStatement(
                        "SELECT u.account_state, u.token_version, " +
                                "(SELECT count(*) FROM public.user_lifecycle_recovery_outbox o " +
                                " WHERE o.user_id = u.id AND o.lifecycle = 'ADMIN_SUSPENDED') AS outbox_cnt " +
                                "FROM public.users u WHERE u.id = ?")) {
                    ps.setLong(1, soleMgr.getId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()
                                && "ADMIN_SUSPENDED".equals(rs.getString("account_state"))
                                && rs.getLong("token_version") >= 1L
                                && rs.getInt("outbox_cnt") >= 1) {
                            observedCommittedWhileLocked = true;
                            break;
                        }
                    }
                }
                Thread.sleep(25);
            }
            assertThat(observedCommittedWhileLocked).isTrue();
            // Old manager JWT fails closed immediately EVEN BEFORE locker releases the project row lock.
            assertThat(get("/api/v1/users/me", mgrJwt).statusCode()).isEqualTo(401);

            // Release the project row lock so post-commit recovery finishes cleanly.
            locker.commit();
            HttpResponse<String> suspendResp = suspendFuture.get(15, TimeUnit.SECONDS);
            assertThat(suspendResp.statusCode()).isEqualTo(200);
        }

        ProjectEntity recoveredProject = projectRepository.findById(projectId).orElseThrow();
        assertThat(recoveredProject.getNeedsManagerAttention()).isTrue();
        assertThat(recoveredProject.getManagementAttentionReason()).isEqualTo("NO_ACTIVE_MANAGER");

        // Durable retry recovery: simulate authoritative tokenVersion advance where initial immediate
        // delivery failed (attemptCount=1, lastError set, due nextAttemptAt) and verify dispatchPendingBatch() recovers it.
        UserEntity updatedSoleMgr = userRepository.findById(soleMgr.getId()).orElseThrow();
        long higherVersion = updatedSoleMgr.getTokenVersion() + 1L;
        updatedSoleMgr.setTokenVersion(higherVersion);
        userRepository.saveAndFlush(updatedSoleMgr);
        UUID failedEventId = UUID.randomUUID();
        outboxRepository.saveAndFlush(UserLifecycleRecoveryOutboxEntity.builder()
                .eventId(failedEventId)
                .userId(soleMgr.getId())
                .tokenVersion(higherVersion)
                .lifecycle("ADMIN_SUSPENDED")
                .occurredAt(Instant.now())
                .status(UserLifecycleRecoveryOutboxEntity.STATUS_PENDING)
                .attemptCount(1)
                .lastError("simulated transient project lock timeout")
                .nextAttemptAt(Instant.now().minusSeconds(5))
                .createdAt(Instant.now().minusSeconds(10))
                .build());

        int dispatched = outboxDispatcher.dispatchPendingBatch();
        assertThat(dispatched).isGreaterThanOrEqualTo(1);
        UserLifecycleRecoveryOutboxEntity retried = outboxRepository.findById(failedEventId).orElseThrow();
        assertThat(retried.getStatus()).isEqualTo(UserLifecycleRecoveryOutboxEntity.STATUS_RECONCILED);
        assertThat(retried.getAttemptCount()).isEqualTo(1);
        assertThat(reconciliationRepository.findById(soleMgr.getId()).orElseThrow().getLastReconciledTokenVersion())
                .isEqualTo(higherVersion);
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
