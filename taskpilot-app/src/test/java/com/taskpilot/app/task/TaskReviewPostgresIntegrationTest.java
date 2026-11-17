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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
import com.taskpilot.ai.service.TaskEvaluationJournalListener;
import com.taskpilot.ai.service.ToolCallingRegistryService;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.app.TaskPilotApplication;
import com.taskpilot.app.migration.MigrationDatabaseConfiguration;
import com.taskpilot.contracts.aiquery.port.out.TaskCommandPort;
import com.taskpilot.contracts.task.EvidenceStatus;
import com.taskpilot.contracts.task.TaskEvaluationAccepted;
import com.taskpilot.contracts.task.TaskEvaluationInvalidated;
import com.taskpilot.contracts.task.TaskEvaluationJournalConsumer;
import com.taskpilot.contracts.user.dto.UserLifecycleRecoveryRequestedEvent;
import com.taskpilot.infrastructure.notification.OneSignalService;
import com.taskpilot.infrastructure.storage.StorageService;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.entity.SprintEntity;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.SprintStatus;
import com.taskpilot.projects.common.enums.TaskStatus;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.SprintRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
import com.taskpilot.projects.files.service.ProjectFileService;
import com.taskpilot.projects.projects.service.ProjectAccountLifecycleAdapter;
import com.taskpilot.projects.projects.service.ProjectServiceImpl;
import com.taskpilot.projects.tasks.service.TaskWorkflowJournalDispatcher;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.enums.UserStatus;
import com.taskpilot.users.common.repository.UserRepository;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * Real PostgreSQL 17 + Flyway V48 behavioral suite for P5-02 task workflow:
 * submit, withdraw, independent review, sole-manager self-attestation,
 * supersede, reopen, evidence snapshots & availability events, atomic rollback,
 * concurrency races, durable journal replay, AI confirmation fencing, and legacy recovery.
 */
@Testcontainers
class TaskReviewPostgresIntegrationTest {

    private static final String TEST_JWT_SECRET = "taskpilot-test-only-p5-task-workflow-jwt-key-2026-32bytes";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Instant BASE_TIME = Instant.parse("2026-11-14T10:00:00Z");

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
    private static ProjectMemberRepository memberRepository;
    private static SprintRepository sprintRepository;
    private static ProjectFileRepository projectFileRepository;
    private static ProjectFileService projectFileService;
    private static ProjectServiceImpl projectService;
    private static TaskCommandPort taskCommandPort;
    private static PendingAiActionService pendingAiActionService;
    private static ToolCallingRegistryService toolCallingRegistryService;
    private static ChatSessionService chatSessionService;
    private static TaskWorkflowJournalDispatcher journalDispatcher;
    private static TaskEvaluationJournalListener aiJournalListener;
    private static MutableClock testClock;
    private static ControllableJournalConsumer controllableConsumer;

