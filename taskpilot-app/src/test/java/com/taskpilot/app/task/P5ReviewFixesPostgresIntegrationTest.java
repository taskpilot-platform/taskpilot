package com.taskpilot.app.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.ai.dto.ConfirmationRequiredDto;
import com.taskpilot.ai.service.ChatSessionService;
import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.service.ToolCallingRegistryService;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.app.TaskPilotApplication;
import com.taskpilot.app.migration.MigrationDatabaseConfiguration;
import com.taskpilot.contracts.user.dto.UserLifecycleRecoveryRequestedEvent;
import com.taskpilot.infrastructure.notification.OneSignalService;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.projects.service.ProjectAccountLifecycleAdapter;
import com.taskpilot.projects.projects.service.ProjectServiceImpl;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.enums.UserStatus;
import com.taskpilot.users.common.repository.UserRepository;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * Desired regression tests for reviewed P5-01 findings (R1, R2, R3, R5, R7)
 * on isolated PostgreSQL 17 + Flyway.
 */
@Testcontainers
class P5ReviewFixesPostgresIntegrationTest {

    private static final String TEST_JWT_SECRET = "taskpilot-test-only-p5-review-fixes-jwt-key-2026-32bytes";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("taskpilot")
            .withUsername("test")
            .withPassword("test")
            .withStartupTimeout(Duration.ofSeconds(90));

    private static ConfigurableApplicationContext context;
    private static String baseUrl;
    private static JdbcTemplate jdbc;
    private static ProjectMemberRepository memberRepository;
    private static ProjectAccountLifecycleAdapter lifecycleAdapter;
    private static ProjectServiceImpl projectService;
    private static PendingAiActionService pendingAiActionService;
    private static ToolCallingRegistryService toolCallingRegistryService;
    private static ChatSessionService chatSessionService;

