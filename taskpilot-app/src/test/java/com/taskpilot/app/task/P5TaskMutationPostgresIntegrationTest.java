package com.taskpilot.app.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import com.taskpilot.contracts.aiquery.port.out.TaskCommandPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.notification.OneSignalService;
import com.taskpilot.projects.common.entity.LabelEntity;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.entity.SprintEntity;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.PriorityLevel;
import com.taskpilot.projects.common.enums.SprintStatus;
import com.taskpilot.projects.common.enums.TaskStatus;
import com.taskpilot.projects.common.repository.LabelRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.common.repository.SprintRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
import com.taskpilot.projects.projects.service.ProjectServiceImpl;
import com.taskpilot.projects.sprints.service.SprintService;
import com.taskpilot.projects.tasks.dto.CreateTaskCommentRequest;
import com.taskpilot.projects.tasks.dto.UpdateTaskCommentRequest;
import com.taskpilot.projects.tasks.service.TaskCommandService;
import com.taskpilot.projects.tasks.service.TaskCommentService;
import com.taskpilot.users.common.entity.SkillEntity;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.enums.UserStatus;
import com.taskpilot.users.common.repository.SkillRepository;
import com.taskpilot.users.common.repository.UserRepository;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * Real PostgreSQL 17 + Flyway V47 behavioral suite for P5-01 task contracts,
 * composite-ID ORM probe, version locking, durable idempotency, project
 * timezone date policy,
 * workflow transition gates, concurrency barriers, and atomic notification
 * rollback/retry.
 */
@Testcontainers
class P5TaskMutationPostgresIntegrationTest {

        private static final String TEST_JWT_SECRET = "taskpilot-test-only-p5-task-contracts-jwt-key-2026-32bytes";
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
        private static TaskRepository taskRepository;
        private static ProjectRepository projectRepository;
        private static ProjectMemberRepository memberRepository;
        private static SprintRepository sprintRepository;
        private static LabelRepository labelRepository;
        private static SkillRepository skillRepository;
        private static TaskCommandService taskCommandService;
        private static TaskCommentService taskCommentService;
        private static SprintService sprintService;
        private static ProjectServiceImpl projectService;
        private static TaskCommandPort taskCommandPort;
        private static PendingAiActionService pendingAiActionService;
        private static ToolCallingRegistryService toolCallingRegistryService;
        private static ChatSessionService chatSessionService;
        private static PlatformTransactionManager txManager;
        private static RecordingOneSignalService fakeOneSignal;