    @BeforeAll
    static void startApp() throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'anon') THEN CREATE ROLE anon NOLOGIN; END IF; END $$;");
            s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'authenticated') THEN CREATE ROLE authenticated NOLOGIN; END IF; END $$;");
            s.execute("ALTER DEFAULT PRIVILEGES GRANT ALL ON TABLES TO anon, authenticated");
        }

        Flyway flyway = MigrationDatabaseConfiguration.configure(
                Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()))
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isGreaterThanOrEqualTo(7);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        context = new SpringApplicationBuilder(TaskPilotApplication.class, FakeWorkflowConfig.class)
                .run(appArgs());
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        baseUrl = "http://127.0.0.1:" + port;

        jdbc = context.getBean(JdbcTemplate.class);
        taskRepository = context.getBean(TaskRepository.class);
        memberRepository = context.getBean(ProjectMemberRepository.class);
        sprintRepository = context.getBean(SprintRepository.class);
        projectFileRepository = context.getBean(ProjectFileRepository.class);
        projectFileService = context.getBean(ProjectFileService.class);
        projectService = context.getBean(ProjectServiceImpl.class);
        taskCommandPort = context.getBean(TaskCommandPort.class);
        pendingAiActionService = context.getBean(PendingAiActionService.class);
        toolCallingRegistryService = context.getBean(ToolCallingRegistryService.class);
        chatSessionService = context.getBean(ChatSessionService.class);
        journalDispatcher = context.getBean(TaskWorkflowJournalDispatcher.class);
        aiJournalListener = context.getBean(TaskEvaluationJournalListener.class);
        testClock = context.getBean(MutableClock.class);
        controllableConsumer = context.getBean(ControllableJournalConsumer.class);
    }

    @AfterAll
    static void stopApp() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void resetFixtures() {
        testClock.setInstant(BASE_TIME);
        controllableConsumer.reset();
    }

    record UserFixture(long id, String email, String jwt) {
    }

    private UserFixture createUser(boolean verified, AccountLifecycle lifecycle) throws Exception {
        String email = "p502-" + UUID.randomUUID() + "@example.com";
        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setFullName("P502 User " + email.substring(0, 8));
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

    private long createProject(String jwt, String timezoneId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "P502 Proj " + UUID.randomUUID());
        if (timezoneId != null) {
            body.put("timezoneId", timezoneId);
        }
        HttpResponse<String> resp = send("POST", "/api/v1/projects", body, jwt, Map.of());
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(201);
        return MAPPER.readTree(resp.body()).path("data").path("id").asLong();
    }

    private void addMember(long projectId, long userId, MemberRole role) {
        memberRepository.saveAndFlush(ProjectMemberEntity.builder()
                .projectId(projectId)
                .userId(userId)
                .role(role)
                .build());
    }

    private JsonNode createAndStartTask(String jwt, long projectId, Long assigneeId, String dueDate) throws Exception {
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("projectId", projectId);
        createBody.put("title", "Workflow Task " + UUID.randomUUID().toString().substring(0, 6));
        createBody.put("description", "Acceptance criteria verified");
        if (assigneeId != null) {
            createBody.put("assigneeId", assigneeId);
        }
        if (dueDate != null) {
            createBody.put("dueDate", dueDate);
        }
        createBody.put("requestId", UUID.randomUUID().toString());
        HttpResponse<String> created = send("POST", "/api/v1/tasks", createBody, jwt, Map.of());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode task = MAPPER.readTree(created.body()).path("data");
        long taskId = task.path("id").asLong();
        long version = task.path("version").asLong();

        HttpResponse<String> started = send("PATCH", "/api/v1/tasks/" + taskId,
                Map.of("expectedTaskVersion", version, "requestId", UUID.randomUUID().toString(), "status", "IN_PROGRESS"),
                jwt, Map.of());
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        return MAPPER.readTree(started.body()).path("data");
    }

    private ProjectFileEntity createProjectFile(long projectId, long uploaderId, String name) {
        return projectFileRepository.saveAndFlush(ProjectFileEntity.builder()
                .projectId(projectId)
                .uploaderId(uploaderId)
                .fileName("stored-" + UUID.randomUUID() + "-" + name)
                .originalName(name)
                .fileSize(2048L)
                .contentType("application/pdf")
                .storageKey("projects/" + projectId + "/files/" + UUID.randomUUID() + "-" + name)
                .storageBucket("documents")
                .build());
    }

    @Test
    void independentAcceptance_directDoneBlocked_frozenReview_selfRatingRejected_andSoleManagerSelfAttest()
            throws Exception {
        UserFixture manager1 = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture manager2 = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture assignee = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture outsider = createUser(true, AccountLifecycle.ACTIVE);

        long projectId = createProject(manager1.jwt(), "UTC");
        addMember(projectId, assignee.id(), MemberRole.MEMBER);
        addMember(projectId, manager2.id(), MemberRole.MANAGER);

        JsonNode task = createAndStartTask(manager1.jwt(), projectId, assignee.id(), "2026-11-16");
        long taskId = task.path("id").asLong();
        long version = task.path("version").asLong();

        // Direct DONE / REVIEW bypass blocked across REST PUT, PATCH, Kanban
        HttpResponse<String> directDonePatch = send("PATCH", "/api/v1/tasks/" + taskId,
                Map.of("expectedTaskVersion", version, "requestId", UUID.randomUUID().toString(), "status", "DONE"),
                manager1.jwt(), Map.of());
        assertThat(directDonePatch.statusCode()).isEqualTo(409);
        assertThat(MAPPER.readTree(directDonePatch.body()).path("data").path("requiredAction").asText())
                .isEqualTo("REVIEW_TASK");

        HttpResponse<String> directDoneKanban = send("PATCH", "/api/v1/tasks/" + taskId + "/kanban",
                Map.of("status", "DONE", "position", 1.0, "expectedTaskVersion", version, "requestId", UUID.randomUUID().toString()),
                manager1.jwt(), Map.of());
        assertThat(directDoneKanban.statusCode()).isEqualTo(409);

        HttpResponse<String> directReviewPut = send("PUT", "/api/v1/tasks/" + taskId,
                Map.of("expectedTaskVersion", version, "requestId", UUID.randomUUID().toString(), "status", "REVIEW"),
                assignee.jwt(), Map.of());
        assertThat(directReviewPut.statusCode()).isEqualTo(409);
        assertThat(MAPPER.readTree(directReviewPut.body()).path("data").path("requiredAction").asText())
                .isEqualTo("SUBMIT_TASK");

        // Non-assignee manager cannot submit on behalf of assignee
        HttpResponse<String> managerSubmit = send("POST", "/api/v1/tasks/" + taskId + "/workflow/submit",
                Map.of("expectedTaskVersion", version, "requestId", UUID.randomUUID().toString(), "summary", "Manager faking submit"),
                manager1.jwt(), Map.of());
        assertThat(managerSubmit.statusCode()).isEqualTo(403);

        // Assignee submits with same-project evidence file
        ProjectFileEntity evidenceFile = createProjectFile(projectId, assignee.id(), "spec-proof.pdf");
        UUID submitReqId = UUID.randomUUID();
        HttpResponse<String> submitResp = send("POST", "/api/v1/tasks/" + taskId + "/workflow/submit",
                Map.of(
                        "expectedTaskVersion", version,
                        "requestId", submitReqId.toString(),
                        "summary", "Implemented and verified criteria",
                        "evidenceProjectFileIds", List.of(evidenceFile.getId())),
                assignee.jwt(), Map.of());
        assertThat(submitResp.statusCode()).as(submitResp.body()).isEqualTo(200);
        JsonNode submitData = MAPPER.readTree(submitResp.body()).path("data");
        long submissionId = submitData.path("submissionId").asLong();
        long reviewTaskVersion = submitData.path("taskVersion").asLong();
        assertThat(submitData.path("state").asText()).isEqualTo("REVIEW");

        // Idempotent replay of submit returns 200 with same submissionId even though task version bumped
        HttpResponse<String> submitReplay = send("POST", "/api/v1/tasks/" + taskId + "/workflow/submit",
                Map.of(
                        "expectedTaskVersion", version,
                        "requestId", submitReqId.toString(),
                        "summary", "Implemented and verified criteria",
                        "evidenceProjectFileIds", List.of(evidenceFile.getId())),
                assignee.jwt(), Map.of());
        assertThat(submitReplay.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(submitReplay.body()).path("data").path("submissionId").asLong())
                .isEqualTo(submissionId);

        // Same requestId with different payload returns 409
        HttpResponse<String> submitMismatch = send("POST", "/api/v1/tasks/" + taskId + "/workflow/submit",
                Map.of(
                        "expectedTaskVersion", version,
                        "requestId", submitReqId.toString(),
                        "summary", "Changed summary",
                        "evidenceProjectFileIds", List.of(evidenceFile.getId())),
                assignee.jwt(), Map.of());
        assertThat(submitMismatch.statusCode()).isEqualTo(409);

        // Missing requestId -> 400; missing expectedTaskVersion -> 428
        HttpResponse<String> missingReqId = send("POST", "/api/v1/tasks/" + taskId + "/workflow/review",
                Map.of("submissionId", submissionId, "expectedTaskVersion", reviewTaskVersion, "decision", "RETURN", "feedback", "Need fixes"),
                manager1.jwt(), Map.of());
        assertThat(missingReqId.statusCode()).isEqualTo(400);

        HttpResponse<String> missingVer = send("POST", "/api/v1/tasks/" + taskId + "/workflow/review",
                Map.of("submissionId", submissionId, "requestId", UUID.randomUUID().toString(), "decision", "RETURN", "feedback", "Need fixes"),
                manager1.jwt(), Map.of());
        assertThat(missingVer.statusCode()).isEqualTo(428);

        // REVIEW freezes evidence-affecting fields while allowing position-only kanban reorder
        HttpResponse<String> frozenTitle = send("PATCH", "/api/v1/tasks/" + taskId,
                Map.of("expectedTaskVersion", reviewTaskVersion, "requestId", UUID.randomUUID().toString(), "title", "Sneaky edit"),
                manager1.jwt(), Map.of());
        assertThat(frozenTitle.statusCode()).isEqualTo(409);

        HttpResponse<String> positionOnly = send("PATCH", "/api/v1/tasks/" + taskId,
                Map.of("expectedTaskVersion", reviewTaskVersion, "requestId", UUID.randomUUID().toString(), "position", 42.5),
                manager1.jwt(), Map.of());
        assertThat(positionOnly.statusCode()).isEqualTo(200);
        reviewTaskVersion = MAPPER.readTree(positionOnly.body()).path("data").path("version").asLong();

        // Outsider & non-manager member denied from reviewing
        HttpResponse<String> outsiderReview = send("POST", "/api/v1/tasks/" + taskId + "/workflow/review",
                Map.of("submissionId", submissionId, "expectedTaskVersion", reviewTaskVersion, "requestId", UUID.randomUUID().toString(),
                        "decision", "ACCEPT", "qualityRating", 4, "feedback", "Good", "evidenceSummary", "Checked"),
                outsider.jwt(), Map.of());
        assertThat(outsiderReview.statusCode()).isEqualTo(403);

        HttpResponse<String> memberSelfReview = send("POST", "/api/v1/tasks/" + taskId + "/workflow/review",
                Map.of("submissionId", submissionId, "expectedTaskVersion", reviewTaskVersion, "requestId", UUID.randomUUID().toString(),
                        "decision", "ACCEPT", "qualityRating", 5, "feedback", "Self rate", "evidenceSummary", "Self check"),
                assignee.jwt(), Map.of());
        assertThat(memberSelfReview.statusCode()).isEqualTo(403);

        // PM reviews 4 days later (Nov 18, AFTER Nov 16 dueDate), but submission was Nov 14 -> still ON_TIME!
        testClock.setInstant(Instant.parse("2026-11-18T12:00:00Z"));
        HttpResponse<String> acceptResp = send("POST", "/api/v1/tasks/" + taskId + "/workflow/review",
                Map.of(
                        "submissionId", submissionId,
                        "expectedTaskVersion", reviewTaskVersion,
                        "requestId", UUID.randomUUID().toString(),
                        "decision", "ACCEPT",
                        "qualityRating", 4,
                        "feedback", "Meets all agreed criteria on first pass",
                        "evidenceSummary", "Verified spec-proof.pdf against checklist"),
                manager1.jwt(), Map.of());
        assertThat(acceptResp.statusCode()).as(acceptResp.body()).isEqualTo(200);
        JsonNode acceptData = MAPPER.readTree(acceptResp.body()).path("data");
        assertThat(acceptData.path("state").asText()).isEqualTo("DONE");
        assertThat(acceptData.path("evidenceStatus").asText()).isEqualTo("INDEPENDENT_ACCEPTED");
        assertThat(acceptData.path("timelinessStatus").asText()).isEqualTo("ON_TIME");
        assertThat(acceptData.path("onTime").asBoolean()).isTrue();
        assertThat(acceptData.path("submittedAt").asText()).isEqualTo(BASE_TIME.toString());
        assertThat(acceptData.path("reviewedAt").asText()).isEqualTo("2026-11-18T12:00:00Z");
        assertThat(acceptData.path("completedAt").asText()).isEqualTo("2026-11-18T12:00:00Z");

        // Now test own-task SELF_ATTEST blocked when extra active manager exists, then allowed when sole active manager
        JsonNode mgrTask = createAndStartTask(manager1.jwt(), projectId, manager1.id(), null);
        long mgrTaskId = mgrTask.path("id").asLong();
        long mgrVer = mgrTask.path("version").asLong();

        HttpResponse<String> mgrSubmit = send("POST", "/api/v1/tasks/" + mgrTaskId + "/workflow/submit",
                Map.of("expectedTaskVersion", mgrVer, "requestId", UUID.randomUUID().toString(), "summary", "Manager own work"),
                manager1.jwt(), Map.of());
        assertThat(mgrSubmit.statusCode()).isEqualTo(200);
        long mgrSubId = MAPPER.readTree(mgrSubmit.body()).path("data").path("submissionId").asLong();
        mgrVer = MAPPER.readTree(mgrSubmit.body()).path("data").path("taskVersion").asLong();

        // Own numeric ACCEPT always forbidden
        HttpResponse<String> ownAccept = send("POST", "/api/v1/tasks/" + mgrTaskId + "/workflow/review",
                Map.of("submissionId", mgrSubId, "expectedTaskVersion", mgrVer, "requestId", UUID.randomUUID().toString(),
                        "decision", "ACCEPT", "qualityRating", 5, "feedback", "Self rate", "evidenceSummary", "None"),
                manager1.jwt(), Map.of());
        assertThat(ownAccept.statusCode()).isEqualTo(403);

        // SELF_ATTEST blocked while manager2 is active
        HttpResponse<String> blockedSelfAttest = send("POST", "/api/v1/tasks/" + mgrTaskId + "/workflow/review",
                Map.of("submissionId", mgrSubId, "expectedTaskVersion", mgrVer, "requestId", UUID.randomUUID().toString(),
                        "decision", "SELF_ATTEST", "feedback", "Sole reason"),
                manager1.jwt(), Map.of());
        assertThat(blockedSelfAttest.statusCode()).isEqualTo(409);

        // Remove manager2 -> manager1 is now sole active manager; numeric rating on SELF_ATTEST still rejected (400), null rating succeeds!
        projectService.removeMember(projectId, manager2.id(), manager1.id());
        HttpResponse<String> ratedSelfAttest = send("POST", "/api/v1/tasks/" + mgrTaskId + "/workflow/review",
                Map.of("submissionId", mgrSubId, "expectedTaskVersion", mgrVer, "requestId", UUID.randomUUID().toString(),
                        "decision", "SELF_ATTEST", "qualityRating", 5, "feedback", "Sole reason"),
                manager1.jwt(), Map.of());
        assertThat(ratedSelfAttest.statusCode()).isEqualTo(400);

        HttpResponse<String> okSelfAttest = send("POST", "/api/v1/tasks/" + mgrTaskId + "/workflow/review",
                Map.of("submissionId", mgrSubId, "expectedTaskVersion", mgrVer, "requestId", UUID.randomUUID().toString(),
                        "decision", "SELF_ATTEST", "feedback", "Completed as sole active manager"),
                manager1.jwt(), Map.of());
        assertThat(okSelfAttest.statusCode()).as(okSelfAttest.body()).isEqualTo(200);
        JsonNode saData = MAPPER.readTree(okSelfAttest.body()).path("data");
        assertThat(saData.path("evidenceStatus").asText()).isEqualTo("SELF_ATTESTED");
        assertThat(saData.path("timelinessStatus").asText()).isEqualTo("NO_DUE_DATE");
        assertThat(saData.path("onTime").isNull()).isTrue();
        Long evalRatingInDb = jdbc.queryForObject(
                "SELECT quality_rating FROM public.task_evaluations WHERE id = ?",
                Long.class, saData.path("evaluationId").asLong());
        assertThat(evalRatingInDb).isNull();
    }

    @Test
    void evaluationOrJournalFailure_rollsBackDoneAtomically() throws Exception {
        UserFixture manager = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture assignee = createUser(true, AccountLifecycle.ACTIVE);
        long projectId = createProject(manager.jwt(), "UTC");
        addMember(projectId, assignee.id(), MemberRole.MEMBER);

        JsonNode task = createAndStartTask(manager.jwt(), projectId, assignee.id(), "2026-11-16");
        long taskId = task.path("id").asLong();
        long version = task.path("version").asLong();

        HttpResponse<String> submitResp = send("POST", "/api/v1/tasks/" + taskId + "/workflow/submit",
                Map.of("expectedTaskVersion", version, "requestId", UUID.randomUUID().toString(), "summary", "Ready for check"),
                assignee.jwt(), Map.of());
        assertThat(submitResp.statusCode()).isEqualTo(200);
        long submissionId = MAPPER.readTree(submitResp.body()).path("data").path("submissionId").asLong();
        long reviewVer = MAPPER.readTree(submitResp.body()).path("data").path("taskVersion").asLong();

        // Inject real PostgreSQL failure on task_workflow_journal insert
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION public.p502_fail_journal_insert() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'injected journal failure for task %', NEW.task_id;
                END;
                $$ LANGUAGE plpgsql;
                CREATE TRIGGER trg_p502_fail_journal
                BEFORE INSERT ON public.task_workflow_journal
                FOR EACH ROW EXECUTE FUNCTION public.p502_fail_journal_insert();
                """);
        try {
            HttpResponse<String> reviewResp = send("POST", "/api/v1/tasks/" + taskId + "/workflow/review",
                    Map.of(
                            "submissionId", submissionId,
                            "expectedTaskVersion", reviewVer,
                            "requestId", UUID.randomUUID().toString(),
                            "decision", "ACCEPT",
                            "qualityRating", 4,
                            "feedback", "Good work",
                            "evidenceSummary", "Checked"),
                    manager.jwt(), Map.of());
            assertThat(reviewResp.statusCode()).isGreaterThanOrEqualTo(400);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_p502_fail_journal ON public.task_workflow_journal");
            jdbc.execute("DROP FUNCTION IF EXISTS public.p502_fail_journal_insert()");
        }

        // Verify complete rollback: task remains REVIEW, completed_at NULL, zero reviews/evaluations/journals!
        TaskEntity afterRollback = taskRepository.findById(taskId).orElseThrow();
        assertThat(afterRollback.getStatus()).isEqualTo(TaskStatus.REVIEW);
        assertThat(afterRollback.getCompletedAt()).isNull();
        assertThat(afterRollback.getCurrentEvaluationId()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.task_reviews WHERE task_id = ?", Integer.class, taskId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.task_evaluations WHERE task_id = ?", Integer.class, taskId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.task_workflow_journal WHERE task_id = ?", Integer.class, taskId))
                .isZero();
    }

    @Test
    void concurrentReviewersRace_and_withdrawVsReviewRace_and_supersedeRace_eachHaveOneWinner() throws Exception {
        UserFixture manager1 = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture manager2 = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture assignee = createUser(true, AccountLifecycle.ACTIVE);
        long projectId = createProject(manager1.jwt(), "UTC");
        addMember(projectId, manager2.id(), MemberRole.MANAGER);
        addMember(projectId, assignee.id(), MemberRole.MEMBER);

        // 1) Two concurrent reviewers racing on same submission -> 1 winner (200), 1 loser (409)
        JsonNode task1 = createAndStartTask(manager1.jwt(), projectId, assignee.id(), "2026-11-20");
        long t1Id = task1.path("id").asLong();
        HttpResponse<String> sub1Resp = send("POST", "/api/v1/tasks/" + t1Id + "/workflow/submit",
                Map.of("expectedTaskVersion", task1.path("version").asLong(), "requestId", UUID.randomUUID().toString(), "summary", "Race 1"),
                assignee.jwt(), Map.of());
        long sub1Id = MAPPER.readTree(sub1Resp.body()).path("data").path("submissionId").asLong();
        long ver1 = MAPPER.readTree(sub1Resp.body()).path("data").path("taskVersion").asLong();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready1 = new CountDownLatch(2);
            CountDownLatch go1 = new CountDownLatch(1);
            Future<Integer> f1 = pool.submit(() -> {
                ready1.countDown();
                go1.await(5, TimeUnit.SECONDS);
                return send("POST", "/api/v1/tasks/" + t1Id + "/workflow/review",
                        Map.of("submissionId", sub1Id, "expectedTaskVersion", ver1, "requestId", UUID.randomUUID().toString(),
                                "decision", "ACCEPT", "qualityRating", 4, "feedback", "Mgr1", "evidenceSummary", "Proof"),
                        manager1.jwt(), Map.of()).statusCode();
            });
            Future<Integer> f2 = pool.submit(() -> {
                ready1.countDown();
                go1.await(5, TimeUnit.SECONDS);
                return send("POST", "/api/v1/tasks/" + t1Id + "/workflow/review",
                        Map.of("submissionId", sub1Id, "expectedTaskVersion", ver1, "requestId", UUID.randomUUID().toString(),
                                "decision", "ACCEPT", "qualityRating", 5, "feedback", "Mgr2", "evidenceSummary", "Proof"),
                        manager2.jwt(), Map.of()).statusCode();
            });
            assertThat(ready1.await(5, TimeUnit.SECONDS)).isTrue();
            go1.countDown();
            List<Integer> codes1 = List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS));
            assertThat(codes1).containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public.task_evaluations WHERE task_id = ?", Integer.class, t1Id))
                    .isEqualTo(1);

            // 2) Concurrent superseders racing on same evaluation -> 1 winner (200), 1 loser (409)
            TaskEntity doneTask1 = taskRepository.findById(t1Id).orElseThrow();
            long eval1Id = doneTask1.getCurrentEvaluationId();
            long doneVer1 = doneTask1.effectiveVersion();
            CountDownLatch readySup = new CountDownLatch(2);
            CountDownLatch goSup = new CountDownLatch(1);
            Future<Integer> s1 = pool.submit(() -> {
                readySup.countDown();
                goSup.await(5, TimeUnit.SECONDS);
                return send("POST", "/api/v1/tasks/" + t1Id + "/workflow/supersede",
                        Map.of("submissionId", sub1Id, "expectedEvaluationId", eval1Id, "expectedEvaluationVersion", 1,
                                "expectedTaskVersion", doneVer1, "requestId", UUID.randomUUID().toString(),
                                "qualityRating", 3, "reason", "Sup1", "evidenceSummary", "Recheck"),
                        manager1.jwt(), Map.of()).statusCode();
            });
            Future<Integer> s2 = pool.submit(() -> {
                readySup.countDown();
                goSup.await(5, TimeUnit.SECONDS);
                return send("POST", "/api/v1/tasks/" + t1Id + "/workflow/supersede",
                        Map.of("submissionId", sub1Id, "expectedEvaluationId", eval1Id, "expectedEvaluationVersion", 1,
                                "expectedTaskVersion", doneVer1, "requestId", UUID.randomUUID().toString(),
                                "qualityRating", 2, "reason", "Sup2", "evidenceSummary", "Recheck"),
                        manager2.jwt(), Map.of()).statusCode();
            });
            assertThat(readySup.await(5, TimeUnit.SECONDS)).isTrue();
            goSup.countDown();
            List<Integer> supCodes = List.of(s1.get(15, TimeUnit.SECONDS), s2.get(15, TimeUnit.SECONDS));
            assertThat(supCodes).containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM public.task_evaluations WHERE task_id = ? AND invalidated = false",
                    Integer.class, t1Id)).isEqualTo(1);

            // 3) Withdraw vs review race -> 1 winner (200), 1 loser (409), no fake RETURN on withdrawal
            JsonNode task2 = createAndStartTask(manager1.jwt(), projectId, assignee.id(), "2026-11-20");
            long t2Id = task2.path("id").asLong();
            HttpResponse<String> sub2Resp = send("POST", "/api/v1/tasks/" + t2Id + "/workflow/submit",
                    Map.of("expectedTaskVersion", task2.path("version").asLong(), "requestId", UUID.randomUUID().toString(), "summary", "Race 2"),
                    assignee.jwt(), Map.of());
            long sub2Id = MAPPER.readTree(sub2Resp.body()).path("data").path("submissionId").asLong();
            long ver2 = MAPPER.readTree(sub2Resp.body()).path("data").path("taskVersion").asLong();

            CountDownLatch ready2 = new CountDownLatch(2);
            CountDownLatch go2 = new CountDownLatch(1);
            Future<Integer> wFut = pool.submit(() -> {
                ready2.countDown();
                go2.await(5, TimeUnit.SECONDS);
                return send("POST", "/api/v1/tasks/" + t2Id + "/workflow/withdraw",
                        Map.of("submissionId", sub2Id, "expectedTaskVersion", ver2, "requestId", UUID.randomUUID().toString(),
                                "reason", "Forgot unit tests"),
                        assignee.jwt(), Map.of()).statusCode();
            });
            Future<Integer> rFut = pool.submit(() -> {
                ready2.countDown();
                go2.await(5, TimeUnit.SECONDS);
                return send("POST", "/api/v1/tasks/" + t2Id + "/workflow/review",
                        Map.of("submissionId", sub2Id, "expectedTaskVersion", ver2, "requestId", UUID.randomUUID().toString(),
                                "decision", "ACCEPT", "qualityRating", 4, "feedback", "Accepted", "evidenceSummary", "Verified"),
                        manager1.jwt(), Map.of()).statusCode();
            });
            assertThat(ready2.await(5, TimeUnit.SECONDS)).isTrue();
            go2.countDown();
            int wCode = wFut.get(15, TimeUnit.SECONDS);
            int rCode = rFut.get(15, TimeUnit.SECONDS);
            assertThat(List.of(wCode, rCode)).containsExactlyInAnyOrder(200, 409);
            if (wCode == 200) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM public.task_reviews WHERE task_id = ?", Integer.class, t2Id))
                        .isZero();
                assertThat(jdbc.queryForObject("SELECT status FROM public.task_submissions WHERE id = ?", String.class, sub2Id))
                        .isEqualTo("WITHDRAWN");
            } else {
                assertThat(jdbc.queryForObject("SELECT status FROM public.task_submissions WHERE id = ?", String.class, sub2Id))
                        .isEqualTo("ACCEPTED");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void returnedThenLateResubmit_and_withdrawResubmitNewTimestamp_and_supersedeSelfAttestedPreservesTime_evenInCompletedSprint()
            throws Exception {
        UserFixture manager1 = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture manager2 = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture assignee = createUser(true, AccountLifecycle.ACTIVE);
        long projectId = createProject(manager1.jwt(), "UTC");
        addMember(projectId, assignee.id(), MemberRole.MEMBER);

        // Task due Nov 14 23:59:59.999999Z
        JsonNode task = createAndStartTask(manager1.jwt(), projectId, assignee.id(), "2026-11-14");
        long taskId = task.path("id").asLong();
        long ver = task.path("version").asLong();

        // 1) Submit on Nov 14 -> Withdraw -> later resubmit gets newer submittedAt (no fake RETURN review!)
        testClock.setInstant(Instant.parse("2026-11-14T10:00:00Z"));
        HttpResponse<String> s1 = send("POST", "/api/v1/tasks/" + taskId + "/workflow/submit",
                Map.of("expectedTaskVersion", ver, "requestId", UUID.randomUUID().toString(), "summary", "Initial draft"),
                assignee.jwt(), Map.of());
        assertThat(s1.statusCode()).isEqualTo(200);
        long sub1Id = MAPPER.readTree(s1.body()).path("data").path("submissionId").asLong();
        ver = MAPPER.readTree(s1.body()).path("data").path("taskVersion").asLong();

        testClock.setInstant(Instant.parse("2026-11-14T11:00:00Z"));
        HttpResponse<String> w1 = send("POST", "/api/v1/tasks/" + taskId + "/workflow/withdraw",
                Map.of("submissionId", sub1Id, "expectedTaskVersion", ver, "requestId", UUID.randomUUID().toString(), "reason", "Adding test"),
                assignee.jwt(), Map.of());
        assertThat(w1.statusCode()).isEqualTo(200);
        ver = MAPPER.readTree(w1.body()).path("data").path("taskVersion").asLong();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.task_reviews WHERE task_id = ?", Integer.class, taskId)).isZero();

        // Resubmit at Nov 14 15:00 -> Manager RETURNs
        testClock.setInstant(Instant.parse("2026-11-14T15:00:00Z"));
        HttpResponse<String> s2 = send("POST", "/api/v1/tasks/" + taskId + "/workflow/submit",
                Map.of("expectedTaskVersion", ver, "requestId", UUID.randomUUID().toString(), "summary", "Second submit"),
                assignee.jwt(), Map.of());
        long sub2Id = MAPPER.readTree(s2.body()).path("data").path("submissionId").asLong();
        ver = MAPPER.readTree(s2.body()).path("data").path("taskVersion").asLong();
        assertThat(MAPPER.readTree(s2.body()).path("data").path("submittedAt").asText()).isEqualTo("2026-11-14T15:00:00Z");

        HttpResponse<String> ret = send("POST", "/api/v1/tasks/" + taskId + "/workflow/review",
                Map.of("submissionId", sub2Id, "expectedTaskVersion", ver, "requestId", UUID.randomUUID().toString(),
                        "decision", "RETURN", "feedback", "Missing edge case verification"),
                manager1.jwt(), Map.of());
        assertThat(ret.statusCode()).isEqualTo(200);
        ver = MAPPER.readTree(ret.body()).path("data").path("taskVersion").asLong();

        // Resubmit on Nov 15 (LATE!) -> Accepted Nov 15 17:00 -> onTime MUST be false!
        testClock.setInstant(Instant.parse("2026-11-15T09:00:00Z"));
        HttpResponse<String> s3 = send("POST", "/api/v1/tasks/" + taskId + "/workflow/submit",
                Map.of("expectedTaskVersion", ver, "requestId", UUID.randomUUID().toString(), "summary", "Fixed edge cases"),
                assignee.jwt(), Map.of());
        long sub3Id = MAPPER.readTree(s3.body()).path("data").path("submissionId").asLong();
        ver = MAPPER.readTree(s3.body()).path("data").path("taskVersion").asLong();

        testClock.setInstant(Instant.parse("2026-11-15T17:00:00Z"));
        HttpResponse<String> acc = send("POST", "/api/v1/tasks/" + taskId + "/workflow/review",
                Map.of("submissionId", sub3Id, "expectedTaskVersion", ver, "requestId", UUID.randomUUID().toString(),
                        "decision", "ACCEPT", "qualityRating", 3, "feedback", "Passed after minor fixes", "evidenceSummary", "Edge cases verified"),
                manager1.jwt(), Map.of());
        assertThat(acc.statusCode()).isEqualTo(200);
        JsonNode accData = MAPPER.readTree(acc.body()).path("data");
        assertThat(accData.path("onTime").asBoolean()).isFalse();
        assertThat(accData.path("timelinessStatus").asText()).isEqualTo("LATE");

        // 2) Sole manager SELF_ATTEST on Nov 14 (on time), then second manager joins & sprint completes,
        // then manager2 supersedes SELF_ATTESTED on Nov 16 -> preserves acceptedSubmittedAt, due, onTime, originalCompletedAt!
        testClock.setInstant(Instant.parse("2026-11-14T12:00:00Z"));
        JsonNode saTask = createAndStartTask(manager1.jwt(), projectId, manager1.id(), "2026-11-14");
        long saTaskId = saTask.path("id").asLong();
        HttpResponse<String> saSub = send("POST", "/api/v1/tasks/" + saTaskId + "/workflow/submit",
                Map.of("expectedTaskVersion", saTask.path("version").asLong(), "requestId", UUID.randomUUID().toString(), "summary", "Sole manager work"),
                manager1.jwt(), Map.of());
        long saSubId = MAPPER.readTree(saSub.body()).path("data").path("submissionId").asLong();
        long saVer = MAPPER.readTree(saSub.body()).path("data").path("taskVersion").asLong();

        HttpResponse<String> saDone = send("POST", "/api/v1/tasks/" + saTaskId + "/workflow/review",
                Map.of("submissionId", saSubId, "expectedTaskVersion", saVer, "requestId", UUID.randomUUID().toString(),
                        "decision", "SELF_ATTEST", "feedback", "Only manager at the time"),
                manager1.jwt(), Map.of());
        assertThat(saDone.statusCode()).isEqualTo(200);
        JsonNode saDoneData = MAPPER.readTree(saDone.body()).path("data");
        long saEvalId = saDoneData.path("evaluationId").asLong();
        long saDoneVer = saDoneData.path("taskVersion").asLong();
        String originalCompletedAt = saDoneData.path("completedAt").asText();

        // Add manager2 and move task into a COMPLETED sprint (ordinary mutations fail, explicit supersede succeeds!)
        addMember(projectId, manager2.id(), MemberRole.MANAGER);
        SprintEntity completedSprint = sprintRepository.saveAndFlush(SprintEntity.builder()
                .projectId(projectId)
                .name("Closed Sprint")
                .status(SprintStatus.COMPLETED)
                .startDate(LocalDate.of(2026, 11, 1))
                .endDate(LocalDate.of(2026, 11, 15))
                .build());
        jdbc.update("UPDATE public.tasks SET sprint_id = ? WHERE id = ?", completedSprint.getId(), saTaskId);

        testClock.setInstant(Instant.parse("2026-11-16T08:30:00Z"));
        HttpResponse<String> supersedeResp = send("POST", "/api/v1/tasks/" + saTaskId + "/workflow/supersede",
                Map.of(
                        "submissionId", saSubId,
                        "expectedEvaluationId", saEvalId,
                        "expectedEvaluationVersion", 1,
                        "expectedTaskVersion", saDoneVer,
                        "requestId", UUID.randomUUID().toString(),
                        "qualityRating", 4,
                        "reason", "Independent audit of self-attested delivery",
                        "evidenceSummary", "Checked against quality-v1 level 4"),
                manager2.jwt(), Map.of());
        assertThat(supersedeResp.statusCode()).as(supersedeResp.body()).isEqualTo(200);
        JsonNode supData = MAPPER.readTree(supersedeResp.body()).path("data");
        assertThat(supData.path("evaluationVersion").asInt()).isEqualTo(2);
        assertThat(supData.path("evidenceStatus").asText()).isEqualTo("INDEPENDENT_ACCEPTED");
        assertThat(supData.path("onTime").asBoolean()).isTrue();
        assertThat(supData.path("submittedAt").asText()).isEqualTo("2026-11-14T12:00:00Z");
        assertThat(supData.path("completedAt").asText()).isEqualTo(originalCompletedAt);
        assertThat(supData.path("reviewedAt").asText()).isEqualTo("2026-11-16T08:30:00Z");

        String invReason = jdbc.queryForObject(
                "SELECT reason FROM public.task_evaluation_invalidations WHERE evaluation_id = ?",
                String.class, saEvalId);
        assertThat(invReason).isEqualTo("SUPERSEDED_BY_INDEPENDENT_REVIEW");
    }

    @Test
    void evidenceFileDeletion_preservesSnapshotAndRecordsUnavailable_and_foreignEvidenceRejected_and_reopenClearsRemovedAssignee()
            throws Exception {
        UserFixture manager = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture assignee = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture otherProjectUser = createUser(true, AccountLifecycle.ACTIVE);

        long projectA = createProject(manager.jwt(), "UTC");
        long projectB = createProject(otherProjectUser.jwt(), "UTC");
        addMember(projectA, assignee.id(), MemberRole.MEMBER);

        ProjectFileEntity fileA = createProjectFile(projectA, assignee.id(), "deliverable.zip");
        ProjectFileEntity fileB = createProjectFile(projectB, otherProjectUser.id(), "foreign-secret.pdf");

        JsonNode task = createAndStartTask(manager.jwt(), projectA, assignee.id(), "2026-11-20");
        long taskId = task.path("id").asLong();
        long ver = task.path("version").asLong();

        // Foreign file rejected
        HttpResponse<String> foreignSubmit = send("POST", "/api/v1/tasks/" + taskId + "/workflow/submit",
                Map.of("expectedTaskVersion", ver, "requestId", UUID.randomUUID().toString(),
                        "summary", "Trying foreign file", "evidenceProjectFileIds", List.of(fileB.getId())),
                assignee.jwt(), Map.of());
        assertThat(foreignSubmit.statusCode()).isEqualTo(403);

        // Submit with valid same-project file
        HttpResponse<String> validSubmit = send("POST", "/api/v1/tasks/" + taskId + "/workflow/submit",
                Map.of("expectedTaskVersion", ver, "requestId", UUID.randomUUID().toString(),
                        "summary", "Attached deliverable", "evidenceProjectFileIds", List.of(fileA.getId())),
                assignee.jwt(), Map.of());
        assertThat(validSubmit.statusCode()).isEqualTo(200);
        long subId = MAPPER.readTree(validSubmit.body()).path("data").path("submissionId").asLong();
        ver = MAPPER.readTree(validSubmit.body()).path("data").path("taskVersion").asLong();

        // Accept
        HttpResponse<String> acceptResp = send("POST", "/api/v1/tasks/" + taskId + "/workflow/review",
                Map.of("submissionId", subId, "expectedTaskVersion", ver, "requestId", UUID.randomUUID().toString(),
                        "decision", "ACCEPT", "qualityRating", 4, "feedback", "Verified", "evidenceSummary", "Checked deliverable.zip"),
                manager.jwt(), Map.of());
        assertThat(acceptResp.statusCode()).isEqualTo(200);
        ver = MAPPER.readTree(acceptResp.body()).path("data").path("taskVersion").asLong();

        // Delete fileA -> snapshot preserved, live_source_file_id becomes NULL, availability event recorded
        projectFileService.deleteFile(projectA, fileA.getId(), assignee.id());
        HttpResponse<String> wfAfterDelete = send("GET", "/api/v1/tasks/" + taskId + "/workflow", null, manager.jwt(), Map.of());
        assertThat(wfAfterDelete.statusCode()).isEqualTo(200);
        JsonNode evFile = MAPPER.readTree(wfAfterDelete.body()).path("data")
                .path("submissions").get(0).path("evidenceFiles").get(0);
        assertThat(evFile.path("originalFileId").asLong()).isEqualTo(fileA.getId());
        assertThat(evFile.path("originalName").asText()).isEqualTo("deliverable.zip");
        assertThat(evFile.path("available").asBoolean()).isFalse();
        assertThat(evFile.path("unavailableReason").asText()).isEqualTo("FILE_DELETED");
        assertThat(evFile.path("liveSourceFileId").isNull()).isTrue();

        // Remove assignee from project, then manager reopens DONE task -> live assignee cleared, assignmentVersion bumped, history kept
        long preAssignmentVer = taskRepository.findById(taskId).orElseThrow().effectiveAssignmentVersion();
        projectService.removeMember(projectA, assignee.id(), manager.id());

        HttpResponse<String> reopenResp = send("POST", "/api/v1/tasks/" + taskId + "/workflow/reopen",
                Map.of("expectedTaskVersion", ver, "requestId", UUID.randomUUID().toString(),
                        "reason", "Scope extension after delivery"),
                manager.jwt(), Map.of());
        assertThat(reopenResp.statusCode()).as(reopenResp.body()).isEqualTo(200);

        TaskEntity reopenedTask = taskRepository.findById(taskId).orElseThrow();
        assertThat(reopenedTask.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(reopenedTask.getCompletedAt()).isNull();
        assertThat(reopenedTask.getCurrentSubmissionId()).isNull();
        assertThat(reopenedTask.getCurrentEvaluationId()).isNull();
        assertThat(reopenedTask.getCompletionEvidenceStatus()).isNull();
        assertThat(reopenedTask.getAssigneeId()).isNull();
        assertThat(reopenedTask.effectiveAssignmentVersion()).isEqualTo(preAssignmentVer + 1L);

        // Historical evaluation remains invalidated with REOPENED, retaining original assigneeId
        String evalInvReason = jdbc.queryForObject(
                "SELECT reason FROM public.task_evaluation_invalidations WHERE task_id = ?",
                String.class, taskId);
        assertThat(evalInvReason).isEqualTo("REOPENED");
    }

    @Test
    void downstreamConsumerFailure_retainsDomainAcceptance_and_replaysIdempotently() throws Exception {
        UserFixture manager = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture assignee = createUser(true, AccountLifecycle.ACTIVE);
        long projectId = createProject(manager.jwt(), "UTC");
        addMember(projectId, assignee.id(), MemberRole.MEMBER);

        JsonNode task = createAndStartTask(manager.jwt(), projectId, assignee.id(), "2026-11-20");
        long taskId = task.path("id").asLong();
        HttpResponse<String> subResp = send("POST", "/api/v1/tasks/" + taskId + "/workflow/submit",
                Map.of("expectedTaskVersion", task.path("version").asLong(), "requestId", UUID.randomUUID().toString(), "summary", "Ready"),
                assignee.jwt(), Map.of());
        long subId = MAPPER.readTree(subResp.body()).path("data").path("submissionId").asLong();
        long ver = MAPPER.readTree(subResp.body()).path("data").path("taskVersion").asLong();

        // Cause downstream consumer to fail
        controllableConsumer.setFailNext(true);
        HttpResponse<String> accResp = send("POST", "/api/v1/tasks/" + taskId + "/workflow/review",
                Map.of("submissionId", subId, "expectedTaskVersion", ver, "requestId", UUID.randomUUID().toString(),
                        "decision", "ACCEPT", "qualityRating", 5, "feedback", "Great edge tests", "evidenceSummary", "All verified"),
                manager.jwt(), Map.of());
        assertThat(accResp.statusCode()).isEqualTo(200);

        // Domain acceptance committed! Journal entry is RETRY_PENDING (not SUCCEEDED!)
        TaskEntity doneTask = taskRepository.findById(taskId).orElseThrow();
        assertThat(doneTask.getStatus()).isEqualTo(TaskStatus.DONE);
        Map<String, Object> journalRow = jdbc.queryForMap(
                "SELECT event_id, status, attempts FROM public.task_workflow_journal WHERE task_id = ? AND event_type = 'TASK_EVALUATION_ACCEPTED'",
                taskId);
        UUID eventId = (UUID) journalRow.get("event_id");
        assertThat(journalRow.get("status")).isEqualTo("RETRY_PENDING");
        assertThat(((Number) journalRow.get("attempts")).intValue()).isEqualTo(1);
        assertThat(aiJournalListener.hasProcessed(eventId)).isFalse();
        assertThat(controllableConsumer.acceptedEventsCountFor(eventId)).isZero();

        // Recover consumer and replay
        controllableConsumer.setFailNext(false);
        int replayed = journalDispatcher.replayPendingForTask(taskId);
        assertThat(replayed).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM public.task_workflow_journal WHERE event_id = ?", String.class, eventId))
                .isEqualTo("SUCCEEDED");
        assertThat(controllableConsumer.acceptedEventsCountFor(eventId)).isEqualTo(1);
        assertThat(aiJournalListener.hasProcessed(eventId)).isTrue();

        // Idempotent second replay does nothing
        assertThat(journalDispatcher.replayPendingForTask(taskId)).isZero();
        assertThat(controllableConsumer.acceptedEventsCountFor(eventId)).isEqualTo(1);
    }

    @Test
    void aiConfirmationFencing_invalidatesOnVersionOrOwnerOrSessionChange_and_legacyDoneAndLegacyReviewHandledHonesty()
            throws Exception {
        UserFixture manager = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture assignee = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture otherUser = createUser(true, AccountLifecycle.ACTIVE);
        long projectId = createProject(manager.jwt(), "UTC");
        addMember(projectId, assignee.id(), MemberRole.MEMBER);

        // 1) AI confirmation fencing for submitTaskWork
        JsonNode task = createAndStartTask(manager.jwt(), projectId, assignee.id(), "2026-11-20");
        long taskId = task.path("id").asLong();
        long ver = task.path("version").asLong();

        var session = chatSessionService.createSession(assignee.id(), "Workflow AI session");
        ConfirmationRequiredDto pendingSubmit = ToolExecutionContext.callWith(
                new ToolExecutionContext.Context(assignee.id(), session.getId(), "submit my task"),
                () -> {
                    String raw = toolCallingRegistryService.execute(ToolExecutionRequest.builder()
                            .id("wf-submit-1")
                            .name("submitTaskWork")
                            .arguments(MAPPER.writeValueAsString(Map.of(
                                    "taskId", taskId,
                                    "expectedTaskVersion", ver,
                                    "summary", "AI-assisted submission")))
                            .build());
                    String actionId = MAPPER.readTree(raw).path("actionId").asText();
                    return pendingAiActionService.describe(actionId, assignee.id(), session.getId());
                });
        assertThat(pendingSubmit.confirmationRequired()).isTrue();

        // Wrong owner cannot confirm or consume action (cross-session -> 404, own-session -> 403)
        HttpResponse<String> wrongSessionConfirm = send("POST",
                "/api/v1/ai/sessions/" + session.getId() + "/actions/" + pendingSubmit.actionId() + "/confirm",
                Map.of("idempotencyKey", UUID.randomUUID().toString(), "payloadHash", pendingSubmit.payloadHash()),
                otherUser.jwt(), Map.of());
        assertThat(wrongSessionConfirm.statusCode()).isEqualTo(404);

        var otherSession = chatSessionService.createSession(otherUser.id(), "Other user session");
        HttpResponse<String> wrongOwnerConfirm = send("POST",
                "/api/v1/ai/sessions/" + otherSession.getId() + "/actions/" + pendingSubmit.actionId() + "/confirm",
                Map.of("idempotencyKey", UUID.randomUUID().toString(), "payloadHash", pendingSubmit.payloadHash()),
                otherUser.jwt(), Map.of());
        assertThat(wrongOwnerConfirm.statusCode()).isEqualTo(403);

        // Task mutated externally (version bumps) -> pending AI action becomes stale (409)
        taskCommandPort.patchTask(
                com.taskpilot.contracts.task.PatchTaskCommand.fromValues(
                        taskId, ver, UUID.randomUUID(), Map.of("title", "Updated before confirm")),
                manager.id());
        HttpResponse<String> staleConfirm = send("POST",
                "/api/v1/ai/sessions/" + session.getId() + "/actions/" + pendingSubmit.actionId() + "/confirm",
                Map.of("idempotencyKey", UUID.randomUUID().toString(), "payloadHash", pendingSubmit.payloadHash()),
                assignee.jwt(), Map.of());
        assertThat(staleConfirm.statusCode()).isEqualTo(409);

        // Fresh AI preview & explicit confirm succeeds
        long latestVer = taskRepository.findById(taskId).orElseThrow().effectiveVersion();
        ConfirmationRequiredDto freshSubmit = ToolExecutionContext.callWith(
                new ToolExecutionContext.Context(assignee.id(), session.getId(), "submit again"),
                () -> {
                    String raw = toolCallingRegistryService.execute(ToolExecutionRequest.builder()
                            .id("wf-submit-2")
                            .name("submitTaskWork")
                            .arguments(MAPPER.writeValueAsString(Map.of(
                                    "taskId", taskId,
                                    "expectedTaskVersion", latestVer,
                                    "summary", "Fresh AI submission")))
                            .build());
                    String actionId = MAPPER.readTree(raw).path("actionId").asText();
                    return pendingAiActionService.describe(actionId, assignee.id(), session.getId());
                });
        HttpResponse<String> okConfirm = send("POST",
                "/api/v1/ai/sessions/" + session.getId() + "/actions/" + freshSubmit.actionId() + "/confirm",
                Map.of("idempotencyKey", UUID.randomUUID().toString(), "payloadHash", freshSubmit.payloadHash()),
                assignee.jwt(), Map.of());
        assertThat(okConfirm.statusCode()).as(okConfirm.body()).isEqualTo(200);
        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.REVIEW);

        // 2) Legacy DONE remains LEGACY_UNVERIFIED without invented evaluation, cannot supersede, can reopen
        TaskEntity legacyDone = taskRepository.saveAndFlush(TaskEntity.builder()
                .projectId(projectId)
                .title("Legacy Done Task")
                .status(TaskStatus.DONE)
                .completedAt(Instant.parse("2026-10-01T00:00:00Z"))
                .completionEvidenceStatus(EvidenceStatus.LEGACY_UNVERIFIED)
                .reporterId(manager.id())
                .assigneeId(assignee.id())
                .build());
        HttpResponse<String> legacyWf = send("GET", "/api/v1/tasks/" + legacyDone.getId() + "/workflow", null, manager.jwt(), Map.of());
        assertThat(legacyWf.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(legacyWf.body()).path("data").path("completionEvidenceStatus").asText())
                .isEqualTo("LEGACY_UNVERIFIED");
        assertThat(MAPPER.readTree(legacyWf.body()).path("data").path("evaluations").size()).isZero();

        // 3) Legacy REVIEW without genuine submission rejects review/withdraw (409) and recovers explicitly
        TaskEntity legacyReview = taskRepository.saveAndFlush(TaskEntity.builder()
                .projectId(projectId)
                .title("Legacy Review Task")
                .status(TaskStatus.REVIEW)
                .reporterId(manager.id())
                .assigneeId(assignee.id())
                .build());
        HttpResponse<String> legacyReviewWf = send("GET", "/api/v1/tasks/" + legacyReview.getId() + "/workflow", null, manager.jwt(), Map.of());
        assertThat(MAPPER.readTree(legacyReviewWf.body()).path("data").path("legacyReviewMissingSubmission").asBoolean())
                .isTrue();

        HttpResponse<String> fakeReviewAttempt = send("POST", "/api/v1/tasks/" + legacyReview.getId() + "/workflow/review",
                Map.of("submissionId", 999999L, "expectedTaskVersion", legacyReview.effectiveVersion(),
                        "requestId", UUID.randomUUID().toString(), "decision", "ACCEPT", "qualityRating", 4,
                        "feedback", "No sub", "evidenceSummary", "None"),
                manager.jwt(), Map.of());
        assertThat(fakeReviewAttempt.statusCode()).isEqualTo(409);
        assertThat(fakeReviewAttempt.body()).contains("LEGACY_REVIEW_MISSING_SUBMISSION");

        HttpResponse<String> recoverResp = send("POST", "/api/v1/tasks/" + legacyReview.getId() + "/workflow/recover-legacy-review",
                Map.of("expectedTaskVersion", legacyReview.effectiveVersion(),
                        "requestId", UUID.randomUUID().toString(),
                        "reason", "Migrating legacy REVIEW task to genuine submission workflow"),
                manager.jwt(), Map.of());
        assertThat(recoverResp.statusCode()).as(recoverResp.body()).isEqualTo(200);
        assertThat(taskRepository.findById(legacyReview.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.IN_PROGRESS);

        // 4) Member removal while task is in REVIEW invalidates pending submission with MEMBER_REMOVED
        JsonNode reviewOrphanTask = createAndStartTask(manager.jwt(), projectId, assignee.id(), "2026-11-22");
        long orphanTaskId = reviewOrphanTask.path("id").asLong();
        HttpResponse<String> orphanSub = send("POST", "/api/v1/tasks/" + orphanTaskId + "/workflow/submit",
                Map.of("expectedTaskVersion", reviewOrphanTask.path("version").asLong(),
                        "requestId", UUID.randomUUID().toString(), "summary", "Pending before removal"),
                assignee.jwt(), Map.of());
        assertThat(orphanSub.statusCode()).isEqualTo(200);
        long orphanSubId = MAPPER.readTree(orphanSub.body()).path("data").path("submissionId").asLong();

        projectService.removeMember(projectId, assignee.id(), manager.id());
        TaskEntity clearedOrphan = taskRepository.findById(orphanTaskId).orElseThrow();
        assertThat(clearedOrphan.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(clearedOrphan.getAssigneeId()).isNull();
        assertThat(clearedOrphan.getCurrentSubmissionId()).isNull();
        Map<String, Object> invSub = jdbc.queryForMap(
                "SELECT status, invalidation_reason FROM public.task_submissions WHERE id = ?", orphanSubId);
        assertThat(invSub.get("status")).isEqualTo("INVALIDATED");
        assertThat(invSub.get("invalidation_reason")).isEqualTo("MEMBER_REMOVED");
    }

    @Test
    void flywayV47ToV48Upgrade_preservesLegacyDoneAndDoesNotFabricateReviewSubmission() throws Exception {
        String dbName = "v47_to_v48_upgrade";
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + dbName);
        }
        String upgradeUrl = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + dbName;

        Flyway v47 = MigrationDatabaseConfiguration
                .configure(Flyway.configure().dataSource(upgradeUrl, postgres.getUsername(), postgres.getPassword()))
                .target("47")
                .load();
        assertThat(v47.migrate().migrationsExecuted).isGreaterThan(0);

        long doneTaskId;
        long reviewTaskId;
        try (Connection c = DriverManager.getConnection(upgradeUrl, postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            long projectId;
            try (var rs = s.executeQuery("INSERT INTO public.projects(name) VALUES ('Legacy Upgrade Project') RETURNING id")) {
                rs.next();
                projectId = rs.getLong(1);
            }
            try (var rs = s.executeQuery("INSERT INTO public.tasks(project_id, title, status) VALUES ("
                    + projectId + ", 'Legacy Done Task', 'DONE'::public.task_status) RETURNING id")) {
                rs.next();
                doneTaskId = rs.getLong(1);
            }
            try (var rs = s.executeQuery("INSERT INTO public.tasks(project_id, title, status) VALUES ("
                    + projectId + ", 'Legacy Review Task', 'REVIEW'::public.task_status) RETURNING id")) {
                rs.next();
                reviewTaskId = rs.getLong(1);
            }
        }

        Flyway v48 = MigrationDatabaseConfiguration
                .configure(Flyway.configure().dataSource(upgradeUrl, postgres.getUsername(), postgres.getPassword()))
                .target("48")
                .load();
        assertThat(v48.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(v48.validateWithResult().validationSuccessful).isTrue();
        assertThat(v48.migrate().migrationsExecuted).isZero();

        try (Connection c = DriverManager.getConnection(upgradeUrl, postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            try (var rs = s.executeQuery(
                    "SELECT completion_evidence_status, current_submission_id, current_evaluation_id FROM public.tasks WHERE id = " + doneTaskId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("completion_evidence_status")).isEqualTo("LEGACY_UNVERIFIED");
                assertThat(rs.getObject("current_submission_id")).isNull();
                assertThat(rs.getObject("current_evaluation_id")).isNull();
            }
            try (var rs = s.executeQuery(
                    "SELECT completion_evidence_status, current_submission_id, current_evaluation_id FROM public.tasks WHERE id = " + reviewTaskId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("completion_evidence_status")).isNull();
                assertThat(rs.getObject("current_submission_id")).isNull();
                assertThat(rs.getObject("current_evaluation_id")).isNull();
            }
            try (var rs = s.executeQuery("SELECT count(*) FROM public.task_submissions")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
            try (var rs = s.executeQuery("SELECT count(*) FROM public.task_evaluations")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
            try (var rs = s.executeQuery(
                    "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tablename IN ("
                            + "'task_deadline_revisions','task_submissions','task_submission_evidence_files',"
                            + "'task_evidence_availability_events','task_reviews','task_evaluations',"
                            + "'task_evaluation_invalidations','task_workflow_journal') AND rowsecurity = true")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(8);
            }
            assertThat(v48.info().current().getVersion().getVersion()).isEqualTo("48");
        }
    }

    @Test
    void r1_managerOwnReturn_blockedAcrossRestAndConfirmedAi_preservingIndependentReturnOwnWithdrawAndSelfAttest()
            throws Exception {
        UserFixture manager1 = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture manager2 = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture soleManager = createUser(true, AccountLifecycle.ACTIVE);

        long projectId = createProject(manager1.jwt(), "UTC");
        addMember(projectId, manager2.id(), MemberRole.MANAGER);

        // Manager1 is assignee and submits own task
        JsonNode ownTask = createAndStartTask(manager1.jwt(), projectId, manager1.id(), "2026-11-22");
        long ownTaskId = ownTask.path("id").asLong();
        HttpResponse<String> submit1 = send("POST", "/api/v1/tasks/" + ownTaskId + "/workflow/submit",
                Map.of("expectedTaskVersion", ownTask.path("version").asLong(),
                        "requestId", UUID.randomUUID().toString(),
                        "summary", "Manager1 work ready"),
                manager1.jwt(), Map.of());
        assertThat(submit1.statusCode()).as(submit1.body()).isEqualTo(200);
        long sub1Id = MAPPER.readTree(submit1.body()).path("data").path("submissionId").asLong();
        long reviewVer1 = MAPPER.readTree(submit1.body()).path("data").path("taskVersion").asLong();

        // 1) REST self-RETURN is forbidden (403) with zero review/evaluation/journal/state mutations
        HttpResponse<String> restOwnReturn = send("POST", "/api/v1/tasks/" + ownTaskId + "/workflow/review",
                Map.of("submissionId", sub1Id,
                        "expectedTaskVersion", reviewVer1,
                        "requestId", UUID.randomUUID().toString(),
                        "decision", "RETURN",
                        "feedback", "Trying to return own submission"),
                manager1.jwt(), Map.of());
        assertThat(restOwnReturn.statusCode()).as(restOwnReturn.body()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.task_reviews WHERE task_id = ?", Integer.class, ownTaskId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.task_evaluations WHERE task_id = ?", Integer.class, ownTaskId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.task_workflow_journal WHERE task_id = ?", Integer.class, ownTaskId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM public.task_submissions WHERE id = ?", String.class, sub1Id)).isEqualTo("PENDING");
        TaskEntity afterRestSelfReturn = taskRepository.findById(ownTaskId).orElseThrow();
        assertThat(afterRestSelfReturn.getStatus()).isEqualTo(TaskStatus.REVIEW);
        assertThat(afterRestSelfReturn.getCurrentSubmissionId()).isEqualTo(sub1Id);

        // 2) Confirmed AI self-RETURN goes through the exact same shared policy and fails (403) cleanly
        var session = chatSessionService.createSession(manager1.id(), "R1 AI self-return test");
        ConfirmationRequiredDto pendingAiReturn = ToolExecutionContext.callWith(
                new ToolExecutionContext.Context(manager1.id(), session.getId(), "return my own task"),
                () -> {
                    String raw = toolCallingRegistryService.execute(ToolExecutionRequest.builder()
                            .id("r1-ai-return")
                            .name("reviewTaskSubmission")
                            .arguments(MAPPER.writeValueAsString(Map.of(
                                    "taskId", ownTaskId,
                                    "submissionId", sub1Id,
                                    "expectedTaskVersion", reviewVer1,
                                    "decision", "RETURN",
                                    "feedback", "AI self-return attempt")))
                            .build());
                    String actionId = MAPPER.readTree(raw).path("actionId").asText();
                    return pendingAiActionService.describe(actionId, manager1.id(), session.getId());
                });
        assertThat(pendingAiReturn.confirmationRequired()).isTrue();

        HttpResponse<String> aiConfirmSelfReturn = send("POST",
                "/api/v1/ai/sessions/" + session.getId() + "/actions/" + pendingAiReturn.actionId() + "/confirm",
                Map.of("idempotencyKey", UUID.randomUUID().toString(), "payloadHash", pendingAiReturn.payloadHash()),
                manager1.jwt(), Map.of());
        assertThat(aiConfirmSelfReturn.statusCode()).as(aiConfirmSelfReturn.body()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.task_reviews WHERE task_id = ?", Integer.class, ownTaskId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.task_evaluations WHERE task_id = ?", Integer.class, ownTaskId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.task_workflow_journal WHERE task_id = ?", Integer.class, ownTaskId)).isZero();
        assertThat(taskRepository.findById(ownTaskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.REVIEW);
        assertThat(jdbc.queryForObject("SELECT status FROM public.task_submissions WHERE id = ?", String.class, sub1Id)).isEqualTo("PENDING");

        // 3) Control: Manager1 uses own withdraw cleanly -> WITHDRAWN + IN_PROGRESS
        HttpResponse<String> ownWithdraw = send("POST", "/api/v1/tasks/" + ownTaskId + "/workflow/withdraw",
                Map.of("submissionId", sub1Id,
                        "expectedTaskVersion", reviewVer1,
                        "requestId", UUID.randomUUID().toString(),
                        "reason", "Refining notes before review"),
                manager1.jwt(), Map.of());
        assertThat(ownWithdraw.statusCode()).as(ownWithdraw.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM public.task_submissions WHERE id = ?", String.class, sub1Id)).isEqualTo("WITHDRAWN");
        TaskEntity afterWithdraw = taskRepository.findById(ownTaskId).orElseThrow();
        assertThat(afterWithdraw.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(afterWithdraw.getCurrentSubmissionId()).isNull();

        // 4) Control: Manager1 resubmits; independent Manager2 CAN return cleanly (200)
        HttpResponse<String> submit2 = send("POST", "/api/v1/tasks/" + ownTaskId + "/workflow/submit",
                Map.of("expectedTaskVersion", afterWithdraw.effectiveVersion(),
                        "requestId", UUID.randomUUID().toString(),
                        "summary", "Resubmitted for independent review"),
                manager1.jwt(), Map.of());
        assertThat(submit2.statusCode()).as(submit2.body()).isEqualTo(200);
        long sub2Id = MAPPER.readTree(submit2.body()).path("data").path("submissionId").asLong();
        long reviewVer2 = MAPPER.readTree(submit2.body()).path("data").path("taskVersion").asLong();

        HttpResponse<String> indepReturn = send("POST", "/api/v1/tasks/" + ownTaskId + "/workflow/review",
                Map.of("submissionId", sub2Id,
                        "expectedTaskVersion", reviewVer2,
                        "requestId", UUID.randomUUID().toString(),
                        "decision", "RETURN",
                        "feedback", "Independent manager corrective feedback"),
                manager2.jwt(), Map.of());
        assertThat(indepReturn.statusCode()).as(indepReturn.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM public.task_submissions WHERE id = ?", String.class, sub2Id)).isEqualTo("RETURNED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.task_reviews WHERE task_id = ? AND decision = 'RETURN'", Integer.class, ownTaskId)).isEqualTo(1);
        assertThat(taskRepository.findById(ownTaskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);

        // 5) Control: Sole active manager SELF_ATTEST remains valid and unmeasured (no rating)
        long soloProjectId = createProject(soleManager.jwt(), "UTC");
        JsonNode soloTask = createAndStartTask(soleManager.jwt(), soloProjectId, soleManager.id(), "2026-11-22");
        long soloTaskId = soloTask.path("id").asLong();
        HttpResponse<String> soloSubmit = send("POST", "/api/v1/tasks/" + soloTaskId + "/workflow/submit",
                Map.of("expectedTaskVersion", soloTask.path("version").asLong(),
                        "requestId", UUID.randomUUID().toString(),
                        "summary", "Solo work completed"),
                soleManager.jwt(), Map.of());
        assertThat(soloSubmit.statusCode()).isEqualTo(200);
        long soloSubId = MAPPER.readTree(soloSubmit.body()).path("data").path("submissionId").asLong();
        long soloVer = MAPPER.readTree(soloSubmit.body()).path("data").path("taskVersion").asLong();

        HttpResponse<String> selfAttest = send("POST", "/api/v1/tasks/" + soloTaskId + "/workflow/review",
                Map.of("submissionId", soloSubId,
                        "expectedTaskVersion", soloVer,
                        "requestId", UUID.randomUUID().toString(),
                        "decision", "SELF_ATTEST",
                        "feedback", "Sole active manager delivery"),
                soleManager.jwt(), Map.of());
        assertThat(selfAttest.statusCode()).as(selfAttest.body()).isEqualTo(200);
        TaskEntity soloDone = taskRepository.findById(soloTaskId).orElseThrow();
        assertThat(soloDone.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(soloDone.getCompletionEvidenceStatus()).isEqualTo(EvidenceStatus.SELF_ATTESTED);
        assertThat(jdbc.queryForObject(
                "SELECT quality_rating FROM public.task_evaluations WHERE id = ?",
                Integer.class, soloDone.getCurrentEvaluationId())).isNull();
    }

    @Test
    void r2_suspendedOrUnverifiedAssignee_blocksReviewDuringRecoveryDelay_andRaceSafeReconcilePreservesHistoricalDone()
            throws Exception {
        UserFixture manager = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture suspendedAssignee = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture unverifiedTarget = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture activeAssignee = createUser(true, AccountLifecycle.ACTIVE);

        long projectId = createProject(manager.jwt(), "UTC");
        addMember(projectId, suspendedAssignee.id(), MemberRole.MEMBER);
        addMember(projectId, unverifiedTarget.id(), MemberRole.MEMBER);
        addMember(projectId, activeAssignee.id(), MemberRole.MEMBER);

        // Historical legitimate DONE task completed BEFORE suspension (for replay & preservation controls)
        JsonNode histTask = createAndStartTask(manager.jwt(), projectId, suspendedAssignee.id(), "2026-11-22");
        long histTaskId = histTask.path("id").asLong();
        HttpResponse<String> histSub = send("POST", "/api/v1/tasks/" + histTaskId + "/workflow/submit",
                Map.of("expectedTaskVersion", histTask.path("version").asLong(),
                        "requestId", UUID.randomUUID().toString(),
                        "summary", "Historical deliverable"),
                suspendedAssignee.jwt(), Map.of());
        assertThat(histSub.statusCode()).isEqualTo(200);
        long histSubId = MAPPER.readTree(histSub.body()).path("data").path("submissionId").asLong();
        long histVer = MAPPER.readTree(histSub.body()).path("data").path("taskVersion").asLong();
        UUID histReviewReqId = UUID.randomUUID();
        Map<String, Object> histReviewBody = Map.of(
                "submissionId", histSubId,
                "expectedTaskVersion", histVer,
                "requestId", histReviewReqId.toString(),
                "decision", "ACCEPT",
                "qualityRating", 4,
                "feedback", "Accepted prior to suspension",
                "evidenceSummary", "Verified cleanly");
        HttpResponse<String> histAccept = send("POST", "/api/v1/tasks/" + histTaskId + "/workflow/review",
                histReviewBody, manager.jwt(), Map.of());
        assertThat(histAccept.statusCode()).as(histAccept.body()).isEqualTo(200);
        long histEvalId = MAPPER.readTree(histAccept.body()).path("data").path("evaluationId").asLong();

        // Pending task A (suspendedAssignee), task B (unverifiedTarget), task C (activeAssignee)
        JsonNode taskA = createAndStartTask(manager.jwt(), projectId, suspendedAssignee.id(), "2026-11-25");
        long taskAId = taskA.path("id").asLong();
        HttpResponse<String> subA = send("POST", "/api/v1/tasks/" + taskAId + "/workflow/submit",
                Map.of("expectedTaskVersion", taskA.path("version").asLong(),
                        "requestId", UUID.randomUUID().toString(),
                        "summary", "Pending submission A"),
                suspendedAssignee.jwt(), Map.of());
        assertThat(subA.statusCode()).isEqualTo(200);
        long subAId = MAPPER.readTree(subA.body()).path("data").path("submissionId").asLong();
        long taskAVer = MAPPER.readTree(subA.body()).path("data").path("taskVersion").asLong();

        JsonNode taskB = createAndStartTask(manager.jwt(), projectId, unverifiedTarget.id(), "2026-11-25");
        long taskBId = taskB.path("id").asLong();
        HttpResponse<String> subB = send("POST", "/api/v1/tasks/" + taskBId + "/workflow/submit",
                Map.of("expectedTaskVersion", taskB.path("version").asLong(),
                        "requestId", UUID.randomUUID().toString(),
                        "summary", "Pending submission B"),
                unverifiedTarget.jwt(), Map.of());
        assertThat(subB.statusCode()).isEqualTo(200);
        long subBId = MAPPER.readTree(subB.body()).path("data").path("submissionId").asLong();
        long taskBVer = MAPPER.readTree(subB.body()).path("data").path("taskVersion").asLong();

        JsonNode taskC = createAndStartTask(manager.jwt(), projectId, activeAssignee.id(), "2026-11-25");
        long taskCId = taskC.path("id").asLong();
        HttpResponse<String> subC = send("POST", "/api/v1/tasks/" + taskCId + "/workflow/submit",
                Map.of("expectedTaskVersion", taskC.path("version").asLong(),
                        "requestId", UUID.randomUUID().toString(),
                        "summary", "Pending submission C"),
                activeAssignee.jwt(), Map.of());
        assertThat(subC.statusCode()).isEqualTo(200);
        long subCId = MAPPER.readTree(subC.body()).path("data").path("submissionId").asLong();
        long taskCVer = MAPPER.readTree(subC.body()).path("data").path("taskVersion").asLong();

        // Seed committed security-first phase while delaying asynchronous project recovery
        jdbc.update("UPDATE public.users SET account_state = 'ADMIN_SUSPENDED', token_version = token_version + 1 WHERE id = ?",
                suspendedAssignee.id());
        jdbc.update("UPDATE public.users SET email_verified = false, token_version = token_version + 1 WHERE id = ?",
                unverifiedTarget.id());

        // 1) Manager ACCEPT & RETURN on suspended assignee must BOTH fail (409) with zero DB mutations
        HttpResponse<String> suspendedAccept = send("POST", "/api/v1/tasks/" + taskAId + "/workflow/review",
                Map.of("submissionId", subAId,
                        "expectedTaskVersion", taskAVer,
                        "requestId", UUID.randomUUID().toString(),
                        "decision", "ACCEPT",
                        "qualityRating", 5,
                        "feedback", "Should be fenced",
                        "evidenceSummary", "Proof"),
                manager.jwt(), Map.of());
        assertThat(suspendedAccept.statusCode()).as(suspendedAccept.body()).isEqualTo(409);

        HttpResponse<String> suspendedReturn = send("POST", "/api/v1/tasks/" + taskAId + "/workflow/review",
                Map.of("submissionId", subAId,
                        "expectedTaskVersion", taskAVer,
                        "requestId", UUID.randomUUID().toString(),
                        "decision", "RETURN",
                        "feedback", "Should also be fenced"),
                manager.jwt(), Map.of());
        assertThat(suspendedReturn.statusCode()).as(suspendedReturn.body()).isEqualTo(409);

        // Confirmed AI ACCEPT on suspended assignee also routes through shared policy -> 409
        var mgrSession = chatSessionService.createSession(manager.id(), "R2 AI fence");
        ConfirmationRequiredDto pendingAiAccept = ToolExecutionContext.callWith(
                new ToolExecutionContext.Context(manager.id(), mgrSession.getId(), "accept pending task A"),
                () -> {
                    String raw = toolCallingRegistryService.execute(ToolExecutionRequest.builder()
                            .id("r2-ai-accept")
                            .name("reviewTaskSubmission")
                            .arguments(MAPPER.writeValueAsString(Map.of(
                                    "taskId", taskAId,
                                    "submissionId", subAId,
                                    "expectedTaskVersion", taskAVer,
                                    "decision", "ACCEPT",
                                    "qualityRating", 5,
                                    "feedback", "AI accept during delay",
                                    "evidenceSummary", "Checked")))
                            .build());
                    String actionId = MAPPER.readTree(raw).path("actionId").asText();
                    return pendingAiActionService.describe(actionId, manager.id(), mgrSession.getId());
                });
        HttpResponse<String> aiConfirmAccept = send("POST",
                "/api/v1/ai/sessions/" + mgrSession.getId() + "/actions/" + pendingAiAccept.actionId() + "/confirm",
                Map.of("idempotencyKey", UUID.randomUUID().toString(), "payloadHash", pendingAiAccept.payloadHash()),
                manager.jwt(), Map.of());
        assertThat(aiConfirmAccept.statusCode()).as(aiConfirmAccept.body()).isEqualTo(409);

        // 2) Manager ACCEPT & RETURN on unverified assignee fail (409) with zero DB mutations
        HttpResponse<String> unverifiedAccept = send("POST", "/api/v1/tasks/" + taskBId + "/workflow/review",
                Map.of("submissionId", subBId,
                        "expectedTaskVersion", taskBVer,
                        "requestId", UUID.randomUUID().toString(),
                        "decision", "ACCEPT",
                        "qualityRating", 4,
                        "feedback", "Unverified assignee",
                        "evidenceSummary", "Proof"),
                manager.jwt(), Map.of());
        assertThat(unverifiedAccept.statusCode()).as(unverifiedAccept.body()).isEqualTo(409);

        for (long id : List.of(taskAId, taskBId)) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public.task_reviews WHERE task_id = ?", Integer.class, id)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public.task_evaluations WHERE task_id = ?", Integer.class, id)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public.task_workflow_journal WHERE task_id = ?", Integer.class, id)).isZero();
            assertThat(taskRepository.findById(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.REVIEW);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM public.task_submissions WHERE id = ?", String.class, subAId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM public.task_submissions WHERE id = ?", String.class, subBId)).isEqualTo("PENDING");

        // 3) Control: pre-suspension accepted receipt replays cleanly (200) and active assignee taskC succeeds (200)
        HttpResponse<String> histReplay = send("POST", "/api/v1/tasks/" + histTaskId + "/workflow/review",
                histReviewBody, manager.jwt(), Map.of());
        assertThat(histReplay.statusCode()).as(histReplay.body()).isEqualTo(200);
        assertThat(MAPPER.readTree(histReplay.body()).path("data").path("evaluationId").asLong()).isEqualTo(histEvalId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.task_evaluations WHERE task_id = ?", Integer.class, histTaskId)).isEqualTo(1);

        HttpResponse<String> activeAccept = send("POST", "/api/v1/tasks/" + taskCId + "/workflow/review",
                Map.of("submissionId", subCId,
                        "expectedTaskVersion", taskCVer,
                        "requestId", UUID.randomUUID().toString(),
                        "decision", "ACCEPT",
                        "qualityRating", 5,
                        "feedback", "Active verified assignee accepted",
                        "evidenceSummary", "Edge cases verified"),
                manager.jwt(), Map.of());
        assertThat(activeAccept.statusCode()).as(activeAccept.body()).isEqualTo(200);
        assertThat(taskRepository.findById(taskCId).orElseThrow().getStatus()).isEqualTo(TaskStatus.DONE);

        // 4) Synchronized race between manager review & ProjectAccountLifecycleAdapter recovery on task A:
        //    regardless of lock winner, review NEVER creates evaluation/DONE, and recovery invalidates subA & clears assignee!
        ProjectAccountLifecycleAdapter lifecycleAdapter = context.getBean(ProjectAccountLifecycleAdapter.class);
        long suspendedTokenVer = jdbc.queryForObject(
                "SELECT token_version FROM public.users WHERE id = ?", Long.class, suspendedAssignee.id());
        ExecutorService racePool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<Integer> reviewRaceFut = racePool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return send("POST", "/api/v1/tasks/" + taskAId + "/workflow/review",
                        Map.of("submissionId", subAId,
                                "expectedTaskVersion", taskAVer,
                                "requestId", UUID.randomUUID().toString(),
                                "decision", "ACCEPT",
                                "qualityRating", 5,
                                "feedback", "Race accept vs recovery",
                                "evidenceSummary", "Proof"),
                        manager.jwt(), Map.of()).statusCode();
            });
            Future<Void> reconcileFut = racePool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                lifecycleAdapter.reconcileUserLifecycle(new UserLifecycleRecoveryRequestedEvent(
                        UUID.randomUUID(),
                        suspendedAssignee.id(),
                        suspendedTokenVer,
                        "ADMIN_SUSPENDED",
                        txNowInstant()));
                return null;
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(reviewRaceFut.get(15, TimeUnit.SECONDS)).isEqualTo(409);
            reconcileFut.get(15, TimeUnit.SECONDS);
        } finally {
            racePool.shutdownNow();
        }

        // Post-recovery assertions: task A invalidated/unassigned; historical DONE untouched!
        TaskEntity recoveredA = taskRepository.findById(taskAId).orElseThrow();
        assertThat(recoveredA.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(recoveredA.getAssigneeId()).isNull();
        assertThat(recoveredA.getCurrentSubmissionId()).isNull();
        Map<String, Object> subARow = jdbc.queryForMap(
                "SELECT status, invalidation_reason FROM public.task_submissions WHERE id = ?", subAId);
        assertThat(subARow.get("status")).isEqualTo("INVALIDATED");
        assertThat(subARow.get("invalidation_reason")).isEqualTo("ACCOUNT_SUSPENDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.task_reviews WHERE task_id = ?", Integer.class, taskAId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.task_evaluations WHERE task_id = ?", Integer.class, taskAId)).isZero();

        TaskEntity preservedHist = taskRepository.findById(histTaskId).orElseThrow();
        assertThat(preservedHist.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(preservedHist.getCompletionEvidenceStatus()).isEqualTo(EvidenceStatus.INDEPENDENT_ACCEPTED);
        assertThat(jdbc.queryForObject(
                "SELECT invalidated FROM public.task_evaluations WHERE id = ?", Boolean.class, histEvalId)).isFalse();
    }

    private Instant txNowInstant() {
        return testClock.instant();
    }

    private HttpResponse<String> send(
            String method,
            String path,
            Object body,
            String jwt,
            Map<String, String> extraHeaders) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15));
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        if (jwt != null) {
            builder.header("Authorization", "Bearer " + jwt);
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
    static class FakeWorkflowConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(BASE_TIME, ZoneId.of("UTC"));
        }

        @Bean
        @Primary
        OneSignalService fakeOneSignal() {
            return new OneSignalService() {
                @Override
                public void sendNotificationToUser(String targetUserId, String title, String message, String linkAction) {
                }
            };
        }

        @Bean
        @Primary
        JavaMailSender fakeMailSender() {
            return mock(JavaMailSender.class);
        }

        @Bean
        @Primary
        StorageService fakeStorageService() {
            return mock(StorageService.class);
        }

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        ControllableJournalConsumer controllableJournalConsumer() {
            return new ControllableJournalConsumer();
        }
    }

    static class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    static class ControllableJournalConsumer implements TaskEvaluationJournalConsumer {
        private final AtomicBoolean failNext = new AtomicBoolean(false);
        private final List<UUID> acceptedEventIds = new CopyOnWriteArrayList<>();

        void setFailNext(boolean fail) {
            failNext.set(fail);
        }

        void reset() {
            failNext.set(false);
            acceptedEventIds.clear();
        }

        int acceptedEventsCountFor(UUID eventId) {
            return (int) acceptedEventIds.stream().filter(id -> id.equals(eventId)).count();
        }

        @Override
        public void onEvaluationAccepted(TaskEvaluationAccepted event) {
            if (failNext.get()) {
                throw new IllegalStateException("Simulated downstream telemetry outage");
            }
            acceptedEventIds.add(event.eventId());
        }

        @Override
        public void onEvaluationInvalidated(TaskEvaluationInvalidated event) {
            if (failNext.get()) {
                throw new IllegalStateException("Simulated downstream telemetry outage");
            }
        }
    }
}