    @BeforeAll
    static void startApp() throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'anon') THEN CREATE ROLE anon NOLOGIN; END IF; END $$;");
            s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'authenticated') THEN CREATE ROLE authenticated NOLOGIN; END IF; END $$;");
            s.execute("ALTER DEFAULT PRIVILEGES GRANT ALL ON TABLES TO anon, authenticated");
        }

        Flyway flyway = MigrationDatabaseConfiguration.configure(
                Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        ).load();
        assertThat(flyway.migrate().migrationsExecuted).isGreaterThanOrEqualTo(6);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        context = new SpringApplicationBuilder(TaskPilotApplication.class, FakePushConfig.class)
                .run(appArgs());
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        baseUrl = "http://127.0.0.1:" + port;

        jdbc = context.getBean(JdbcTemplate.class);
        memberRepository = context.getBean(ProjectMemberRepository.class);
        lifecycleAdapter = context.getBean(ProjectAccountLifecycleAdapter.class);
        projectService = context.getBean(ProjectServiceImpl.class);
        pendingAiActionService = context.getBean(PendingAiActionService.class);
        toolCallingRegistryService = context.getBean(ToolCallingRegistryService.class);
        chatSessionService = context.getBean(ChatSessionService.class);
    }

    @AfterAll
    static void stopApp() {
        if (context != null) {
            context.close();
        }
    }

    record UserFixture(long id, String email, String jwt) {
    }

    private UserFixture createUser() throws Exception {
        String email = "p5r-" + UUID.randomUUID() + "@example.com";
        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setFullName("Review User");
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.AVAILABLE);
        user.setAccountState(AccountLifecycle.ACTIVE);
        user.setPassword(context.getBean(PasswordEncoder.class).encode("Pass!12345"));
        user.setEmailVerified(true);
        user = context.getBean(UserRepository.class).saveAndFlush(user);

        HttpResponse<String> login = send("POST", "/api/v1/auth/login",
                Map.of("email", email, "password", "Pass!12345"), null, Map.of());
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String jwt = MAPPER.readTree(login.body()).path("data").path("token").asText();
        return new UserFixture(user.getId(), email, jwt);
    }

    private long createProject(String jwt, String timezoneId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "P5R " + UUID.randomUUID());
        if (timezoneId != null) {
            body.put("timezoneId", timezoneId);
        }
        HttpResponse<String> resp = send("POST", "/api/v1/projects", body, jwt, Map.of());
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(201);
        return MAPPER.readTree(resp.body()).path("data").path("id").asLong();
    }

    private void addMember(long projectId, long userId, MemberRole role) {
        memberRepository.saveAndFlush(ProjectMemberEntity.builder()
                .projectId(projectId).userId(userId).role(role).build());
    }

    private long createTask(String jwt, long projectId, String title, Long assigneeId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("title", title);
        if (assigneeId != null) {
            body.put("assigneeId", assigneeId);
        }
        HttpResponse<String> resp = send("POST", "/api/v1/tasks", body, jwt,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(201);
        return MAPPER.readTree(resp.body()).path("data").path("id").asLong();
    }

    @Test
    void r1_doneTaskCannotChangeAssigneeDeadlineOrMetadataWithoutReopen_restAndAi() throws Exception {
        UserFixture mgr = createUser();
        UserFixture member = createUser();
        long projectId = createProject(mgr.jwt(), "Asia/Ho_Chi_Minh");
        addMember(projectId, member.id(), MemberRole.MEMBER);

        long doneId = createTask(mgr.jwt(), projectId, "Legacy DONE Task", mgr.id());
        Instant originalDue = Instant.parse("2026-11-10T16:59:59.999999Z");
        jdbc.update("update public.tasks set status = 'DONE', completed_at = now(), due_date = ? where id = ?",
                Timestamp.from(originalDue), doneId);

        long beforeNotifs = jdbc.queryForObject("select count(*) from public.notifications where user_id = ?",
                Long.class, member.id());

        // REST PATCH omitting status, trying to change assigneeId and dueDate on DONE -> 409 REOPEN_TASK
        HttpResponse<String> patchDone = send("PATCH", "/api/v1/tasks/" + doneId,
                Map.of("expectedTaskVersion", 0L, "assigneeId", member.id(), "dueDate", "2026-12-31"),
                mgr.jwt(), Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(patchDone.statusCode()).as(patchDone.body()).isEqualTo(409);
        assertThat(MAPPER.readTree(patchDone.body()).path("data").path("requiredAction").asText())
                .isEqualTo("REOPEN_TASK");

        // REST PATCH changing priority only on DONE -> 409 REOPEN_TASK
        HttpResponse<String> priorityDone = send("PATCH", "/api/v1/tasks/" + doneId,
                Map.of("expectedTaskVersion", 0L, "priority", "URGENT"),
                mgr.jwt(), Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(priorityDone.statusCode()).as(priorityDone.body()).isEqualTo(409);

        // AI patchTask trying to change dueDate on DONE -> rejected 409 and DB unchanged
        long sid = chatSessionService.createSession(mgr.id(), "R1 AI session").getId();
        String aiRaw = ToolExecutionContext.callWith(
                new ToolExecutionContext.Context(mgr.id(), sid, "extend deadline"),
                () -> toolCallingRegistryService.execute(ToolExecutionRequest.builder()
                        .name("patchTask")
                        .arguments(MAPPER.writeValueAsString(Map.of(
                                "taskId", doneId,
                                "expectedTaskVersion", 0L,
                                "patchData", Map.of("dueDate", "2026-12-31", "assigneeId", member.id()),
                                "reason", "R1 bypass test")))
                        .build()));
        JsonNode previewJson = MAPPER.readTree(aiRaw);
        ConfirmationRequiredDto action = pendingAiActionService.describe(
                previewJson.path("actionId").asText(), mgr.id(), sid);
        HttpResponse<String> aiConfirm = send("POST",
                "/api/v1/ai/sessions/" + sid + "/actions/" + action.actionId() + "/confirm",
                Map.of("idempotencyKey", "r1-ai-1", "payloadHash", action.payloadHash()),
                mgr.jwt(), Map.of());
        assertThat(aiConfirm.statusCode()).as(aiConfirm.body()).isEqualTo(409);

        // DB state, version, assignment_version, due_date, assignee_id, and notifications unchanged
        Map<String, Object> storedDone = jdbc.queryForMap(
                "select status::text, assignee_id, due_date, version, assignment_version from public.tasks where id = ?",
                doneId);
        assertThat(storedDone.get("status")).isEqualTo("DONE");
        assertThat(((Number) storedDone.get("assignee_id")).longValue()).isEqualTo(mgr.id());
        assertThat(((Timestamp) storedDone.get("due_date")).toInstant()).isEqualTo(originalDue);
        assertThat(((Number) storedDone.get("version")).longValue()).isEqualTo(0L);
        assertThat(((Number) storedDone.get("assignment_version")).longValue()).isEqualTo(0L);
        assertThat(jdbc.queryForObject("select count(*) from public.notifications where user_id = ?",
                Long.class, member.id())).isEqualTo(beforeNotifs);

        // Positive controls: authorized TODO and IN_PROGRESS metadata changes succeed
        long todoId = createTask(mgr.jwt(), projectId, "Allowed TODO", null);
        HttpResponse<String> todoOk = send("PATCH", "/api/v1/tasks/" + todoId,
                Map.of("expectedTaskVersion", 0L, "assigneeId", member.id(), "dueDate", "2026-12-31"),
                mgr.jwt(), Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(todoOk.statusCode()).as(todoOk.body()).isEqualTo(200);

        HttpResponse<String> startOk = send("PATCH", "/api/v1/tasks/" + todoId + "/kanban",
                Map.of("expectedTaskVersion", 1L, "status", "IN_PROGRESS", "position", 1.0),
                member.jwt(), Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(startOk.statusCode()).as(startOk.body()).isEqualTo(200);

        HttpResponse<String> inProgressEdit = send("PATCH", "/api/v1/tasks/" + todoId,
                Map.of("expectedTaskVersion", 2L, "description", "In-progress metadata ok"),
                mgr.jwt(), Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(inProgressEdit.statusCode()).as(inProgressEdit.body()).isEqualTo(200);
    }

    @Test
    void r2_suspensionAndRemovalLifecycleClearAssigneeBumpsAssignmentVersionAndUpdatedAtWithoutSleep() throws Exception {
        UserFixture mgr = createUser();
        UserFixture suspendedMember = createUser();
        UserFixture removedMember = createUser();
        long projectId = createProject(mgr.jwt(), "Asia/Ho_Chi_Minh");
        addMember(projectId, suspendedMember.id(), MemberRole.MEMBER);
        addMember(projectId, removedMember.id(), MemberRole.MEMBER);

        long suspendedTaskId = createTask(mgr.jwt(), projectId, "Suspended Orphan", suspendedMember.id());
        long removedTaskId = createTask(mgr.jwt(), projectId, "Removed Orphan", removedMember.id());

        Instant earlierTimestamp = Instant.parse("2026-11-01T00:00:00Z");
        jdbc.update("update public.tasks set updated_at = ?, updated_at_legacy = true where id in (?, ?)",
                Timestamp.from(earlierTimestamp), suspendedTaskId, removedTaskId);

        // Admin suspend commits user state first, then lifecycle recovery runs in separate tx
        jdbc.update("update public.users set account_state = 'ADMIN_SUSPENDED', token_version = token_version + 1 where id = ?",
                suspendedMember.id());
        long epoch = jdbc.queryForObject("select token_version from public.users where id = ?", Long.class, suspendedMember.id());
        lifecycleAdapter.reconcileUserLifecycle(new UserLifecycleRecoveryRequestedEvent(
                UUID.randomUUID(), suspendedMember.id(), epoch, "ADMIN_SUSPENDED", Instant.now()));

        Map<String, Object> afterSuspend = jdbc.queryForMap(
                "select assignee_id, assignment_version, version, updated_at, updated_at_legacy from public.tasks where id = ?",
                suspendedTaskId);
        assertThat(afterSuspend.get("assignee_id")).isNull();
        assertThat(((Number) afterSuspend.get("assignment_version")).longValue()).isEqualTo(1L);
        assertThat(((Number) afterSuspend.get("version")).longValue()).isEqualTo(1L);
        assertThat(afterSuspend.get("updated_at_legacy")).isEqualTo(Boolean.FALSE);
        assertThat(((Timestamp) afterSuspend.get("updated_at")).toInstant()).isAfter(earlierTimestamp);

        // Member removal via ProjectServiceImpl also clears assignee, bumps assignment_version, version, and updated_at
        projectService.removeMember(projectId, removedMember.id(), mgr.id());
        Map<String, Object> afterRemove = jdbc.queryForMap(
                "select assignee_id, assignment_version, version, updated_at, updated_at_legacy from public.tasks where id = ?",
                removedTaskId);
        assertThat(afterRemove.get("assignee_id")).isNull();
        assertThat(((Number) afterRemove.get("assignment_version")).longValue()).isEqualTo(1L);
        assertThat(((Number) afterRemove.get("version")).longValue()).isEqualTo(1L);
        assertThat(afterRemove.get("updated_at_legacy")).isEqualTo(Boolean.FALSE);
        assertThat(((Timestamp) afterRemove.get("updated_at")).toInstant()).isAfter(earlierTimestamp);
    }

    @Test
    void r3_deleteIdempotentReplayAfterDeletionEnforcesActorScopePayloadHashAndUnknownKey404() throws Exception {
        UserFixture owner = createUser();
        UserFixture secondMgr = createUser();
        UserFixture outsider = createUser();
        long projectId = createProject(owner.jwt(), "Asia/Ho_Chi_Minh");
        addMember(projectId, secondMgr.id(), MemberRole.MANAGER);

        long taskId = createTask(secondMgr.jwt(), projectId, "Delete Replay Target", null);
        String key = UUID.randomUUID().toString();
        String path = "/api/v1/tasks/" + taskId + "?expectedTaskVersion=0";

        // First DELETE succeeds 200 and writes 1 ledger row
        HttpResponse<String> first = send("DELETE", path, null, secondMgr.jwt(), Map.of("Idempotency-Key", key));
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from public.task_command_idempotency where request_id = ?",
                Long.class, UUID.fromString(key))).isEqualTo(1L);

        // Identical DELETE replay by same authorized actor returns 200 and still 1 ledger row
        HttpResponse<String> replay = send("DELETE", path, null, secondMgr.jwt(), Map.of("Idempotency-Key", key));
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from public.task_command_idempotency where request_id = ?",
                Long.class, UUID.fromString(key))).isEqualTo(1L);

        // Changed body (different expectedTaskVersion or different taskId) with same key -> 409
        HttpResponse<String> changedVersion = send("DELETE", "/api/v1/tasks/" + taskId + "?expectedTaskVersion=1",
                null, secondMgr.jwt(), Map.of("Idempotency-Key", key));
        assertThat(changedVersion.statusCode()).as(changedVersion.body()).isEqualTo(409);

        // Unknown key on deleted task -> 404 (never returns 200 blindly)
        HttpResponse<String> unknownKey = send("DELETE", path, null, secondMgr.jwt(),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(unknownKey.statusCode()).as(unknownKey.body()).isEqualTo(404);

        // Another actor (outsider or even owner) using secondMgr's key on deleted task -> 404
        HttpResponse<String> otherActor = send("DELETE", path, null, outsider.jwt(), Map.of("Idempotency-Key", key));
        assertThat(otherActor.statusCode()).as(otherActor.body()).isEqualTo(404);

        // Removed member replaying their own key after losing membership -> 403
        projectService.removeMember(projectId, secondMgr.id(), owner.id());
        HttpResponse<String> removedReplay = send("DELETE", path, null, secondMgr.jwt(), Map.of("Idempotency-Key", key));
        assertThat(removedReplay.statusCode()).as(removedReplay.body()).isEqualTo(403);
    }

    @Test
    void r5AndR7_requireRequestIdentityOnTaskMutationsAndRejectNonIanaProjectTimezones() throws Exception {
        UserFixture mgr = createUser();

        // R7: Create & update project reject Z and UTC+07:00
        HttpResponse<String> zCreate = send("POST", "/api/v1/projects",
                Map.of("name", "Bad Z " + UUID.randomUUID(), "timezoneId", "Z"), mgr.jwt(), Map.of());
        assertThat(zCreate.statusCode()).as(zCreate.body()).isEqualTo(400);

        HttpResponse<String> offsetCreate = send("POST", "/api/v1/projects",
                Map.of("name", "Bad Offset " + UUID.randomUUID(), "timezoneId", "UTC+07:00"), mgr.jwt(), Map.of());
        assertThat(offsetCreate.statusCode()).as(offsetCreate.body()).isEqualTo(400);

        long validProjectId = createProject(mgr.jwt(), "UTC");
        HttpResponse<String> badUpdateZone = send("PUT", "/api/v1/projects/" + validProjectId,
                Map.of("timezoneId", "UTC+07:00"), mgr.jwt(), Map.of());
        assertThat(badUpdateZone.statusCode()).as(badUpdateZone.body()).isEqualTo(400);

        // R5: Create, patch, kanban, sprint, delete without Idempotency-Key / requestId rejected 400
        HttpResponse<String> createNoKey = send("POST", "/api/v1/tasks",
                Map.of("projectId", validProjectId, "title", "Missing Key"), mgr.jwt(), Map.of());
        assertThat(createNoKey.statusCode()).as(createNoKey.body()).isEqualTo(400);

        long taskId = createTask(mgr.jwt(), validProjectId, "Has Key", mgr.id());
        HttpResponse<String> patchNoKey = send("PATCH", "/api/v1/tasks/" + taskId,
                Map.of("expectedTaskVersion", 0L, "title", "No Key"), mgr.jwt(), Map.of());
        assertThat(patchNoKey.statusCode()).as(patchNoKey.body()).isEqualTo(400);

        HttpResponse<String> kanbanNoKey = send("PATCH", "/api/v1/tasks/" + taskId + "/kanban",
                Map.of("expectedTaskVersion", 0L, "status", "IN_PROGRESS", "position", 1f), mgr.jwt(), Map.of());
        assertThat(kanbanNoKey.statusCode()).as(kanbanNoKey.body()).isEqualTo(400);

        Map<String, Object> sprintBodyNoKey = new LinkedHashMap<>();
        sprintBodyNoKey.put("expectedTaskVersion", 0L);
        sprintBodyNoKey.put("sprintId", null);
        HttpResponse<String> sprintNoKey = send("PATCH", "/api/v1/tasks/" + taskId + "/sprint",
                sprintBodyNoKey, mgr.jwt(), Map.of());
        assertThat(sprintNoKey.statusCode()).as(sprintNoKey.body()).isEqualTo(400);

        HttpResponse<String> deleteNoKey = send("DELETE", "/api/v1/tasks/" + taskId + "?expectedTaskVersion=0",
                null, mgr.jwt(), Map.of());
        assertThat(deleteNoKey.statusCode()).as(deleteNoKey.body()).isEqualTo(400);
    }

    private static HttpResponse<String> send(
            String method,
            String path,
            Object body,
            String token,
            Map<String, String> extraHeaders) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, publisher);
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String[] appArgs() {
        return new String[]{
                "--server.port=0",
                "--spring.jmx.enabled=false",
                "--spring.profiles.active=dev",
                "--spring.main.allow-bean-definition-overriding=true",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.datasource.hikari.connection-init-sql=SET lock_timeout = '2s'",
                "--spring.jpa.hibernate.ddl-auto=none",
                "--spring.flyway.enabled=false",
                "--spring.mail.host=localhost",
                "--spring.mail.port=1025",
                "--spring.task.scheduling.enabled=false",
                "--ai.gemini.api-key=local-dummy-p5-review",
                "--ai.groq.enabled=false",
                "--ai.openrouter.enabled=false",
                "--supabase.s3.endpoint=http://127.0.0.1:19999",
                "--supabase.s3.access-key=test-access",
                "--supabase.s3.secret-key=test-secret",
                "--supabase.s3.bucket=test-bucket",
                "--supabase.s3.public-url=http://127.0.0.1:19999/public",
                "--google.drive.folder-id=fake-folder-id",
                "--application.security.jwt.secret-key=" + TEST_JWT_SECRET,
                "--application.security.jwt.issuer=taskpilot",
                "--application.security.jwt.audience=taskpilot-api",
                "--application.security.jwt.expiration=600000",
                "--application.security.jwt.refresh-token.expiration=604800000",
                "--application.security.jwt.blocklist.provider=memory",
                "--application.security.password-reset.rate-limit.provider=memory",
                "--rag.embedding.poll-interval-ms=86400000",
                "--decorator.datasource.p6spy.enable-logging=false",
                "--logging.level.root=WARN"
        };
    }

    @Configuration
    static class FakePushConfig {
        @Bean
        @Primary
        OneSignalService fakeOneSignal() {
            return mock(OneSignalService.class);
        }

        @Bean
        @Primary
        JavaMailSender fakeMailSender() {
            return mock(JavaMailSender.class);
        }
    }
}
