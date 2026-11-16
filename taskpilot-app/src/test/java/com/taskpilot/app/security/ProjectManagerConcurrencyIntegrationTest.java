package com.taskpilot.app.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.ai.dto.AutoAssignmentResponse;
import com.taskpilot.ai.dto.CandidateScore;
import com.taskpilot.ai.service.AutoAssignmentService;
import com.taskpilot.app.TaskPilotApplication;
import com.taskpilot.app.migration.MigrationDatabaseConfiguration;
import com.taskpilot.contracts.project.exception.SoleActiveManagerException;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.users.auth.service.SelfDeactivationCoordinator;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.UserStatus;
import com.taskpilot.users.common.repository.UserRepository;
import com.taskpilot.users.profile.dto.SelfDeactivateRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ProjectManagerConcurrencyIntegrationTest {

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
    private static ProjectRepository projectRepository;
    private static ProjectMemberRepository memberRepository;
    private static PasswordEncoder passwordEncoder;
    private static SelfDeactivationCoordinator selfDeactivationCoordinator;
    private static PlatformTransactionManager transactionManager;
    private static AutoAssignmentService autoAssignmentService;

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
        projectRepository = context.getBean(ProjectRepository.class);
        memberRepository = context.getBean(ProjectMemberRepository.class);
        passwordEncoder = context.getBean(PasswordEncoder.class);
        selfDeactivationCoordinator = context.getBean(SelfDeactivationCoordinator.class);
        transactionManager = context.getBean(PlatformTransactionManager.class);
        autoAssignmentService = context.getBean(AutoAssignmentService.class);
    }

    @AfterAll
    static void stopApp() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void concurrentLastManagerDemotionsOrLeaveNeverLeaveZeroActiveManagers() throws Exception {
        String email1 = "m1-" + UUID.randomUUID() + "@example.com";
        String email2 = "m2-" + UUID.randomUUID() + "@example.com";
        UserEntity m1 = createVerifiedUser(email1, "Pass!12345", "Manager One");
        UserEntity m2 = createVerifiedUser(email2, "Pass!12345", "Manager Two");
        String jwt1 = login(email1, "Pass!12345");
        String jwt2 = login(email2, "Pass!12345");

        HttpResponse<String> createProj = postJson("/api/v1/projects", Map.of("name", "Concurrent Managers"), jwt1);
        assertThat(createProj.statusCode()).isEqualTo(201);
        long projectId = MAPPER.readTree(createProj.body()).path("data").path("id").asLong();

        String inviteToken = MAPPER.readTree(
                postJson("/api/v1/projects/" + projectId + "/invitations", Map.of("email", email2), jwt1).body()
        ).path("data").path("inviteToken").asText();
        assertThat(postJson("/api/v1/projects/invitations/accept", Map.of("token", inviteToken), jwt2).statusCode())
                .isEqualTo(200);
        assertThat(putJson("/api/v1/projects/" + projectId + "/members/" + m2.getId() + "/role",
                Map.of("role", "MANAGER"), jwt1).statusCode()).isEqualTo(200);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> f1 = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return delete("/api/v1/projects/" + projectId + "/leave", jwt1).statusCode();
            });
            Future<Integer> f2 = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return delete("/api/v1/projects/" + projectId + "/leave", jwt2).statusCode();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Integer> codes = List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS));
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
        }

        long remainingManagers = memberRepository.findMembers(projectId).stream()
                .filter(m -> m.getRole() == MemberRole.MANAGER)
                .count();
        assertThat(remainingManagers).isEqualTo(1L);
    }

    @Test
    void soleActiveManagerSelfDeactivateReturns409AndKeepsUserActive() throws Exception {
        String email = "sole-" + UUID.randomUUID() + "@example.com";
        createVerifiedUser(email, "Pass!12345", "Sole Manager");
        String jwt = login(email, "Pass!12345");

        long projectId = MAPPER.readTree(
                postJson("/api/v1/projects", Map.of("name", "Sole Owned"), jwt).body()
        ).path("data").path("id").asLong();

        HttpResponse<String> resp = postJson("/api/v1/users/me/self-deactivate",
                Map.of("confirmation", "DEACTIVATE", "currentPassword", "Pass!12345"), jwt);
        assertThat(resp.statusCode()).isEqualTo(409);
        JsonNode blocking = MAPPER.readTree(resp.body()).path("data").path("blockingProjectIds");
        assertThat(blocking.toString()).contains(String.valueOf(projectId));

        assertThat(get("/api/v1/users/me", jwt).statusCode()).isEqualTo(200);
    }

    @Test
    void selfDeactivationCoordinatorRollsBackAndRetriesInFreshTxWhenNewProjectAppearsMidAttempt() throws Exception {
        String email = "retry-" + UUID.randomUUID() + "@example.com";
        UserEntity user = createVerifiedUser(email, "Pass!12345", "Retry Coordinator User");
        String jwt = login(email, "Pass!12345");

        // Hold FOR UPDATE lock on the user row in a separate JDBC transaction, insert & commit a project while
        // Attempt 1 waits on users lock (with empty candidateProjectIds=[]), forcing RetryableRosterChangeException
        // -> clean rollback -> fresh Attempt 2 with expanded project lock set -> deterministic SoleActiveManagerException 409.
        final long[] createdProjectId = new long[1];
        CountDownLatch txLocked = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<?> bg = pool.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    userRepository.findByIdForUpdate(user.getId()).orElseThrow();
                    txLocked.countDown();
                    try {
                        Thread.sleep(350);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    ProjectEntity created = projectRepository.saveAndFlush(
                            ProjectEntity.builder().name("Race Project").description("d").build());
                    memberRepository.saveAndFlush(ProjectMemberEntity.builder()
                            .projectId(created.getId())
                            .userId(user.getId())
                            .role(MemberRole.MANAGER)
                            .build());
                    createdProjectId[0] = created.getId();
                });
            });

            assertThat(txLocked.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> selfDeactivationCoordinator.selfDeactivate(
                    user.getId(), new SelfDeactivateRequest("DEACTIVATE", "Pass!12345", null)))
                    .isInstanceOfSatisfying(SoleActiveManagerException.class,
                            ex -> assertThat(ex.getBlockingProjectIds()).containsExactly(createdProjectId[0]));

            bg.get(10, TimeUnit.SECONDS);
        }

        UserEntity persisted = userRepository.findById(user.getId()).orElseThrow();
        assertThat(persisted.getAccountState()).isEqualTo(AccountLifecycle.ACTIVE);
        assertThat(get("/api/v1/users/me", jwt).statusCode()).isEqualTo(200);
    }

    @Test
    void unverifiedActiveManagerDoesNotCountForManagerInvariantPromotionOrAutoAssignment() throws Exception {
        String verifiedMgrEmail = "vmgr-" + UUID.randomUUID() + "@example.com";
        String unverifiedMgrEmail = "uvmgr-" + UUID.randomUUID() + "@example.com";
        String unverifiedMemEmail = "uvmem-" + UUID.randomUUID() + "@example.com";
        String oooMemEmail = "ooomem-" + UUID.randomUUID() + "@example.com";

        UserEntity verifiedMgr = createVerifiedUser(verifiedMgrEmail, "Pass!12345", "Verified Manager");
        UserEntity unverifiedMgr = createVerifiedUser(unverifiedMgrEmail, "Pass!12345", "Unverified Manager");
        UserEntity unverifiedMem = createVerifiedUser(unverifiedMemEmail, "Pass!12345", "Unverified Member");
        UserEntity oooMem = createVerifiedUser(oooMemEmail, "Pass!12345", "OOO Member");

        String verifiedJwt = login(verifiedMgrEmail, "Pass!12345");
        long projectId = MAPPER.readTree(
                postJson("/api/v1/projects", Map.of("name", "Verified Manager Invariant"), verifiedJwt).body()
        ).path("data").path("id").asLong();

        memberRepository.saveAndFlush(ProjectMemberEntity.builder()
                .projectId(projectId).userId(unverifiedMgr.getId()).role(MemberRole.MANAGER).build());
        memberRepository.saveAndFlush(ProjectMemberEntity.builder()
                .projectId(projectId).userId(unverifiedMem.getId()).role(MemberRole.MEMBER).build());
        memberRepository.saveAndFlush(ProjectMemberEntity.builder()
                .projectId(projectId).userId(oooMem.getId()).role(MemberRole.MEMBER).build());

        // Transition second manager & member to unverified ACTIVE, and fourth member to OOO.
        unverifiedMgr.setEmailVerified(false);
        unverifiedMgr.setAccountState(AccountLifecycle.ACTIVE);
        userRepository.saveAndFlush(unverifiedMgr);

        unverifiedMem.setEmailVerified(false);
        unverifiedMem.setAccountState(AccountLifecycle.ACTIVE);
        userRepository.saveAndFlush(unverifiedMem);

        oooMem.setStatus(UserStatus.OOO);
        userRepository.saveAndFlush(oooMem);

        // Verified manager must STILL be treated as sole effective active manager.
        assertThat(postJson("/api/v1/users/me/self-deactivate",
                Map.of("confirmation", "DEACTIVATE", "currentPassword", "Pass!12345"), verifiedJwt).statusCode())
                .isEqualTo(409);
        assertThat(delete("/api/v1/projects/" + projectId + "/leave", verifiedJwt).statusCode())
                .isEqualTo(409);
        assertThat(putJson("/api/v1/projects/" + projectId + "/members/" + verifiedMgr.getId() + "/role",
                Map.of("role", "MEMBER"), verifiedJwt).statusCode())
                .isEqualTo(409);

        // Promoting an unverified user to MANAGER must fail closed with 409.
        assertThat(putJson("/api/v1/projects/" + projectId + "/members/" + unverifiedMem.getId() + "/role",
                Map.of("role", "MANAGER"), verifiedJwt).statusCode())
                .isEqualTo(409);

        // AutoAssignment excludes unverified ACTIVE users and OOO users, keeping only verified AVAILABLE manager.
        AutoAssignmentResponse candidates = autoAssignmentService.recommendCandidates(
                projectId, List.of(), 3, verifiedMgr.getId());
        assertThat(candidates.candidates())
                .extracting(CandidateScore::getUserId)
                .containsExactly(verifiedMgr.getId());
    }

    @Test
    void concurrentMutualRoleDemotionsNeverLeaveZeroActiveManagers() throws Exception {
        String email1 = "dm1-" + UUID.randomUUID() + "@example.com";
        String email2 = "dm2-" + UUID.randomUUID() + "@example.com";
        UserEntity m1 = createVerifiedUser(email1, "Pass!12345", "Demote One");
        UserEntity m2 = createVerifiedUser(email2, "Pass!12345", "Demote Two");
        String jwt1 = login(email1, "Pass!12345");
        String jwt2 = login(email2, "Pass!12345");

        long projectId = MAPPER.readTree(
                postJson("/api/v1/projects", Map.of("name", "Mutual Demotion"), jwt1).body()
        ).path("data").path("id").asLong();
        String inviteToken = MAPPER.readTree(
                postJson("/api/v1/projects/" + projectId + "/invitations", Map.of("email", email2), jwt1).body()
        ).path("data").path("inviteToken").asText();
        assertThat(postJson("/api/v1/projects/invitations/accept", Map.of("token", inviteToken), jwt2).statusCode())
                .isEqualTo(200);
        assertThat(putJson("/api/v1/projects/" + projectId + "/members/" + m2.getId() + "/role",
                Map.of("role", "MANAGER"), jwt1).statusCode()).isEqualTo(200);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> f1 = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return putJson("/api/v1/projects/" + projectId + "/members/" + m1.getId() + "/role",
                        Map.of("role", "MEMBER"), jwt1).statusCode();
            });
            Future<Integer> f2 = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return putJson("/api/v1/projects/" + projectId + "/members/" + m2.getId() + "/role",
                        Map.of("role", "MEMBER"), jwt2).statusCode();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> codes = List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS));
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
        }

        long remainingManagers = memberRepository.findMembers(projectId).stream()
                .filter(m -> m.getRole() == MemberRole.MANAGER)
                .count();
        assertThat(remainingManagers).isEqualTo(1L);
    }

    @Test
    void concurrentRemoveManagerVsSelfDeactivatePreservesActiveManager() throws Exception {
        String email1 = "rm1-" + UUID.randomUUID() + "@example.com";
        String email2 = "rm2-" + UUID.randomUUID() + "@example.com";
        UserEntity m1 = createVerifiedUser(email1, "Pass!12345", "Remove Target");
        UserEntity m2 = createVerifiedUser(email2, "Pass!12345", "Acting Deactivator");
        String jwt1 = login(email1, "Pass!12345");
        String jwt2 = login(email2, "Pass!12345");

        long projectId = MAPPER.readTree(
                postJson("/api/v1/projects", Map.of("name", "Remove vs Deactivate"), jwt1).body()
        ).path("data").path("id").asLong();
        String inviteToken = MAPPER.readTree(
                postJson("/api/v1/projects/" + projectId + "/invitations", Map.of("email", email2), jwt1).body()
        ).path("data").path("inviteToken").asText();
        assertThat(postJson("/api/v1/projects/invitations/accept", Map.of("token", inviteToken), jwt2).statusCode())
                .isEqualTo(200);
        assertThat(putJson("/api/v1/projects/" + projectId + "/members/" + m2.getId() + "/role",
                Map.of("role", "MANAGER"), jwt1).statusCode()).isEqualTo(200);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        int removeCode;
        int deactivateCode;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> removeFuture = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return delete("/api/v1/projects/" + projectId + "/members/" + m1.getId(), jwt2).statusCode();
            });
            Future<Integer> deactivateFuture = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return postJson("/api/v1/users/me/self-deactivate",
                        Map.of("confirmation", "DEACTIVATE", "currentPassword", "Pass!12345"), jwt2).statusCode();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            removeCode = removeFuture.get(15, TimeUnit.SECONDS);
            deactivateCode = deactivateFuture.get(15, TimeUnit.SECONDS);
        }

        // One operation wins (200) and the other must fail cleanly (401/409); NEVER zero effective active managers.
        assertThat(List.of(removeCode, deactivateCode)).contains(200);
        assertThat(removeCode == 200 && deactivateCode == 200).isFalse();

        long activeManagers = memberRepository.findMembers(projectId).stream()
                .filter(m -> m.getRole() == MemberRole.MANAGER)
                .filter(m -> userRepository.findById(m.getUserId())
                        .map(u -> u.getAccountState() == AccountLifecycle.ACTIVE && Boolean.TRUE.equals(u.getEmailVerified()))
                        .orElse(false))
                .count();
        assertThat(activeManagers).isEqualTo(1L);
    }

    @Test
    void selfDeactivationCoordinatorExhaustsMaxThreeAttemptsWhenRosterChangesEveryAttempt() throws Exception {
        String mgrEmail = "exh-mgr-" + UUID.randomUUID() + "@example.com";
        String userEmail = "exh-usr-" + UUID.randomUUID() + "@example.com";
        UserEntity existingMgr = createVerifiedUser(mgrEmail, "Pass!12345", "Existing Manager");
        UserEntity user = createVerifiedUser(userEmail, "Pass!12345", "Exhaustion Target");

        ProjectEntity p0 = projectRepository.saveAndFlush(ProjectEntity.builder().name("Exh P0").description("d").build());
        ProjectEntity p1 = projectRepository.saveAndFlush(ProjectEntity.builder().name("Exh P1").description("d").build());
        ProjectEntity p2 = projectRepository.saveAndFlush(ProjectEntity.builder().name("Exh P2").description("d").build());
        for (ProjectEntity p : List.of(p0, p1, p2)) {
            memberRepository.saveAndFlush(ProjectMemberEntity.builder()
                    .projectId(p.getId()).userId(existingMgr.getId()).role(MemberRole.MANAGER).build());
            memberRepository.saveAndFlush(ProjectMemberEntity.builder()
                    .projectId(p.getId()).userId(user.getId()).role(MemberRole.MEMBER).build());
        }

        try (Connection conn1 = openTxConnection();
             Connection conn2 = openTxConnection();
             Connection conn3 = openTxConnection();
             Connection observer = DriverManager.getConnection(
                     postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             ExecutorService pool = Executors.newSingleThreadExecutor()) {
            // Lock user in conn1, P0 in conn2, P1 in conn3 so each attempt waits on a distinct deterministic row lock.
            selectForUpdate(conn1, "SELECT id FROM public.users WHERE id = " + user.getId() + " FOR UPDATE");
            selectForUpdate(conn2, "SELECT id FROM public.projects WHERE id = " + p0.getId() + " FOR UPDATE");
            selectForUpdate(conn3, "SELECT id FROM public.projects WHERE id = " + p1.getId() + " FOR UPDATE");

            Future<Throwable> callFuture = pool.submit(() -> {
                try {
                    selfDeactivationCoordinator.selfDeactivate(
                            user.getId(), new SelfDeactivateRequest("DEACTIVATE", "Pass!12345", null));
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });

            // Attempt 1 waits on users(userId) held by conn1 -> promote user in P0 and commit conn1.
            awaitLockWaiter(observer);
            try (Statement st = conn1.createStatement()) {
                st.executeUpdate("UPDATE public.project_members SET role = 'MANAGER' WHERE project_id = "
                        + p0.getId() + " AND user_id = " + user.getId());
            }
            conn1.commit();

            // Attempt 2 waits on projects(P0) held by conn2 -> promote user in P1 and commit conn2.
            awaitLockWaiter(observer);
            try (Statement st = conn2.createStatement()) {
                st.executeUpdate("UPDATE public.project_members SET role = 'MANAGER' WHERE project_id = "
                        + p1.getId() + " AND user_id = " + user.getId());
            }
            conn2.commit();

            // Attempt 3 waits on projects(P1) held by conn3 -> promote user in P2 and commit conn3.
            awaitLockWaiter(observer);
            try (Statement st = conn3.createStatement()) {
                st.executeUpdate("UPDATE public.project_members SET role = 'MANAGER' WHERE project_id = "
                        + p2.getId() + " AND user_id = " + user.getId());
            }
            conn3.commit();

            Throwable thrown = callFuture.get(15, TimeUnit.SECONDS);
            assertThat(thrown)
                    .isInstanceOfSatisfying(BusinessException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(409);
                        assertThat(ex.getMessage()).contains("3 attempts");
                    });
        }

        assertThat(userRepository.findById(user.getId()).orElseThrow().getAccountState())
                .isEqualTo(AccountLifecycle.ACTIVE);
    }

    private Connection openTxConnection() throws Exception {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        c.setAutoCommit(false);
        return c;
    }

    private void selectForUpdate(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeQuery(sql).close();
        }
    }

    private void awaitLockWaiter(Connection observer) throws Exception {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            try (Statement st = observer.createStatement();
                 var rs = st.executeQuery(
                         "SELECT count(*) FROM pg_stat_activity " +
                                 "WHERE datname = current_database() AND state = 'active' " +
                                 "AND wait_event_type = 'Lock' AND pid <> pg_backend_pid()")) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for blocked lock waiter in pg_stat_activity");
    }

    private UserEntity createVerifiedUser(String email, String rawPassword, String fullName) throws Exception {
        HttpResponse<String> reg = postJson("/api/v1/auth/register",
                Map.of("email", email, "password", rawPassword, "fullName", fullName), null);
        assertThat(reg.statusCode()).isEqualTo(201);
        UserEntity user = userRepository.findByEmail(email.toLowerCase(Locale.ROOT)).orElseThrow();
        user.setEmailVerified(true);
        user.setAccountState(AccountLifecycle.ACTIVE);
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

    private HttpResponse<String> delete(String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).DELETE();
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