        @BeforeAll
        static void startApp() throws Exception {
                try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                                postgres.getPassword());
                                Statement s = c.createStatement()) {
                        s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'anon') THEN CREATE ROLE anon NOLOGIN; END IF; END $$;");
                        s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'authenticated') THEN CREATE ROLE authenticated NOLOGIN; END IF; END $$;");
                        s.execute("ALTER DEFAULT PRIVILEGES GRANT ALL ON TABLES TO anon, authenticated");
                }

                Flyway flyway = MigrationDatabaseConfiguration.configure(
                                Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                                                postgres.getPassword()))
                                .load();
                assertThat(flyway.migrate().migrationsExecuted).isGreaterThanOrEqualTo(6);
                assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

                context = new SpringApplicationBuilder(TaskPilotApplication.class, FakePushConfig.class)
                                .run(appArgs());
                int port = ((WebServerApplicationContext) context).getWebServer().getPort();
                baseUrl = "http://127.0.0.1:" + port;

                jdbc = context.getBean(JdbcTemplate.class);
                taskRepository = context.getBean(TaskRepository.class);
                projectRepository = context.getBean(ProjectRepository.class);
                memberRepository = context.getBean(ProjectMemberRepository.class);
                sprintRepository = context.getBean(SprintRepository.class);
                labelRepository = context.getBean(LabelRepository.class);
                skillRepository = context.getBean(SkillRepository.class);
                taskCommandService = context.getBean(TaskCommandService.class);
                taskCommentService = context.getBean(TaskCommentService.class);
                sprintService = context.getBean(SprintService.class);
                projectService = context.getBean(ProjectServiceImpl.class);
                taskCommandPort = context.getBean(TaskCommandPort.class);
                pendingAiActionService = context.getBean(PendingAiActionService.class);
                toolCallingRegistryService = context.getBean(ToolCallingRegistryService.class);
                chatSessionService = context.getBean(ChatSessionService.class);
                txManager = context.getBean(PlatformTransactionManager.class);
                fakeOneSignal = context.getBean(RecordingOneSignalService.class);
        }

        @AfterAll
        static void stopApp() {
                if (context != null) {
                        context.close();
                }
        }

        @BeforeEach
        void clearPushLog() {
                fakeOneSignal.clear();
        }

        record UserFixture(long id, String email, String jwt) {
        }

        private UserFixture createUser(boolean verified, AccountLifecycle lifecycle) throws Exception {
                String email = "p5-" + UUID.randomUUID() + "@example.com";
                UserEntity user = new UserEntity();
                user.setEmail(email);
                user.setFullName("P5 User " + email.substring(0, 8));
                user.setRole(UserRole.USER);
                user.setStatus(UserStatus.AVAILABLE);
                user.setAccountState(lifecycle);
                user.setPassword(context.getBean(PasswordEncoder.class).encode("Pass!12345"));
                user.setEmailVerified(verified);
                user = context.getBean(UserRepository.class).saveAndFlush(user);

                String jwt = null;
                if (verified && lifecycle == AccountLifecycle.ACTIVE) {
                        HttpResponse<String> login = send("POST", "/api/v1/auth/login",
                                        Map.of("email", email, "password", "Pass!12345"), null, Map.of());
                        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
                        jwt = MAPPER.readTree(login.body()).path("data").path("token").asText();
                }
                return new UserFixture(user.getId(), email, jwt);
        }

        private JsonNode createProject(String jwt, String timezoneId) throws Exception {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("name", "P5 Proj " + UUID.randomUUID());
                if (timezoneId != null) {
                        body.put("timezoneId", timezoneId);
                }
                HttpResponse<String> resp = send("POST", "/api/v1/projects", body, jwt, Map.of());
                assertThat(resp.statusCode()).as(resp.body()).isEqualTo(201);
                return MAPPER.readTree(resp.body()).path("data");
        }

        private void addMember(long projectId, long userId, MemberRole role) {
                memberRepository.saveAndFlush(ProjectMemberEntity.builder()
                                .projectId(projectId)
                                .userId(userId)
                                .role(role)
                                .build());
        }

        private long createLabel(long projectId, String name) {
                return labelRepository.saveAndFlush(LabelEntity.builder()
                                .projectId(projectId)
                                .name(name + "-" + UUID.randomUUID().toString().substring(0, 6))
                                .color("#3B82F6")
                                .build()).getId();
        }

        private long createSkill(String name) {
                return skillRepository.saveAndFlush(SkillEntity.builder()
                                .name(name + "-" + UUID.randomUUID().toString().substring(0, 6))
                                .description("P5 test skill")
                                .isActive(true)
                                .build()).getId();
        }

        private long createSprint(long projectId, String name, SprintStatus status) {
                return sprintRepository.saveAndFlush(SprintEntity.builder()
                                .projectId(projectId)
                                .name(name)
                                .status(status)
                                .startDate(LocalDate.of(2026, 11, 1))
                                .endDate(LocalDate.of(2026, 11, 15))
                                .build()).getId();
        }

        @Test
        void compositeIdProbe_retainedAndAddedTaskLabelsRequiredSkillsAndCommentMentionsPersistCleanly()
                        throws Exception {
                UserFixture mgr = createUser(true, AccountLifecycle.ACTIVE);
                UserFixture m1 = createUser(true, AccountLifecycle.ACTIVE);
                UserFixture m2 = createUser(true, AccountLifecycle.ACTIVE);
                UserFixture m3 = createUser(true, AccountLifecycle.ACTIVE);

                long projectId = createProject(mgr.jwt(), "Asia/Ho_Chi_Minh").path("id").asLong();
                addMember(projectId, m1.id(), MemberRole.MEMBER);
                addMember(projectId, m2.id(), MemberRole.MEMBER);
                addMember(projectId, m3.id(), MemberRole.MEMBER);

                long label1 = createLabel(projectId, "Backend");
                long label2 = createLabel(projectId, "Urgent");
                long skill1 = createSkill("Java");
                long skill2 = createSkill("Postgres");

                HttpResponse<String> createdResp = send("POST", "/api/v1/tasks", Map.of(
                                "projectId", projectId,
                                "title", "Composite Probe Task",
                                "labelIds", List.of(label1),
                                "requiredSkillIds", List.of(skill1)), mgr.jwt(), Map.of());
                assertThat(createdResp.statusCode()).as(createdResp.body()).isEqualTo(201);
                long taskId = MAPPER.readTree(createdResp.body()).path("data").path("id").asLong();

                // Update retaining label1 & skill1 while adding label2 & skill2
                Map<String, Object> retainAndAdd = new LinkedHashMap<>();
                retainAndAdd.put("expectedTaskVersion", 0L);
                retainAndAdd.put("labelIds", List.of(label1, label2));
                retainAndAdd.put("requiredSkillIds", List.of(skill1, skill2));
                HttpResponse<String> patch1 = send("PATCH", "/api/v1/tasks/" + taskId, retainAndAdd, mgr.jwt(),
                                Map.of());
                assertThat(patch1.statusCode()).as(patch1.body()).isEqualTo(200);
                assertThat(patch1.body()).contains("\"version\":1");

                List<Long> dbLabelsAfterAdd = jdbc.queryForList(
                                "select label_id from task_labels where task_id = ? order by label_id", Long.class,
                                taskId);
                List<Long> dbSkillsAfterAdd = jdbc.queryForList(
                                "select skill_id from task_required_skills where task_id = ? order by skill_id",
                                Long.class, taskId);
                assertThat(dbLabelsAfterAdd).containsExactly(Math.min(label1, label2), Math.max(label1, label2));
                assertThat(dbSkillsAfterAdd).containsExactly(Math.min(skill1, skill2), Math.max(skill1, skill2));

                // Second update retaining label2 / skill2 while removing label1 / skill1
                Map<String, Object> swap = new LinkedHashMap<>();
                swap.put("expectedTaskVersion", 1L);
                swap.put("labelIds", List.of(label2));
                swap.put("requiredSkillIds", List.of(skill2));
                HttpResponse<String> patch2 = send("PATCH", "/api/v1/tasks/" + taskId, swap, mgr.jwt(), Map.of());
                assertThat(patch2.statusCode()).as(patch2.body()).isEqualTo(200);
                assertThat(jdbc.queryForList("select label_id from task_labels where task_id = ?", Long.class, taskId))
                                .containsExactly(label2);
                assertThat(jdbc.queryForList("select skill_id from task_required_skills where task_id = ?", Long.class,
                                taskId))
                                .containsExactly(skill2);

                // Comment mentions probe: create with m1, m2 -> edit retaining m1 & adding m3
                var createdComment = taskCommentService.createComment(taskId,
                                new CreateTaskCommentRequest("Hello @[m1] and @[m2]", null, Set.of(m1.id(), m2.id())),
                                mgr.id());
                var updatedComment = taskCommentService.updateComment(taskId, createdComment.id(),
                                new UpdateTaskCommentRequest("Hello @[m1] and @[m3]", Set.of(m1.id(), m3.id())),
                                mgr.id());
                assertThat(updatedComment.mentions().stream().map(u -> u.id()).toList())
                                .containsExactlyInAnyOrder(m1.id(), m3.id());
                assertThat(jdbc.queryForList(
                                "select user_id from comment_mentions where comment_id = ?",
                                Long.class, createdComment.id()))
                                .containsExactlyInAnyOrder(m1.id(), m3.id());
        }

        @Test
        void patchAndPutPresenceVsNullClearAndValidationAndVersionEnforcement() throws Exception {
                UserFixture mgr = createUser(true, AccountLifecycle.ACTIVE);
                UserFixture member = createUser(true, AccountLifecycle.ACTIVE);
                long projectId = createProject(mgr.jwt(), null).path("id").asLong();
                addMember(projectId, member.id(), MemberRole.MEMBER);
                long sprintId = createSprint(projectId, "Sprint 1", SprintStatus.PLANNING);
                long labelId = createLabel(projectId, "Feature");
                long skillId = createSkill("Spring");

                Map<String, Object> createPayload = new LinkedHashMap<>();
                createPayload.put("projectId", projectId);
                createPayload.put("title", "Original Title");
                createPayload.put("description", "Initial description");
                createPayload.put("assigneeId", member.id());
                createPayload.put("sprintId", sprintId);
                createPayload.put("startDate", "2026-11-15");
                createPayload.put("dueDate", "2026-11-15");
                createPayload.put("labelIds", List.of(labelId));
                createPayload.put("requiredSkillIds", List.of(skillId));

                HttpResponse<String> created = send("POST", "/api/v1/tasks", createPayload, mgr.jwt(), Map.of());
                assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
                JsonNode createdData = MAPPER.readTree(created.body()).path("data");
                long taskId = createdData.path("id").asLong();
                assertThat(createdData.path("version").asLong()).isEqualTo(0L);
                assertThat(createdData.path("projectTimezoneId").asText()).isEqualTo("Asia/Ho_Chi_Minh");
                assertThat(Instant.parse(createdData.path("startDate").asText()))
                                .isEqualTo(Instant.parse("2026-11-14T17:00:00Z"));
                assertThat(Instant.parse(createdData.path("dueDate").asText()))
                                .isEqualTo(Instant.parse("2026-11-15T16:59:59.999999Z"));

                // Missing expectedTaskVersion -> 428
                HttpResponse<String> missingVersion = send("PATCH", "/api/v1/tasks/" + taskId,
                                Map.of("title", "No Version"), mgr.jwt(), Map.of());
                assertThat(missingVersion.statusCode()).as(missingVersion.body()).isEqualTo(428);

                // Partial PATCH omitting nullable fields preserves
                // description/assignee/dates/sprint/labels
                HttpResponse<String> partialPatch = send("PATCH", "/api/v1/tasks/" + taskId,
                                Map.of("expectedTaskVersion", 0L, "title", "Updated Title"), mgr.jwt(), Map.of());
                assertThat(partialPatch.statusCode()).as(partialPatch.body()).isEqualTo(200);
                JsonNode partialData = MAPPER.readTree(partialPatch.body()).path("data");
                assertThat(partialData.path("title").asText()).isEqualTo("Updated Title");
                assertThat(partialData.path("description").asText()).isEqualTo("Initial description");
                assertThat(partialData.path("assigneeId").asLong()).isEqualTo(member.id());
                assertThat(partialData.path("sprintId").asLong()).isEqualTo(sprintId);
                assertThat(partialData.path("labels")).hasSize(1);
                assertThat(partialData.path("version").asLong()).isEqualTo(1L);

                // Stale expectedTaskVersion -> 409
                HttpResponse<String> stalePatch = send("PATCH", "/api/v1/tasks/" + taskId,
                                Map.of("expectedTaskVersion", 0L, "title", "Stale Write"), mgr.jwt(), Map.of());
                assertThat(stalePatch.statusCode()).as(stalePatch.body()).isEqualTo(409);

                // Null non-nullable or null collection -> 400 and NO partial DB write
                Map<String, Object> badNullTitle = new LinkedHashMap<>();
                badNullTitle.put("expectedTaskVersion", 1L);
                badNullTitle.put("title", null);
                badNullTitle.put("description", "Should not apply");
                HttpResponse<String> nullTitleResp = send("PATCH", "/api/v1/tasks/" + taskId, badNullTitle, mgr.jwt(),
                                Map.of());
                assertThat(nullTitleResp.statusCode()).as(nullTitleResp.body()).isEqualTo(400);

                Map<String, Object> badNullLabels = new LinkedHashMap<>();
                badNullLabels.put("expectedTaskVersion", 1L);
                badNullLabels.put("labelIds", null);
                assertThat(send("PATCH", "/api/v1/tasks/" + taskId, badNullLabels, mgr.jwt(), Map.of()).statusCode())
                                .isEqualTo(400);
                assertThat(jdbc.queryForObject("select description from tasks where id = ?", String.class, taskId))
                                .isEqualTo("Initial description");

                // Forbidden mass-assignment keys rejected 400
                for (String forbidden : List.of("reviewerId", "qualityRating", "evaluationId", "role", "reporterId",
                                "version", "updatedAt")) {
                        HttpResponse<String> massAssign = send("PATCH", "/api/v1/tasks/" + taskId,
                                        Map.of("expectedTaskVersion", 1L, forbidden, 99), mgr.jwt(), Map.of());
                        assertThat(massAssign.statusCode()).as("Forbidden key " + forbidden).isEqualTo(400);
                }

                // Explicit null clears description, assigneeId, startDate, dueDate, sprintId
                // and [] clears labels/skills
                Map<String, Object> explicitClears = new LinkedHashMap<>();
                explicitClears.put("expectedTaskVersion", 1L);
                explicitClears.put("description", null);
                explicitClears.put("assigneeId", null);
                explicitClears.put("startDate", null);
                explicitClears.put("dueDate", null);
                explicitClears.put("sprintId", null);
                explicitClears.put("labelIds", List.of());
                explicitClears.put("requiredSkillIds", List.of());

                HttpResponse<String> clearResp = send("PUT", "/api/v1/tasks/" + taskId, explicitClears, mgr.jwt(),
                                Map.of());
                assertThat(clearResp.statusCode()).as(clearResp.body()).isEqualTo(200);
                JsonNode cleared = MAPPER.readTree(clearResp.body()).path("data");
                assertThat(cleared.path("version").asLong()).isEqualTo(2L);
                assertThat(cleared.get("description").isNull()).isTrue();
                assertThat(cleared.get("assigneeId").isNull()).isTrue();
                assertThat(cleared.get("startDate").isNull()).isTrue();
                assertThat(cleared.get("dueDate").isNull()).isTrue();
                assertThat(cleared.get("sprintId").isNull()).isTrue();
                assertThat(cleared.path("labels")).isEmpty();
                assertThat(jdbc.queryForObject("select count(*) from task_required_skills where task_id = ?",
                                Integer.class, taskId))
                                .isZero();

                // Kanban & Sprint & Delete also require expectedTaskVersion (missing -> 428,
                // stale -> 409)
                assertThat(send("PATCH", "/api/v1/tasks/" + taskId + "/kanban",
                                Map.of("status", "TODO", "position", 10.0), mgr.jwt(), Map.of()).statusCode())
                                .isEqualTo(428);
                assertThat(send("PATCH", "/api/v1/tasks/" + taskId + "/kanban",
                                Map.of("status", "TODO", "position", 10.0, "expectedTaskVersion", 1L), mgr.jwt(),
                                Map.of()).statusCode()).isEqualTo(409);

                Map<String, Object> missingSprintVer = new LinkedHashMap<>();
                missingSprintVer.put("sprintId", sprintId);
                assertThat(send("PATCH", "/api/v1/tasks/" + taskId + "/sprint", missingSprintVer, mgr.jwt(), Map.of())
                                .statusCode()).isEqualTo(428);
                assertThat(send("DELETE", "/api/v1/tasks/" + taskId, null, mgr.jwt(), Map.of()).statusCode())
                                .isEqualTo(428);
                assertThat(send("DELETE", "/api/v1/tasks/" + taskId + "?expectedTaskVersion=1", null, mgr.jwt(),
                                Map.of()).statusCode()).isEqualTo(409);

                // Sprint move with current version succeeds -> version 3
                HttpResponse<String> sprintMove = send("PATCH", "/api/v1/tasks/" + taskId + "/sprint",
                                Map.of("sprintId", sprintId, "expectedTaskVersion", 2L), mgr.jwt(), Map.of());
                assertThat(sprintMove.statusCode()).as(sprintMove.body()).isEqualTo(200);
                assertThat(MAPPER.readTree(sprintMove.body()).path("data").path("version").asLong()).isEqualTo(3L);

                // Bulk SprintService.deleteSprint clears sprint_id and advances version &
                // updated_at
                Instant beforeBulkUpdatedAt = jdbc.queryForObject("select updated_at from tasks where id = ?",
                                Instant.class, taskId);
                Thread.sleep(5);
                sprintService.deleteSprint(projectId, sprintId, mgr.id());
                Map<String, Object> afterBulk = jdbc
                                .queryForMap("select sprint_id, version, updated_at from tasks where id = ?", taskId);
                assertThat(afterBulk.get("sprint_id")).isNull();
                assertThat(((Number) afterBulk.get("version")).longValue()).isEqualTo(4L);
                assertThat(((java.sql.Timestamp) afterBulk.get("updated_at")).toInstant()).isAfter(beforeBulkUpdatedAt);

                // Delete with current version 4 succeeds
                assertThat(send("DELETE", "/api/v1/tasks/" + taskId + "?expectedTaskVersion=4", null, mgr.jwt(),
                                Map.of()).statusCode())
                                .isEqualTo(200);
                assertThat(taskRepository.findById(taskId)).isEmpty();
        }

        @Test
        void aiPatchTaskPreservesExplicitNullThroughPendingConfirmationAndRejectsStaleOrUnauthorizedConfirm()
                        throws Exception {
                UserFixture mgr = createUser(true, AccountLifecycle.ACTIVE);
                UserFixture member = createUser(true, AccountLifecycle.ACTIVE);
                UserFixture outsider = createUser(true, AccountLifecycle.ACTIVE);
                long projectId = createProject(mgr.jwt(), "Asia/Ho_Chi_Minh").path("id").asLong();
                addMember(projectId, member.id(), MemberRole.MEMBER);

                var created = taskCommandPort.createTask(projectId, "AI Patch Target", "Keep", "MEDIUM", 0f,
                                null, null, 3, null, null, member.id(), "2026-11-15", "2026-11-15", UUID.randomUUID(),
                                mgr.id());
                long sid = chatSessionService.createSession(mgr.id(), "P5 AI session").getId();

                Map<String, Object> patchData = new LinkedHashMap<>();
                patchData.put("dueDate", null);
                patchData.put("assigneeId", null);
                Map<String, Object> toolArgs = new LinkedHashMap<>();
                toolArgs.put("taskId", created.id());
                toolArgs.put("expectedTaskVersion", created.version());
                toolArgs.put("patchData", patchData);
                toolArgs.put("reason", "Clear deadline and unassign");

                String raw = ToolExecutionContext.callWith(
                                new ToolExecutionContext.Context(mgr.id(), sid, "clear due date"),
                                () -> toolCallingRegistryService.execute(ToolExecutionRequest.builder()
                                                .name("patchTask")
                                                .arguments(MAPPER.writeValueAsString(toolArgs))
                                                .build()));
                JsonNode previewJson = MAPPER.readTree(raw);
                assertThat(previewJson.path("confirmationRequired").asBoolean()).isTrue();
                ConfirmationRequiredDto action = pendingAiActionService.describe(
                                previewJson.path("actionId").asText(), mgr.id(), sid);

                // Unauthorized outsider cannot confirm or consume owner's action
                HttpResponse<String> outsiderConfirm = send("POST",
                                "/api/v1/ai/sessions/" + sid + "/actions/" + action.actionId() + "/confirm",
                                Map.of("idempotencyKey", "out-1", "payloadHash", action.payloadHash()),
                                outsider.jwt(), Map.of());
                assertThat(outsiderConfirm.statusCode()).isIn(403, 404);

                // Owner confirms explicit null patch -> clears dueDate and assigneeId in DB
                HttpResponse<String> okConfirm = send("POST",
                                "/api/v1/ai/sessions/" + sid + "/actions/" + action.actionId() + "/confirm",
                                Map.of("idempotencyKey", "ok-1", "payloadHash", action.payloadHash()),
                                mgr.jwt(), Map.of());
                assertThat(okConfirm.statusCode()).as(okConfirm.body()).isEqualTo(200);
                TaskEntity afterAiClear = taskRepository.findById(created.id()).orElseThrow();
                assertThat(afterAiClear.getDueDate()).isNull();
                assertThat(afterAiClear.getAssigneeId()).isNull();
                assertThat(afterAiClear.effectiveVersion()).isEqualTo(1L);

                // Second AI preview on version 1, then external REST mutation bumps version to
                // 2 -> confirm returns 409
                Map<String, Object> staleArgs = new LinkedHashMap<>();
                staleArgs.put("taskId", created.id());
                staleArgs.put("expectedTaskVersion", 1L);
                staleArgs.put("patchData", Map.of("title", "AI Should Conflict"));
                staleArgs.put("reason", "Stale check");
                String raw2 = ToolExecutionContext.callWith(
                                new ToolExecutionContext.Context(mgr.id(), sid, "rename task"),
                                () -> toolCallingRegistryService.execute(ToolExecutionRequest.builder()
                                                .name("patchTask")
                                                .arguments(MAPPER.writeValueAsString(staleArgs))
                                                .build()));
                JsonNode p2 = MAPPER.readTree(raw2);
                assertThat(send("PATCH", "/api/v1/tasks/" + created.id(),
                                Map.of("expectedTaskVersion", 1L, "title", "Concurrent REST Title"), mgr.jwt(),
                                Map.of()).statusCode())
                                .isEqualTo(200);

                HttpResponse<String> staleConfirm = send("POST",
                                "/api/v1/ai/sessions/" + sid + "/actions/" + p2.path("actionId").asText() + "/confirm",
                                Map.of("idempotencyKey", "stale-1", "payloadHash", p2.path("payloadHash").asText()),
                                mgr.jwt(), Map.of());
                assertThat(staleConfirm.statusCode()).as(staleConfirm.body()).isEqualTo(409);
                assertThat(taskRepository.findById(created.id()).orElseThrow().getTitle())
                                .isEqualTo("Concurrent REST Title");
        }

        @Test
        void durableIdempotencyReplaysExactSameBodyRejectsChangedBodyAndReauthorizesRemovedMember() throws Exception {
                UserFixture mgr = createUser(true, AccountLifecycle.ACTIVE);
                UserFixture secondMgr = createUser(true, AccountLifecycle.ACTIVE);
                long projectId = createProject(mgr.jwt(), "UTC").path("id").asLong();
                addMember(projectId, secondMgr.id(), MemberRole.MANAGER);

                HttpResponse<String> created = send("POST", "/api/v1/tasks",
                                Map.of("projectId", projectId, "title", "Idempotent Task"),
                                mgr.jwt(), Map.of());
                long taskId = MAPPER.readTree(created.body()).path("data").path("id").asLong();

                String idemKey = UUID.randomUUID().toString();
                Map<String, Object> bodyA = Map.of("expectedTaskVersion", 0L, "title", "Renamed Once");
                HttpResponse<String> first = send("PATCH", "/api/v1/tasks/" + taskId, bodyA,
                                secondMgr.jwt(), Map.of("Idempotency-Key", idemKey));
                assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
                assertThat(MAPPER.readTree(first.body()).path("data").path("version").asLong()).isEqualTo(1L);

                // Same key + same body -> replay cached response, version stays 1
                HttpResponse<String> replay = send("PATCH", "/api/v1/tasks/" + taskId, bodyA,
                                secondMgr.jwt(), Map.of("Idempotency-Key", idemKey));
                assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
                assertThat(MAPPER.readTree(replay.body()).path("data").path("version").asLong()).isEqualTo(1L);
                assertThat(taskRepository.findById(taskId).orElseThrow().effectiveVersion()).isEqualTo(1L);

                // Same key + different body -> 409
                HttpResponse<String> altered = send("PATCH", "/api/v1/tasks/" + taskId,
                                Map.of("expectedTaskVersion", 0L, "title", "Different Payload"),
                                secondMgr.jwt(), Map.of("Idempotency-Key", idemKey));
                assertThat(altered.statusCode()).as(altered.body()).isEqualTo(409);

                // Remove secondMgr from project -> replay with same key & body must fail 403
                // (reauthorized!)
                projectService.removeMember(projectId, secondMgr.id(), mgr.id());
                HttpResponse<String> removedReplay = send("PATCH", "/api/v1/tasks/" + taskId, bodyA,
                                secondMgr.jwt(), Map.of("Idempotency-Key", idemKey));
                assertThat(removedReplay.statusCode()).as(removedReplay.body()).isEqualTo(403);
        }

        @Test
        void workflowGatesCompletedSprintForeignRefsAndProjectTimezoneSummaryAndConcurrencyBarrier() throws Exception {
                UserFixture mgr = createUser(true, AccountLifecycle.ACTIVE);
                UserFixture member1 = createUser(true, AccountLifecycle.ACTIVE);
                UserFixture member2 = createUser(true, AccountLifecycle.ACTIVE);
                UserFixture outsider = createUser(true, AccountLifecycle.ACTIVE);
                UserFixture unverified = createUser(false, AccountLifecycle.ACTIVE);
                UserFixture suspended = createUser(true, AccountLifecycle.ADMIN_SUSPENDED);

                JsonNode defaultProjNode = createProject(mgr.jwt(), null);
                assertThat(defaultProjNode.path("timezoneId").asText()).isEqualTo("Asia/Ho_Chi_Minh");
                assertThat(defaultProjNode.path("timezoneProvenance").asText()).isEqualTo("PROJECT_DEFAULT");

                JsonNode projNode = createProject(mgr.jwt(), "America/New_York");
                long projectId = projNode.path("id").asLong();
                assertThat(projNode.path("timezoneId").asText()).isEqualTo("America/New_York");
                assertThat(projNode.path("timezoneProvenance").asText()).isEqualTo("MANAGER_SELECTED");

                addMember(projectId, member1.id(), MemberRole.MEMBER);
                addMember(projectId, member2.id(), MemberRole.MEMBER);
                addMember(projectId, unverified.id(), MemberRole.MEMBER);
                addMember(projectId, suspended.id(), MemberRole.MEMBER);

                // Foreign entities in another project
                long otherProjectId = createProject(outsider.jwt(), null).path("id").asLong();
                long otherLabel = createLabel(otherProjectId, "Foreign");
                long otherSprint = createSprint(otherProjectId, "Foreign Sprint", SprintStatus.ACTIVE);
                long otherTask = MAPPER.readTree(send("POST", "/api/v1/tasks",
                                Map.of("projectId", otherProjectId, "title", "Foreign Parent"), outsider.jwt(),
                                Map.of()).body())
                                .path("data").path("id").asLong();

                // Reject foreign parent/label/sprint and unverified/suspended/outsider assignee
                // and invalid date range
                assertThat(send("POST", "/api/v1/tasks",
                                Map.of("projectId", projectId, "title", "Bad Parent", "parentId", otherTask), mgr.jwt(),
                                Map.of()).statusCode())
                                .isIn(400, 403, 404);
                assertThat(send("POST", "/api/v1/tasks",
                                Map.of("projectId", projectId, "title", "Bad Label", "labelIds", List.of(otherLabel)),
                                mgr.jwt(), Map.of()).statusCode())
                                .isIn(400, 403, 404);
                assertThat(send("POST", "/api/v1/tasks",
                                Map.of("projectId", projectId, "title", "Bad Sprint", "sprintId", otherSprint),
                                mgr.jwt(), Map.of()).statusCode())
                                .isIn(400, 403, 404);
                assertThat(send("POST", "/api/v1/tasks",
                                Map.of("projectId", projectId, "title", "Unverified Assignee", "assigneeId",
                                                unverified.id()),
                                mgr.jwt(), Map.of()).statusCode())
                                .isIn(400, 403);
                assertThat(send("POST", "/api/v1/tasks",
                                Map.of("projectId", projectId, "title", "Suspended Assignee", "assigneeId",
                                                suspended.id()),
                                mgr.jwt(), Map.of()).statusCode())
                                .isIn(400, 403);
                assertThat(send("POST", "/api/v1/tasks",
                                Map.of("projectId", projectId, "title", "Outsider Assignee", "assigneeId",
                                                outsider.id()),
                                mgr.jwt(), Map.of()).statusCode())
                                .isIn(400, 403, 409);
                assertThat(send("POST", "/api/v1/tasks",
                                Map.of("projectId", projectId, "title", "Bad Range", "startDate", "2026-11-16",
                                                "dueDate", "2026-11-15"),
                                mgr.jwt(), Map.of()).statusCode()).isEqualTo(400);

                // DST boundary in America/New_York: 2026-03-08 (spring forward)
                HttpResponse<String> nyTaskResp = send("POST", "/api/v1/tasks",
                                Map.of("projectId", projectId, "title", "NY DST Task", "startDate", "2026-03-08",
                                                "dueDate", "2026-03-08"),
                                mgr.jwt(), Map.of());
                assertThat(nyTaskResp.statusCode()).as(nyTaskResp.body()).isEqualTo(201);
                JsonNode nyData = MAPPER.readTree(nyTaskResp.body()).path("data");
                long nyTaskId = nyData.path("id").asLong();
                assertThat(Instant.parse(nyData.path("startDate").asText()))
                                .isEqualTo(Instant.parse("2026-03-08T05:00:00Z"));
                assertThat(Instant.parse(nyData.path("dueDate").asText()))
                                .isEqualTo(Instant.parse("2026-03-09T03:59:59.999999Z"));

                // TODO -> IN_PROGRESS requires active assignee and caller is assignee or active
                // manager
                assertThat(send("PATCH", "/api/v1/tasks/" + nyTaskId + "/kanban",
                                Map.of("status", "IN_PROGRESS", "position", 1.0, "expectedTaskVersion", 0L), mgr.jwt(),
                                Map.of()).statusCode())
                                .isIn(400, 409);

                // Assign to member1 (v0 -> v1)
                assertThat(send("PATCH", "/api/v1/tasks/" + nyTaskId,
                                Map.of("expectedTaskVersion", 0L, "assigneeId", member1.id()), mgr.jwt(), Map.of())
                                .statusCode())
                                .isEqualTo(200);

                // member2 (not assignee, not manager) cannot start member1's task
                assertThat(send("PATCH", "/api/v1/tasks/" + nyTaskId + "/kanban",
                                Map.of("status", "IN_PROGRESS", "position", 1.0, "expectedTaskVersion", 1L),
                                member2.jwt(), Map.of()).statusCode())
                                .isEqualTo(403);

                // member1 moves TODO -> IN_PROGRESS (v1 -> v2)
                assertThat(send("PATCH", "/api/v1/tasks/" + nyTaskId + "/kanban",
                                Map.of("status", "IN_PROGRESS", "position", 1.0, "expectedTaskVersion", 1L),
                                member1.jwt(), Map.of()).statusCode())
                                .isEqualTo(200);

                // Direct REVIEW/DONE bypass rejected 409 with structured requiredAction +
                // allowedActions
                HttpResponse<String> reviewBypass = send("PATCH", "/api/v1/tasks/" + nyTaskId + "/kanban",
                                Map.of("status", "REVIEW", "position", 2.0, "expectedTaskVersion", 2L), member1.jwt(),
                                Map.of());
                assertThat(reviewBypass.statusCode()).as(reviewBypass.body()).isEqualTo(409);
                JsonNode rbJson = MAPPER.readTree(reviewBypass.body()).path("data");
                assertThat(rbJson.path("requiredAction").asText()).isEqualTo("SUBMIT_TASK");
                assertThat(rbJson.path("allowedActions").isArray()).isTrue();

                HttpResponse<String> doneBypass = send("PATCH", "/api/v1/tasks/" + nyTaskId,
                                Map.of("expectedTaskVersion", 2L, "status", "DONE"), mgr.jwt(), Map.of());
                assertThat(doneBypass.statusCode()).as(doneBypass.body()).isEqualTo(409);
                assertThat(MAPPER.readTree(doneBypass.body()).path("data").path("requiredAction").asText())
                                .isEqualTo("REVIEW_TASK");

                // Completed sprint gate across edit / kanban / sprint move / delete
                long completedSprint = createSprint(projectId, "Closed Sprint", SprintStatus.COMPLETED);
                assertThat(send("PATCH", "/api/v1/tasks/" + nyTaskId + "/sprint",
                                Map.of("sprintId", completedSprint, "expectedTaskVersion", 2L), mgr.jwt(), Map.of())
                                .statusCode())
                                .isEqualTo(409);

                TaskEntity closedTask = taskRepository.saveAndFlush(TaskEntity.builder()
                                .projectId(projectId)
                                .sprintId(completedSprint)
                                .title("Locked In Completed Sprint")
                                .status(TaskStatus.REVIEW)
                                .priority(PriorityLevel.MEDIUM)
                                .assigneeId(member1.id())
                                .reporterId(mgr.id())
                                .build());
                assertThat(send("PATCH", "/api/v1/tasks/" + closedTask.getId(),
                                Map.of("expectedTaskVersion", 0L, "title", "Edit Closed"), mgr.jwt(), Map.of())
                                .statusCode()).isEqualTo(409);
                assertThat(send("PATCH", "/api/v1/tasks/" + closedTask.getId() + "/kanban",
                                Map.of("status", "TODO", "position", 1f, "expectedTaskVersion", 0L), mgr.jwt(),
                                Map.of()).statusCode()).isEqualTo(409);
                assertThat(send("DELETE", "/api/v1/tasks/" + closedTask.getId() + "?expectedTaskVersion=0",
                                null, mgr.jwt(), Map.of()).statusCode()).isEqualTo(409);

                // Verify ProjectSummaryResponse has nonzero todoTasks, inProgressTasks,
                // reviewTasks, doneTasks
                TaskEntity todoExtra = taskRepository.saveAndFlush(TaskEntity.builder()
                                .projectId(projectId).title("Todo Extra").status(TaskStatus.TODO)
                                .priority(PriorityLevel.LOW).reporterId(mgr.id()).build());
                TaskEntity doneExtra = taskRepository.saveAndFlush(TaskEntity.builder()
                                .projectId(projectId).title("Done Extra").status(TaskStatus.DONE)
                                .priority(PriorityLevel.LOW).reporterId(mgr.id()).build());

                HttpResponse<String> summaryResp = send("GET", "/api/v1/projects/" + projectId + "/summary",
                                null, mgr.jwt(), Map.of());
                assertThat(summaryResp.statusCode()).as(summaryResp.body()).isEqualTo(200);
                JsonNode sum = MAPPER.readTree(summaryResp.body()).path("data");
                assertThat(sum.path("todoTasks").asLong()).isGreaterThanOrEqualTo(1L);
                assertThat(sum.path("inProgressTasks").asLong()).isGreaterThanOrEqualTo(1L);
                assertThat(sum.path("reviewTasks").asLong()).isGreaterThanOrEqualTo(1L);
                assertThat(sum.path("doneTasks").asLong()).isGreaterThanOrEqualTo(1L);
                assertThat(sum.has("completedTasks")).isFalse();
                assertThat(sum.has("pendingTasks")).isFalse();

                // Concurrency barrier: removeMember(member2) races with
                // assignTaskToMember(member2)
                // Canonical lock order (projects -> accounts -> task) ensures no deadlock and
                // no dangling assignment to removed member
                ExecutorService pool = Executors.newFixedThreadPool(2);
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                List<Throwable> errors = new CopyOnWriteArrayList<>();

                Future<?> removeFuture = pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        projectService.removeMember(projectId, member2.id(), mgr.id());
                        return null;
                });
                Future<?> assignFuture = pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        try {
                                taskCommandService.assignTaskToMember(todoExtra.getId(), member2.id(), 0L,
                                                UUID.randomUUID(), mgr.id(), false);
                        } catch (BusinessException expected) {
                                errors.add(expected);
                        }
                        return null;
                });
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                removeFuture.get(15, TimeUnit.SECONDS);
                assignFuture.get(15, TimeUnit.SECONDS);
                pool.shutdownNow();

                assertThat(memberRepository.existsByProjectIdAndUserId(projectId, member2.id())).isFalse();
                assertThat(taskRepository.findById(todoExtra.getId()).orElseThrow().getAssigneeId()).isNull();
                assertThat(taskRepository.findById(doneExtra.getId())).isPresent();
        }

        @Test
        void failedTransactionRollsBackTaskNotificationAndIdempotencyAndPush_retryCommitsOnceWithScope()
                        throws Exception {
                UserFixture mgr = createUser(true, AccountLifecycle.ACTIVE);
                UserFixture assignee = createUser(true, AccountLifecycle.ACTIVE);
                long projectId = createProject(mgr.jwt(), "Asia/Ho_Chi_Minh").path("id").asLong();
                addMember(projectId, assignee.id(), MemberRole.MEMBER);

                var created = taskCommandPort.createTask(projectId, "Atomic Notify Task", null, "HIGH", 0f,
                                null, null, 2, null, null, null, null, null, UUID.randomUUID(), mgr.id());

                UUID requestId = UUID.randomUUID();
                TransactionTemplate tx = new TransactionTemplate(txManager);

                assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                        taskCommandService.assignTaskToMember(created.id(), assignee.id(),
                                        0L, requestId, mgr.id(), false);
                        throw new IllegalStateException("Injected failure after assignTaskToMember");
                })).isInstanceOf(IllegalStateException.class);

                // Task assignment, notification row, idempotency row all rolled back and zero
                // pushes sent
                TaskEntity rolledBackTask = taskRepository.findById(created.id()).orElseThrow();
                assertThat(rolledBackTask.getAssigneeId()).isNull();
                assertThat(rolledBackTask.effectiveVersion()).isEqualTo(0L);
                assertThat(jdbc.queryForObject(
                                "select count(*) from notifications where user_id = ? and link_action = ?",
                                Integer.class, assignee.id(), "/tasks?taskId=" + created.id())).isZero();
                assertThat(jdbc.queryForObject("select count(*) from task_command_idempotency where request_id = ?",
                                Integer.class, requestId)).isZero();
                Thread.sleep(150);
                assertThat(fakeOneSignal.calls()).isEmpty();

                // Retry with the same requestId succeeds, persists 1 notification row, and
                // dispatches push after commit
                taskCommandService.assignTaskToMember(created.id(), assignee.id(),
                                0L, requestId, mgr.id(), false);

                TaskEntity committedTask = taskRepository.findById(created.id()).orElseThrow();
                assertThat(committedTask.getAssigneeId()).isEqualTo(assignee.id());
                assertThat(committedTask.effectiveVersion()).isEqualTo(1L);
                assertThat(jdbc.queryForObject(
                                "select count(*) from notifications where user_id = ? and link_action = ?",
                                Integer.class, assignee.id(), "/tasks?taskId=" + created.id())).isEqualTo(1);
                assertThat(jdbc.queryForObject("select count(*) from task_command_idempotency where request_id = ?",
                                Integer.class, requestId)).isEqualTo(1);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (fakeOneSignal.calls().isEmpty() && System.nanoTime() < deadline) {
                        Thread.sleep(25);
                }
                assertThat(fakeOneSignal.calls()).hasSize(1);
                assertThat(fakeOneSignal.calls().getFirst().userId()).isEqualTo(String.valueOf(assignee.id()));
                assertThat(fakeOneSignal.calls().getFirst().linkAction()).contains("taskId=" + created.id());
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
                if (!extraHeaders.containsKey("Idempotency-Key") && path.startsWith("/api/v1/tasks")
                                && !"GET".equals(method)) {
                        builder.header("Idempotency-Key", UUID.randomUUID().toString());
                }
                for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                        builder.header(header.getKey(), header.getValue());
                }
                return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        private static String[] appArgs() {
                return new String[] {
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
                                "--ai.gemini.api-key=local-dummy-p5",
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
                RecordingOneSignalService recordingOneSignalService() {
                        return new RecordingOneSignalService();
                }

                @Bean
                @Primary
                JavaMailSender fakeMailSender() {
                        return mock(JavaMailSender.class);
                }
        }

        static class RecordingOneSignalService extends OneSignalService {
                record PushCall(String userId, String title, String message, String linkAction) {
                }

                private final List<PushCall> calls = new CopyOnWriteArrayList<>();

                @Override
                public void sendNotificationToUser(String targetUserId, String title, String message,
                                String linkAction) {
                        calls.add(new PushCall(targetUserId, title, message, linkAction));
                }

                List<PushCall> calls() {
                        return List.copyOf(calls);
                }

                void clear() {
                        calls.clear();
                }
        }
}
