package com.taskpilot.app.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.drive.Drive;
import com.taskpilot.app.TaskPilotApplication;
import com.taskpilot.app.migration.MigrationDatabaseConfiguration;
import com.taskpilot.contracts.aiquery.dto.AiResourceRef;
import com.taskpilot.contracts.aiquery.dto.MemberWorkloadDto;
import com.taskpilot.contracts.aiquery.dto.TaskSummaryDto;
import com.taskpilot.contracts.aiquery.port.out.MemberAnalyticsPort;
import com.taskpilot.contracts.aiquery.port.out.ProjectInsightsPort;
import com.taskpilot.contracts.aiquery.port.out.SprintQueryPort;
import com.taskpilot.contracts.aiquery.port.out.TaskCommandPort;
import com.taskpilot.contracts.storage.StorageCleanupPort;
import com.taskpilot.contracts.storage.StorageCleanupRequest;
import com.taskpilot.contracts.task.PatchTaskCommand;
import com.taskpilot.contracts.task.ReopenTaskCommand;
import com.taskpilot.contracts.task.TaskEvaluationAccepted;
import com.taskpilot.contracts.task.TaskEvaluationInvalidated;
import com.taskpilot.contracts.task.TaskEvaluationJournalConsumer;
import com.taskpilot.contracts.task.TaskWorkflowCommandPort;
import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.notification.OneSignalService;
import com.taskpilot.infrastructure.storage.StorageService;
import com.taskpilot.infrastructure.storage.cleanup.StorageCleanupJobRepository;
import com.taskpilot.infrastructure.storage.cleanup.StorageCleanupService;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveFileDto;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveService;
import com.taskpilot.infrastructure.storage.range.ByteRange;
import com.taskpilot.infrastructure.storage.range.StorageDeleteResult;
import com.taskpilot.infrastructure.storage.range.StorageObjectStat;
import com.taskpilot.infrastructure.storage.range.StorageReadRequest;
import com.taskpilot.infrastructure.storage.range.StorageReadResult;
import com.taskpilot.projects.aiquery.ProjectAiResourceAccessAdapter;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.entity.SprintEntity;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.SprintStatus;
import com.taskpilot.projects.common.enums.TaskStatus;
import com.taskpilot.projects.common.repository.CommentRepository;
import com.taskpilot.projects.common.repository.ProjectDeletionAuditRepository;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.SprintClosureSnapshotRepository;
import com.taskpilot.projects.common.repository.SprintRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
import com.taskpilot.projects.realtime.adapter.ProjectRealtimeAccessAdapter;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.enums.UserStatus;
import com.taskpilot.users.common.repository.UserRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real PostgreSQL 17 + Flyway V49 behavioral suite for P5-03 Sprint Closure, Tombstones & Project Purge:
 * 1. Serialized sprint start race + DB partial unique index ux_sprints_one_active_per_project
 * 2. Atomic sprint completion preview/carryover (preserving REVIEW + submission) & snapshot + rollback
 * 3. Completed sprint freeze across REST/AI/workflow + allowed audited grade correction + successor task
 * 4. Subtree delete policy (completed descendant block, mixed history tombstone + invalidation, pure hard-delete)
 * 5. Exhaustive active read/count/comment/AI/notification tombstone exclusion + restricted audit endpoints
 * 6. Reopen revalidates removed/suspended assignee eligibility
 * 7. Read-only legacy dry-run repair manifest + zero writes + Flyway V48->V49 upgrade path
 * 8. Explicit permanent project purge confirmation/idempotency/rollback/full table wipe & deferred storage cleanup
 */
@Testcontainers
class SprintLifecyclePostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final String TEST_JWT_SECRET =
            "p5-03-sprint-lifecycle-test-secret-0123456789abcdef0123456789abcdef";
    private static final Instant BASE_TIME = Instant.parse("2026-11-17T08:00:00Z");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("taskpilot")
            .withUsername("test")
            .withPassword("test");

    private static ConfigurableApplicationContext context;
    private static String baseUrl;
    private static MutableClock testClock;
    private static ControllableJournalConsumer journalConsumer;
    private static FakeS3StorageService fakeS3;
    private static FakeGoogleDriveService fakeDrive;
    private static FaultInjectableStorageCleanupService faultStorageCleanup;

    private ProjectMemberRepository memberRepository;
    private ProjectFileRepository projectFileRepository;
    private SprintRepository sprintRepository;
    private SprintClosureSnapshotRepository closureSnapshotRepository;
    private TaskRepository taskRepository;
    private CommentRepository commentRepository;
    private ProjectDeletionAuditRepository deletionAuditRepository;
    private StorageCleanupJobRepository cleanupJobRepository;
    private JdbcTemplate jdbcTemplate;
    private TaskCommandPort taskCommandPort;
    private SprintQueryPort sprintQueryPort;
    private TaskWorkflowCommandPort taskWorkflowCommandPort;
    private ProjectInsightsPort projectInsightsPort;
    private MemberAnalyticsPort memberAnalyticsPort;
    private ProjectAiResourceAccessAdapter aiResourceAccessAdapter;
    private ProjectRealtimeAccessAdapter realtimeAccessAdapter;

    @BeforeAll
    static void startApp() throws Exception {
        try (Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'anon') THEN CREATE ROLE anon NOLOGIN; END IF; END $$;");
            s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'authenticated') THEN CREATE ROLE authenticated NOLOGIN; END IF; END $$;");
            s.execute("ALTER DEFAULT PRIVILEGES GRANT ALL ON TABLES TO anon, authenticated");
        }

        Flyway flyway = MigrationDatabaseConfiguration.configure(
                Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()))
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isGreaterThanOrEqualTo(8);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        context = new SpringApplicationBuilder(TaskPilotApplication.class, FakeSprintLifecycleConfig.class)
                .run(appArgs());
        int port = context.getEnvironment().getProperty("local.server.port", Integer.class);
        baseUrl = "http://127.0.0.1:" + port;
        testClock = context.getBean(MutableClock.class);
        journalConsumer = context.getBean(ControllableJournalConsumer.class);
        fakeS3 = context.getBean(FakeS3StorageService.class);
        fakeDrive = context.getBean(FakeGoogleDriveService.class);
        faultStorageCleanup = context.getBean(FaultInjectableStorageCleanupService.class);
    }

    @AfterAll
    static void stopApp() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void initBeans() {
        memberRepository = context.getBean(ProjectMemberRepository.class);
        projectFileRepository = context.getBean(ProjectFileRepository.class);
        sprintRepository = context.getBean(SprintRepository.class);
        closureSnapshotRepository = context.getBean(SprintClosureSnapshotRepository.class);
        taskRepository = context.getBean(TaskRepository.class);
        commentRepository = context.getBean(CommentRepository.class);
        deletionAuditRepository = context.getBean(ProjectDeletionAuditRepository.class);
        cleanupJobRepository = context.getBean(StorageCleanupJobRepository.class);
        jdbcTemplate = context.getBean(JdbcTemplate.class);
        taskCommandPort = context.getBean(TaskCommandPort.class);
        sprintQueryPort = context.getBean(SprintQueryPort.class);
        taskWorkflowCommandPort = context.getBean(TaskWorkflowCommandPort.class);
        projectInsightsPort = context.getBean(ProjectInsightsPort.class);
        memberAnalyticsPort = context.getBean(MemberAnalyticsPort.class);
        aiResourceAccessAdapter = context.getBean(ProjectAiResourceAccessAdapter.class);
        realtimeAccessAdapter = context.getBean(ProjectRealtimeAccessAdapter.class);

        testClock.setInstant(BASE_TIME);
        journalConsumer.reset();
        fakeS3.reset();
        fakeDrive.reset();
        faultStorageCleanup.failNextEnqueue.set(false);
    }

    record UserFixture(long id, String email, String jwt) {
    }

    private UserFixture createUser(boolean verified, AccountLifecycle lifecycle) throws Exception {
        String email = "p503-" + UUID.randomUUID() + "@example.com";
        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setFullName("P503 User " + email.substring(0, 8));
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

    private long createProject(String jwt) throws Exception {
        HttpResponse<String> resp = send("POST", "/api/v1/projects",
                Map.of("name", "P503 Proj " + UUID.randomUUID().toString().substring(0, 6), "timezoneId", "UTC"),
                jwt, Map.of());
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

    private JsonNode createSprint(String jwt, long projectId, String name) throws Exception {
        HttpResponse<String> resp = send("POST", "/api/v1/projects/" + projectId + "/sprints",
                Map.of("name", name, "goal", "Sprint goal for " + name,
                        "startDate", "2026-11-17", "endDate", "2026-11-24"),
                jwt, Map.of());
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(201);
        return MAPPER.readTree(resp.body()).path("data");
    }

    private JsonNode startSprint(String jwt, long projectId, long sprintId) throws Exception {
        HttpResponse<String> resp = send("POST", "/api/v1/projects/" + projectId + "/sprints/" + sprintId + "/start",
                null, jwt, Map.of());
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        return MAPPER.readTree(resp.body()).path("data");
    }

    private JsonNode createTask(String jwt, long projectId, Long sprintId, Long parentId, Long assigneeId, String title)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("title", title);
        body.put("description", "Spec for " + title);
        if (sprintId != null) {
            body.put("sprintId", sprintId);
        }
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        if (assigneeId != null) {
            body.put("assigneeId", assigneeId);
        }
        body.put("requestId", UUID.randomUUID().toString());
        HttpResponse<String> resp = send("POST", "/api/v1/tasks", body, jwt, Map.of());
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(201);
        return MAPPER.readTree(resp.body()).path("data");
    }

    private JsonNode patchTaskStatus(String jwt, long taskId, long expectedVersion, String status) throws Exception {
        HttpResponse<String> resp = send("PATCH", "/api/v1/tasks/" + taskId,
                Map.of("expectedTaskVersion", expectedVersion,
                        "requestId", UUID.randomUUID().toString(),
                        "status", status),
                jwt, Map.of());
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        return MAPPER.readTree(resp.body()).path("data");
    }

    private JsonNode submitTask(String jwt, long taskId, long expectedVersion, List<Long> fileIds) throws Exception {
        HttpResponse<String> resp = send("POST", "/api/v1/tasks/" + taskId + "/workflow/submit",
                Map.of("expectedTaskVersion", expectedVersion,
                        "requestId", UUID.randomUUID().toString(),
                        "summary", "Ready for review",
                        "evidenceProjectFileIds", fileIds),
                jwt, Map.of());
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        return MAPPER.readTree(resp.body()).path("data");
    }

    private JsonNode acceptReview(String jwt, long taskId, long submissionId, long expectedVersion, int rating)
            throws Exception {
        HttpResponse<String> resp = send("POST", "/api/v1/tasks/" + taskId + "/workflow/review",
                Map.of("submissionId", submissionId,
                        "expectedTaskVersion", expectedVersion,
                        "requestId", UUID.randomUUID().toString(),
                        "decision", "ACCEPT",
                        "qualityRating", rating,
                        "feedback", "Accepted in review",
                        "evidenceSummary", "Verified deliverable"),
                jwt, Map.of());
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        return MAPPER.readTree(resp.body()).path("data");
    }

    private ProjectFileEntity createProjectFile(long projectId, long uploaderId, String name) {
        String key = "projects/" + projectId + "/files/" + UUID.randomUUID() + "-" + name;
        fakeS3.objects.put("documents:" + key, ("file-" + name).getBytes(StandardCharsets.UTF_8));
        return projectFileRepository.saveAndFlush(ProjectFileEntity.builder()
                .projectId(projectId)
                .uploaderId(uploaderId)
                .fileName("stored-" + UUID.randomUUID() + "-" + name)
                .originalName(name)
                .fileSize(1024L)
                .contentType("application/pdf")
                .storageKey(key)
                .storageBucket("documents")
                .build());
    }

    @Test
    void concurrentStartSprint_onlyOneSucceeds_andPartialUniqueIndexEnforcesSingleActive() throws Exception {
        UserFixture manager = createUser(true, AccountLifecycle.ACTIVE);
        long projectId = createProject(manager.jwt());
        long s1Id = createSprint(manager.jwt(), projectId, "Sprint Alpha").path("id").asLong();
        long s2Id = createSprint(manager.jwt(), projectId, "Sprint Beta").path("id").asLong();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<Integer> c1 = () -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return send("POST", "/api/v1/projects/" + projectId + "/sprints/" + s1Id + "/start",
                        null, manager.jwt(), Map.of()).statusCode();
            };
            Callable<Integer> c2 = () -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return send("POST", "/api/v1/projects/" + projectId + "/sprints/" + s2Id + "/start",
                        null, manager.jwt(), Map.of()).statusCode();
            };
            Future<Integer> f1 = pool.submit(c1);
            Future<Integer> f2 = pool.submit(c2);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> codes = List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS));
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }

        List<SprintEntity> active = sprintRepository.findByProjectIdAndStatusOrderByIdAsc(projectId, SprintStatus.ACTIVE);
        assertThat(active).hasSize(1);
        long inactiveId = active.get(0).getId().equals(s1Id) ? s2Id : s1Id;

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE public.sprints SET status = 'ACTIVE' WHERE id = ?", inactiveId))
                .isInstanceOf(Exception.class);
    }

    @Test
    void atomicCompleteSprint_carryoverBacklogAndTargetSprint_preservesReviewAndSnapshot_andRollsBackOnError()
            throws Exception {
        UserFixture manager = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture member = createUser(true, AccountLifecycle.ACTIVE);
        long projectId = createProject(manager.jwt());
        addMember(projectId, member.id(), MemberRole.MEMBER);

        long otherProjectId = createProject(manager.jwt());
        long foreignSprintId = createSprint(manager.jwt(), otherProjectId, "Foreign Sprint").path("id").asLong();

        JsonNode s1 = createSprint(manager.jwt(), projectId, "Sprint 1");
        long s1Id = s1.path("id").asLong();
        JsonNode s2 = createSprint(manager.jwt(), projectId, "Sprint 2");
        long s2Id = s2.path("id").asLong();
        long s1Version = startSprint(manager.jwt(), projectId, s1Id).path("version").asLong();

        ProjectFileEntity evidence = createProjectFile(projectId, member.id(), "proof.pdf");

        JsonNode todoTask = createTask(manager.jwt(), projectId, s1Id, null, member.id(), "Todo Item");
        JsonNode inProgressTask = patchTaskStatus(
                manager.jwt(),
                createTask(manager.jwt(), projectId, s1Id, null, member.id(), "WIP Item").path("id").asLong(),
                0L,
                "IN_PROGRESS");

        JsonNode reviewStarted = patchTaskStatus(
                manager.jwt(),
                createTask(manager.jwt(), projectId, s1Id, null, member.id(), "Review Item").path("id").asLong(),
                0L,
                "IN_PROGRESS");
        JsonNode reviewSubmitted = submitTask(
                member.jwt(),
                reviewStarted.path("id").asLong(),
                reviewStarted.path("version").asLong(),
                List.of(evidence.getId()));
        long reviewTaskId = reviewStarted.path("id").asLong();
        long reviewSubmissionId = reviewSubmitted.path("submissionId").asLong();

        JsonNode doneStarted = patchTaskStatus(
                manager.jwt(),
                createTask(manager.jwt(), projectId, s1Id, null, member.id(), "Done Item").path("id").asLong(),
                0L,
                "IN_PROGRESS");
        JsonNode doneSub = submitTask(
                member.jwt(),
                doneStarted.path("id").asLong(),
                doneStarted.path("version").asLong(),
                List.of(evidence.getId()));
        acceptReview(manager.jwt(), doneStarted.path("id").asLong(), doneSub.path("submissionId").asLong(),
                doneSub.path("taskVersion").asLong(), 5);

        HttpResponse<String> previewResp = send("GET",
                "/api/v1/projects/" + projectId + "/sprints/" + s1Id + "/complete-preview",
                null, manager.jwt(), Map.of());
        assertThat(previewResp.statusCode()).as(previewResp.body()).isEqualTo(200);
        JsonNode preview = MAPPER.readTree(previewResp.body()).path("data");
        assertThat(preview.path("doneTasksRetained").asInt()).isEqualTo(1);
        assertThat(preview.path("unfinishedTotal").asInt()).isEqualTo(3);
        assertThat(preview.path("todoCount").asInt()).isEqualTo(1);
        assertThat(preview.path("inProgressCount").asInt()).isEqualTo(1);
        assertThat(preview.path("reviewCount").asInt()).isEqualTo(1);
        assertThat(preview.path("availableTargetSprints")).extracting(n -> n.path("id").asLong()).contains(s2Id);

        // Missing version -> 428
        HttpResponse<String> missingVersion = send("POST",
                "/api/v1/projects/" + projectId + "/sprints/" + s1Id + "/complete",
                Map.of("requestId", UUID.randomUUID().toString(),
                        "carryover", Map.of("destination", "BACKLOG")),
                manager.jwt(), Map.of());
        assertThat(missingVersion.statusCode()).isEqualTo(428);

        // Stale version -> 409
        HttpResponse<String> staleVersion = send("POST",
                "/api/v1/projects/" + projectId + "/sprints/" + s1Id + "/complete",
                Map.of("expectedSprintVersion", s1Version + 99,
                        "requestId", UUID.randomUUID().toString(),
                        "carryover", Map.of("destination", "BACKLOG")),
                manager.jwt(), Map.of());
        assertThat(staleVersion.statusCode()).isEqualTo(409);

        // Changed expectedUnfinishedTaskIds -> 409
        HttpResponse<String> stalePreviewIds = send("POST",
                "/api/v1/projects/" + projectId + "/sprints/" + s1Id + "/complete",
                Map.of("expectedSprintVersion", s1Version,
                        "requestId", UUID.randomUUID().toString(),
                        "carryover", Map.of("destination", "BACKLOG"),
                        "expectedUnfinishedTaskIds", List.of(todoTask.path("id").asLong())),
                manager.jwt(), Map.of());
        assertThat(stalePreviewIds.statusCode()).isEqualTo(409);

        // Foreign target sprint -> fails AND rolls back entire close/move/snapshot
        HttpResponse<String> foreignResp = send("POST",
                "/api/v1/projects/" + projectId + "/sprints/" + s1Id + "/complete",
                Map.of("expectedSprintVersion", s1Version,
                        "requestId", UUID.randomUUID().toString(),
                        "carryover", Map.of("destination", "SPRINT", "targetSprintId", foreignSprintId)),
                manager.jwt(), Map.of());
        assertThat(foreignResp.statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(sprintRepository.findById(s1Id).orElseThrow().getStatus()).isEqualTo(SprintStatus.ACTIVE);
        assertThat(closureSnapshotRepository.findBySprintId(s1Id)).isEmpty();
        assertThat(taskRepository.findActiveById(reviewTaskId).orElseThrow().getSprintId()).isEqualTo(s1Id);

        // Valid atomic completion carrying unfinished (TODO, IN_PROGRESS, REVIEW) to Sprint 2
        UUID completeReqId = UUID.randomUUID();
        Map<String, Object> validBody = Map.of(
                "expectedSprintVersion", s1Version,
                "requestId", completeReqId.toString(),
                "carryover", Map.of("destination", "SPRINT", "targetSprintId", s2Id),
                "expectedUnfinishedTaskIds", List.of(
                        todoTask.path("id").asLong(),
                        inProgressTask.path("id").asLong(),
                        reviewTaskId));
        HttpResponse<String> completedResp = send("POST",
                "/api/v1/projects/" + projectId + "/sprints/" + s1Id + "/complete",
                validBody, manager.jwt(), Map.of());
        assertThat(completedResp.statusCode()).as(completedResp.body()).isEqualTo(200);
        JsonNode completedSprint = MAPPER.readTree(completedResp.body()).path("data");
        assertThat(completedSprint.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(completedSprint.path("closureSnapshot").path("doneTasksRetained").asInt()).isEqualTo(1);
        assertThat(completedSprint.path("closureSnapshot").path("carriedOverTotal").asInt()).isEqualTo(3);
        assertThat(completedSprint.path("closureSnapshot").path("carriedOverReview").asInt()).isEqualTo(1);
        assertThat(completedSprint.path("closureSnapshot").path("targetSprintId").asLong()).isEqualTo(s2Id);

        // Retained DONE stays in s1; unfinished moved to s2; REVIEW keeps status & pending submission!
        assertThat(taskRepository.findActiveById(doneStarted.path("id").asLong()).orElseThrow().getSprintId()).isEqualTo(s1Id);
        TaskEntity movedReview = taskRepository.findActiveById(reviewTaskId).orElseThrow();
        assertThat(movedReview.getSprintId()).isEqualTo(s2Id);
        assertThat(movedReview.getStatus()).isEqualTo(TaskStatus.REVIEW);
        assertThat(movedReview.getCurrentSubmissionId()).isEqualTo(reviewSubmissionId);

        // Idempotent replay with same requestId & payload -> 200; mismatch -> 409; new requestId -> 409
        HttpResponse<String> replay = send("POST",
                "/api/v1/projects/" + projectId + "/sprints/" + s1Id + "/complete",
                validBody, manager.jwt(), Map.of());
        assertThat(replay.statusCode()).isEqualTo(200);

        HttpResponse<String> mismatch = send("POST",
                "/api/v1/projects/" + projectId + "/sprints/" + s1Id + "/complete",
                Map.of("expectedSprintVersion", s1Version,
                        "requestId", completeReqId.toString(),
                        "carryover", Map.of("destination", "BACKLOG")),
                manager.jwt(), Map.of());
        assertThat(mismatch.statusCode()).isEqualTo(409);

        HttpResponse<String> secondComplete = send("POST",
                "/api/v1/projects/" + projectId + "/sprints/" + s1Id + "/complete",
                Map.of("expectedSprintVersion", s1Version + 1,
                        "requestId", UUID.randomUUID().toString(),
                        "carryover", Map.of("destination", "BACKLOG")),
                manager.jwt(), Map.of());
        assertThat(secondComplete.statusCode()).isEqualTo(409);

        // Manager reviews the carried-over REVIEW task directly inside target Sprint 2!
        long s2Ver = startSprint(manager.jwt(), projectId, s2Id).path("version").asLong();
        JsonNode closedReviewDone = acceptReview(
                manager.jwt(), reviewTaskId, reviewSubmissionId, movedReview.getVersion(), 4);
        assertThat(closedReviewDone.path("state").asText()).isEqualTo("DONE");

        // Now complete Sprint 2 carrying remaining unfinished tasks (WIP + Todo) to BACKLOG
        HttpResponse<String> s2Complete = send("POST",
                "/api/v1/projects/" + projectId + "/sprints/" + s2Id + "/complete",
                Map.of("expectedSprintVersion", s2Ver,
                        "requestId", UUID.randomUUID().toString(),
                        "carryover", Map.of("destination", "BACKLOG")),
                manager.jwt(), Map.of());
        assertThat(s2Complete.statusCode()).as(s2Complete.body()).isEqualTo(200);
        assertThat(taskRepository.findActiveById(todoTask.path("id").asLong()).orElseThrow().getSprintId()).isNull();
        assertThat(taskRepository.findActiveById(inProgressTask.path("id").asLong()).orElseThrow().getSprintId()).isNull();
    }

    @Test
    void completedSprintFreeze_blocksTaskMutationsAndSubtasks_allowsAuditedGradeCorrection_andSupportsSuccessorTask()
            throws Exception {
        UserFixture manager1 = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture manager2 = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture member = createUser(true, AccountLifecycle.ACTIVE);
        long projectId = createProject(manager1.jwt());
        addMember(projectId, member.id(), MemberRole.MEMBER);
        addMember(projectId, manager2.id(), MemberRole.MANAGER);

        long s1Id = createSprint(manager1.jwt(), projectId, "Frozen Sprint").path("id").asLong();
        long s1Ver = startSprint(manager1.jwt(), projectId, s1Id).path("version").asLong();
        long planningId = createSprint(manager1.jwt(), projectId, "Next Planning").path("id").asLong();
        ProjectFileEntity evidence = createProjectFile(projectId, member.id(), "spec.pdf");

        JsonNode created = createTask(manager1.jwt(), projectId, s1Id, null, member.id(), "Closed Feature");
        JsonNode started = patchTaskStatus(manager1.jwt(), created.path("id").asLong(), created.path("version").asLong(), "IN_PROGRESS");
        JsonNode submitted = submitTask(member.jwt(), started.path("id").asLong(), started.path("version").asLong(), List.of(evidence.getId()));
        JsonNode reviewed = acceptReview(manager1.jwt(), started.path("id").asLong(),
                submitted.path("submissionId").asLong(), submitted.path("taskVersion").asLong(), 3);
        long closedTaskId = started.path("id").asLong();
        long closedTaskVer = reviewed.path("taskVersion").asLong();
        long evalId = reviewed.path("evaluationId").asLong();

        HttpResponse<String> completeResp = send("POST",
                "/api/v1/projects/" + projectId + "/sprints/" + s1Id + "/complete",
                Map.of("expectedSprintVersion", s1Ver,
                        "requestId", UUID.randomUUID().toString(),
                        "carryover", Map.of("destination", "BACKLOG")),
                manager1.jwt(), Map.of());
        assertThat(completeResp.statusCode()).as(completeResp.body()).isEqualTo(200);
        int snapshotDoneBefore = MAPPER.readTree(completeResp.body()).path("data").path("closureSnapshot").path("doneTasksRetained").asInt();

        // All completed-sprint mutations blocked across REST
        assertThat(send("PATCH", "/api/v1/tasks/" + closedTaskId,
                Map.of("expectedTaskVersion", closedTaskVer, "requestId", UUID.randomUUID().toString(), "title", "Edited"),
                manager1.jwt(), Map.of()).statusCode()).isEqualTo(409);
        assertThat(send("PUT", "/api/v1/tasks/" + closedTaskId,
                Map.of("expectedTaskVersion", closedTaskVer, "requestId", UUID.randomUUID().toString(), "title", "Edited"),
                manager1.jwt(), Map.of()).statusCode()).isEqualTo(409);
        assertThat(send("PATCH", "/api/v1/tasks/" + closedTaskId + "/kanban",
                Map.of("expectedTaskVersion", closedTaskVer, "requestId", UUID.randomUUID().toString(),
                        "status", "TODO", "position", 1.0f),
                manager1.jwt(), Map.of()).statusCode()).isEqualTo(409);
        assertThat(send("PATCH", "/api/v1/tasks/" + closedTaskId + "/sprint",
                Map.of("expectedTaskVersion", closedTaskVer, "requestId", UUID.randomUUID().toString(), "sprintId", planningId),
                manager1.jwt(), Map.of()).statusCode()).isEqualTo(409);
        assertThat(send("DELETE", "/api/v1/tasks/" + closedTaskId + "?expectedTaskVersion=" + closedTaskVer
                + "&requestId=" + UUID.randomUUID(), null, manager1.jwt(), Map.of()).statusCode()).isEqualTo(409);
        assertThat(send("POST", "/api/v1/tasks/" + closedTaskId + "/workflow/reopen",
                Map.of("expectedTaskVersion", closedTaskVer, "requestId", UUID.randomUUID().toString(), "reason", "Attempt reopen"),
                manager1.jwt(), Map.of()).statusCode()).isEqualTo(409);

        // Creating task inside completed sprint or subtask under completed task blocked
        assertThat(send("POST", "/api/v1/tasks",
                Map.of("projectId", projectId, "title", "Into Closed", "sprintId", s1Id, "requestId", UUID.randomUUID().toString()),
                manager1.jwt(), Map.of()).statusCode()).isEqualTo(409);
        assertThat(send("POST", "/api/v1/tasks",
                Map.of("projectId", projectId, "title", "Sub Closed", "parentId", closedTaskId, "requestId", UUID.randomUUID().toString()),
                manager1.jwt(), Map.of()).statusCode()).isEqualTo(409);

        // Confirmed AI & workflow ports also respect completed-sprint freeze
        assertThatThrownBy(() -> taskCommandPort.patchTask(
                new PatchTaskCommand(closedTaskId, closedTaskVer, UUID.randomUUID(),
                        Set.of("title"), Map.of("title", "AI Edit")),
                manager1.id()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> taskCommandPort.updateTaskStatus(
                closedTaskId, "TODO", closedTaskVer, UUID.randomUUID(), manager1.id()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> taskCommandPort.deleteTask(
                closedTaskId, closedTaskVer, UUID.randomUUID(), manager1.id()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> sprintQueryPort.assignTaskToSprint(
                closedTaskId, planningId, closedTaskVer, UUID.randomUUID(), manager1.id()))
                .isInstanceOf(BusinessException.class);
        CurrentActor managerActor = new CurrentActor(manager1.id(), manager1.email(), "USER", 0L);
        assertThatThrownBy(() -> taskWorkflowCommandPort.reopen(
                new ReopenTaskCommand(closedTaskId, closedTaskVer, UUID.randomUUID(), "AI Reopen"),
                managerActor))
                .isInstanceOf(BusinessException.class);

        // Audited grade correction (supersedeEvaluation) REMAINS ALLOWED on DONE task in COMPLETED sprint without changing snapshot counts
        HttpResponse<String> supersede = send("POST", "/api/v1/tasks/" + closedTaskId + "/workflow/supersede",
                Map.of("submissionId", submitted.path("submissionId").asLong(),
                        "expectedEvaluationId", evalId,
                        "expectedEvaluationVersion", reviewed.path("evaluationVersion").asLong(),
                        "expectedTaskVersion", closedTaskVer,
                        "requestId", UUID.randomUUID().toString(),
                        "qualityRating", 5,
                        "reason", "Post-sprint grade calibration verified",
                        "evidenceSummary", "Benchmark confirmed"),
                manager2.jwt(), Map.of());
        assertThat(supersede.statusCode()).as(supersede.body()).isEqualTo(200);
        assertThat(closureSnapshotRepository.findBySprintId(s1Id).orElseThrow().getDoneTasksRetained())
                .isEqualTo(snapshotDoneBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.sprint_closure_corrections WHERE sprint_id = ?",
                Integer.class, s1Id)).isEqualTo(1);

        // Follow-up successor into completed sprint rejected (409); successor into writable planning sprint/backlog succeeds!
        assertThat(send("POST", "/api/v1/tasks/" + closedTaskId + "/successor",
                Map.of("title", "Invalid Successor", "sprintId", s1Id, "requestId", UUID.randomUUID().toString()),
                manager1.jwt(), Map.of()).statusCode()).isEqualTo(409);

        HttpResponse<String> succResp = send("POST", "/api/v1/tasks/" + closedTaskId + "/successor",
                Map.of("title", "Follow-up Defect Fix", "sprintId", planningId, "requestId", UUID.randomUUID().toString()),
                manager1.jwt(), Map.of());
        assertThat(succResp.statusCode()).as(succResp.body()).isEqualTo(201);
        JsonNode succJson = MAPPER.readTree(succResp.body()).path("data");
        assertThat(succJson.path("successorOfTaskId").asLong()).isEqualTo(closedTaskId);
        assertThat(succJson.path("sprintId").asLong()).isEqualTo(planningId);

        TaskSummaryDto aiSucc = taskCommandPort.createSuccessorTask(
                closedTaskId, "AI Follow-up Bug", "From AI", "HIGH", null,
                null, null, null, null, UUID.randomUUID(), manager1.id());
        assertThat(aiSucc.id()).isNotNull();
        assertThat(taskRepository.findActiveById(aiSucc.id()).orElseThrow().getSuccessorOfTaskId()).isEqualTo(closedTaskId);
    }

    @Test
    void subtreeDelete_tombstonesWithHistoryAndExcludesAllReadsCountsAiRealtime_hardDeletesHistoryFree()
            throws Exception {
        UserFixture manager = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture member = createUser(true, AccountLifecycle.ACTIVE);
        long projectId = createProject(manager.jwt());
        addMember(projectId, member.id(), MemberRole.MEMBER);

        long completedSprintId = createSprint(manager.jwt(), projectId, "Closed S").path("id").asLong();
        long completedVer = startSprint(manager.jwt(), projectId, completedSprintId).path("version").asLong();
        assertThat(send("POST", "/api/v1/projects/" + projectId + "/sprints/" + completedSprintId + "/complete",
                Map.of("expectedSprintVersion", completedVer,
                        "requestId", UUID.randomUUID().toString(),
                        "carryover", Map.of("destination", "BACKLOG")),
                manager.jwt(), Map.of()).statusCode()).isEqualTo(200);

        // Case A: parent in backlog with child moved into completed sprint (simulate pre-existing completed child -> blocked 409)
        JsonNode blockedParent = createTask(manager.jwt(), projectId, null, null, null, "Blocked Parent");
        JsonNode blockedChild = createTask(manager.jwt(), projectId, null, blockedParent.path("id").asLong(), null, "Child");
        jdbcTemplate.update("UPDATE public.tasks SET sprint_id = ? WHERE id = ?",
                completedSprintId, blockedChild.path("id").asLong());
        assertThat(send("DELETE", "/api/v1/tasks/" + blockedParent.path("id").asLong()
                + "?expectedTaskVersion=" + blockedParent.path("version").asLong()
                + "&requestId=" + UUID.randomUUID(), null, manager.jwt(), Map.of()).statusCode()).isEqualTo(409);
        assertThat(taskRepository.findActiveById(blockedParent.path("id").asLong())).isPresent();

        // Case B: history-free parent + evaluated child + grandchild with recommendation_task_outcomes -> tombstone whole subtree!
        JsonNode parent = createTask(manager.jwt(), projectId, null, null, null, "Parent To Tombstone");
        long parentId = parent.path("id").asLong();
        long parentVer = parent.path("version").asLong();

        ProjectFileEntity proof = createProjectFile(projectId, member.id(), "deliverable.pdf");
        JsonNode child = createTask(manager.jwt(), projectId, null, parentId, member.id(), "Evaluated Child");
        JsonNode childStarted = patchTaskStatus(manager.jwt(), child.path("id").asLong(), child.path("version").asLong(), "IN_PROGRESS");
        JsonNode childSubmitted = submitTask(member.jwt(), childStarted.path("id").asLong(), childStarted.path("version").asLong(), List.of(proof.getId()));
        acceptReview(manager.jwt(), childStarted.path("id").asLong(),
                childSubmitted.path("submissionId").asLong(), childSubmitted.path("taskVersion").asLong(), 5);
        long childId = child.path("id").asLong();

        JsonNode grandChild = createTask(manager.jwt(), projectId, null, childId, member.id(), "Rec Outcome Child");
        long grandChildId = grandChild.path("id").asLong();

        // Comment on parent so searchAccessibleComments can also verify exclusion after tombstone
        assertThat(send("POST", "/api/v1/tasks/" + parentId + "/comments",
                Map.of("content", "Visible comment before tombstone"),
                manager.jwt(), Map.of()).statusCode()).isEqualTo(201);

        // Attach recommendation_task_outcomes FK row pointing to grandChild
        String snapshotId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO public.recommendation_snapshots (
                    snapshot_id, requested_by_user_id, project_id, task_id, created_at,
                    heuristic_mode, presentation_contract_version, scoring_model_version,
                    differentiation_status, required_skills, candidate_count, request_source,
                    fit_weight, load_weight, performance_weight, normalization_contract
                ) VALUES (?, ?, ?, ?, NOW(), 'HEURISTIC', 'v1', 'v1', 'DIFF', 'java', 1, 'TEST', 0.4, 0.3, 0.3, 'DEFAULT')
                """,
                snapshotId, manager.id(), projectId, grandChildId);
        String decisionId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO public.recommendation_decision_events (
                    decision_id, snapshot_id, project_id, task_id, decided_by_user_id,
                    decision_source, decision_type, recommended_candidate_id, selected_candidate_id
                ) VALUES (?, ?, ?, ?, ?, 'UI', 'ACCEPTED', ?, ?)
                """,
                decisionId, snapshotId, projectId, grandChildId, manager.id(), member.id(), member.id());
        jdbcTemplate.update(
                """
                INSERT INTO public.recommendation_task_outcomes (
                    outcome_id, snapshot_id, decision_id, project_id, task_id,
                    recommended_candidate_id, selected_candidate_id, decision_type, decision_source,
                    observed_assignee_id, task_status, outcome_type
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACCEPTED', 'UI', ?, 'TODO', 'ASSIGNED')
                """,
                UUID.randomUUID().toString(), snapshotId, decisionId, projectId, grandChildId,
                member.id(), member.id(), member.id());

        // Also keep a live surviving sibling task in the project so counts/lists verify active filtering
        JsonNode survivor = createTask(manager.jwt(), projectId, null, null, member.id(), "Surviving Active Task");
        long survivorId = survivor.path("id").asLong();

        UUID delReqId = UUID.randomUUID();
        HttpResponse<String> tombstoneResp = send("DELETE",
                "/api/v1/tasks/" + parentId + "?expectedTaskVersion=" + parentVer + "&requestId=" + delReqId,
                null, manager.jwt(), Map.of());
        assertThat(tombstoneResp.statusCode()).as(tombstoneResp.body()).isEqualTo(200);

        // All 3 nodes tombstoned; active queries empty; child's active evaluation invalidated & journal event fired
        assertThat(taskRepository.findActiveById(parentId)).isEmpty();
        assertThat(taskRepository.findActiveById(childId)).isEmpty();
        assertThat(taskRepository.findActiveById(grandChildId)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM public.tasks WHERE id = ?", Boolean.class, parentId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM public.tasks WHERE id = ?", Boolean.class, grandChildId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reason FROM public.task_evaluation_invalidations WHERE task_id = ?",
                String.class, childId)).isEqualTo("TASK_DELETED");
        assertThat(journalConsumer.invalidatedCount()).isGreaterThanOrEqualTo(1);

        // Idempotent DELETE retry returns 200; live GET /comments/successor/subtask on tombstoned return 404
        assertThat(send("DELETE",
                "/api/v1/tasks/" + parentId + "?expectedTaskVersion=" + parentVer + "&requestId=" + delReqId,
                null, manager.jwt(), Map.of()).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/tasks/" + parentId, null, manager.jwt(), Map.of()).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/v1/tasks/" + childId, null, manager.jwt(), Map.of()).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/v1/tasks",
                Map.of("projectId", projectId, "title", "Dead Parent Sub", "parentId", parentId, "requestId", UUID.randomUUID().toString()),
                manager.jwt(), Map.of()).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/v1/tasks/" + childId + "/successor",
                Map.of("title", "Succ on Tombstone", "requestId", UUID.randomUUID().toString()),
                manager.jwt(), Map.of()).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/v1/tasks/" + parentId + "/comments",
                Map.of("content", "After tombstone"),
                manager.jwt(), Map.of()).statusCode()).isEqualTo(404);

        // Exhaustive active read/count/search/AI/notification exclusions
        HttpResponse<String> listResp = send("GET", "/api/v1/tasks?projectId=" + projectId, null, manager.jwt(), Map.of());
        assertThat(listResp.statusCode()).isEqualTo(200);
        List<Long> listedIds = new ArrayList<>();
        MAPPER.readTree(listResp.body()).path("data").forEach(n -> listedIds.add(n.path("id").asLong()));
        assertThat(listedIds).contains(survivorId).doesNotContain(parentId, childId, grandChildId);

        HttpResponse<String> summaryResp = send("GET", "/api/v1/projects/" + projectId + "/summary", null, manager.jwt(), Map.of());
        assertThat(summaryResp.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(summaryResp.body()).path("data").path("totalTasks").asInt()).isEqualTo(3);

        List<Object[]> nativeCounts = taskRepository.countTasksByStatusNative(projectId);
        int nativeTotal = nativeCounts.stream().mapToInt(r -> ((Number) r[1]).intValue()).sum();
        assertThat(nativeTotal).isEqualTo(3);

        assertThat(commentRepository.searchAccessibleComments(
                manager.id(), true, "%tombstone%", projectId, null, null, false, Set.of(-1L), false, PageRequest.of(0, 20)))
                .isEmpty();
        assertThat(taskCommandPort.getTasksByProject(projectId, manager.id())).extracting(TaskSummaryDto::id)
                .contains(survivorId).doesNotContain(parentId, childId, grandChildId);
        assertThat(projectInsightsPort.getProjectStatus(projectId, manager.id()).totalTasks()).isEqualTo(3L);
        List<MemberWorkloadDto> workloads = memberAnalyticsPort.getMemberWorkloadForProject(projectId, manager.id());
        assertThat(workloads).filteredOn(w -> w.memberId().equals(member.id()))
                .singleElement().satisfies(w -> assertThat(w.openTasks()).isEqualTo(1));
        assertThat(realtimeAccessAdapter.canDeliverProjectNotification(projectId, parentId, member.id())).isFalse();
        assertThatThrownBy(() -> aiResourceAccessAdapter.fingerprint(
                manager.id(), List.of(new AiResourceRef("TASK", parentId)), List.of(manager.id()), false))
                .isInstanceOf(BusinessException.class);

        // Restricted manager audit exposes tombstoned tasks & history; member receives 403
        assertThat(send("GET", "/api/v1/projects/" + projectId + "/audit/deleted-tasks",
                null, member.jwt(), Map.of()).statusCode()).isEqualTo(403);
        HttpResponse<String> auditList = send("GET", "/api/v1/projects/" + projectId + "/audit/deleted-tasks",
                null, manager.jwt(), Map.of());
        assertThat(auditList.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(auditList.body()).path("data")).extracting(n -> n.path("taskId").asLong())
                .containsExactlyInAnyOrder(parentId, childId, grandChildId);
        HttpResponse<String> auditWorkflow = send("GET",
                "/api/v1/projects/" + projectId + "/audit/deleted-tasks/" + childId + "/workflow",
                null, manager.jwt(), Map.of());
        assertThat(auditWorkflow.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(auditWorkflow.body()).path("data").path("evaluations")).isNotEmpty();

        // Case C: pure history-free parent + child hard-deleted bottom-up!
        JsonNode cleanParent = createTask(manager.jwt(), projectId, null, null, null, "Clean Parent");
        JsonNode cleanChild = createTask(manager.jwt(), projectId, null, cleanParent.path("id").asLong(), null, "Clean Child");
        long cpId = cleanParent.path("id").asLong();
        long ccId = cleanChild.path("id").asLong();
        assertThat(send("DELETE", "/api/v1/tasks/" + cpId
                + "?expectedTaskVersion=" + cleanParent.path("version").asLong()
                + "&requestId=" + UUID.randomUUID(), null, manager.jwt(), Map.of()).statusCode()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.tasks WHERE id IN (?, ?)", Integer.class, cpId, ccId))
                .isZero();
    }

    @Test
    void reopenTask_revalidatesAssigneeEligibility_clearsRemovedAssigneeAndPreservesHistory() throws Exception {
        UserFixture manager = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture member = createUser(true, AccountLifecycle.ACTIVE);
        long projectId = createProject(manager.jwt());
        addMember(projectId, member.id(), MemberRole.MEMBER);

        ProjectFileEntity proof = createProjectFile(projectId, member.id(), "work.pdf");
        JsonNode created = createTask(manager.jwt(), projectId, null, null, member.id(), "Reopen Target");
        JsonNode started = patchTaskStatus(manager.jwt(), created.path("id").asLong(), created.path("version").asLong(), "IN_PROGRESS");
        JsonNode sub = submitTask(member.jwt(), started.path("id").asLong(), started.path("version").asLong(), List.of(proof.getId()));
        JsonNode reviewed = acceptReview(manager.jwt(), started.path("id").asLong(),
                sub.path("submissionId").asLong(), sub.path("taskVersion").asLong(), 5);
        long taskId = started.path("id").asLong();
        long verAfterDone = reviewed.path("taskVersion").asLong();

        long assignVerBeforeReopen = taskRepository.findActiveById(taskId).orElseThrow().getAssignmentVersion();

        // Remove assignee from project before reopen
        jdbcTemplate.update("DELETE FROM public.project_members WHERE project_id = ? AND user_id = ?", projectId, member.id());

        HttpResponse<String> reopenResp = send("POST", "/api/v1/tasks/" + taskId + "/workflow/reopen",
                Map.of("expectedTaskVersion", verAfterDone,
                        "requestId", UUID.randomUUID().toString(),
                        "reason", "Scope expanded after member left"),
                manager.jwt(), Map.of());
        assertThat(reopenResp.statusCode()).as(reopenResp.body()).isEqualTo(200);
        TaskEntity reopened = taskRepository.findActiveById(taskId).orElseThrow();
        assertThat(reopened.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(reopened.getAssigneeId()).isNull();
        assertThat(reopened.getAssignmentVersion()).isGreaterThan(assignVerBeforeReopen);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.task_evaluations WHERE task_id = ?", Integer.class, taskId)).isEqualTo(1);
    }

    @Test
    void dryRunLegacyRepairManifest_andFlywayV48ToV49Upgrade_detectAnomaliesWithoutWrites() throws Exception {
        UserFixture manager = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture member = createUser(true, AccountLifecycle.ACTIVE);
        long projectId = createProject(manager.jwt());
        addMember(projectId, member.id(), MemberRole.MEMBER);

        long sClosed = createSprint(manager.jwt(), projectId, "Legacy Closed").path("id").asLong();
        JsonNode trapped = createTask(manager.jwt(), projectId, null, null, member.id(), "Trapped Legacy");
        JsonNode reviewLegacy = createTask(manager.jwt(), projectId, null, null, member.id(), "Legacy Review No Sub");
        JsonNode doneLegacy = createTask(manager.jwt(), projectId, null, null, member.id(), "Legacy Done");
        jdbcTemplate.update("UPDATE public.sprints SET status = 'COMPLETED' WHERE id = ?", sClosed);
        jdbcTemplate.update("UPDATE public.tasks SET sprint_id = ?, status = 'IN_PROGRESS' WHERE id = ?",
                sClosed, trapped.path("id").asLong());
        jdbcTemplate.update("UPDATE public.tasks SET status = 'REVIEW' WHERE id = ?", reviewLegacy.path("id").asLong());
        jdbcTemplate.update("UPDATE public.tasks SET status = 'DONE', completion_evidence_status = 'LEGACY_UNVERIFIED' WHERE id = ?",
                doneLegacy.path("id").asLong());

        assertThat(send("GET", "/api/v1/projects/" + projectId + "/sprints/legacy-repair-manifest",
                null, member.jwt(), Map.of()).statusCode()).isEqualTo(403);
        HttpResponse<String> resp = send("GET", "/api/v1/projects/" + projectId + "/sprints/legacy-repair-manifest",
                null, manager.jwt(), Map.of());
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        JsonNode data = MAPPER.readTree(resp.body()).path("data");
        assertThat(data.path("dryRunOnly").asBoolean()).isTrue();
        assertThat(data.path("mutationsApplied").asBoolean()).isFalse();
        assertThat(data.path("trappedUnfinishedTasks")).extracting(n -> n.path("taskId").asLong())
                .contains(trapped.path("id").asLong());
        assertThat(data.path("legacyReviewMissingSubmissions")).extracting(n -> n.path("taskId").asLong())
                .contains(reviewLegacy.path("id").asLong());
        assertThat(data.path("legacyDonePreservedIds")).extracting(JsonNode::asLong)
                .contains(doneLegacy.path("id").asLong());
        // Zero DB writes happened
        assertThat(taskRepository.findActiveById(trapped.path("id").asLong()).orElseThrow().getSprintId()).isEqualTo(sClosed);

        // Real Flyway V48 -> V49 upgrade check on separate isolated DB inside same PG17 container
        String upgradeDb = "upgrade_v48_v49_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try (Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + upgradeDb);
        }
        String upgradeUrl = postgres.getJdbcUrl().replace("/taskpilot", "/" + upgradeDb);
        Flyway v48 = MigrationDatabaseConfiguration
                .configure(Flyway.configure().dataSource(upgradeUrl, postgres.getUsername(), postgres.getPassword()))
                .target("48")
                .load();
        v48.migrate();
        Flyway v49 = MigrationDatabaseConfiguration
                .configure(Flyway.configure().dataSource(upgradeUrl, postgres.getUsername(), postgres.getPassword()))
                .load();
        assertThat(v49.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(v49.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void permanentProjectPurge_enforcesConfirmationIdempotencyRollbackFullWipeAndDeferredStorageCleanup()
            throws Exception {
        UserFixture manager = createUser(true, AccountLifecycle.ACTIVE);
        UserFixture member = createUser(true, AccountLifecycle.ACTIVE);
        long projectId = createProject(manager.jwt());
        addMember(projectId, member.id(), MemberRole.MEMBER);

        long s1Id = createSprint(manager.jwt(), projectId, "Purge Sprint").path("id").asLong();
        long s1Ver = startSprint(manager.jwt(), projectId, s1Id).path("version").asLong();

        // Upload real fake S3 + Google Drive backed project file & attach it
        MockMultipartFile mp = new MockMultipartFile("file", "purge-artifact.pdf", "application/pdf", "binary".getBytes(StandardCharsets.UTF_8));
        String s3Key = fakeS3.uploadFile(mp, "projects/" + projectId, "documents");
        ProjectFileEntity pFile = projectFileRepository.saveAndFlush(ProjectFileEntity.builder()
                .projectId(projectId)
                .uploaderId(manager.id())
                .fileName("purge-artifact.pdf")
                .originalName("purge-artifact.pdf")
                .fileSize(6L)
                .contentType("application/pdf")
                .storageKey(s3Key)
                .storageBucket("documents")
                .build());
        GoogleDriveFileDto driveDto = fakeDrive.uploadFile(mp, "fake-folder-id", UUID.randomUUID());
        projectFileRepository.saveAndFlush(ProjectFileEntity.builder()
                .projectId(projectId)
                .uploaderId(manager.id())
                .fileName("drive-artifact.pdf")
                .originalName("drive-artifact.pdf")
                .fileSize(6L)
                .contentType("application/pdf")
                .storageKey(driveDto.getFileId())
                .storageBucket("googledrive")
                .build());

        JsonNode doneTask = createTask(manager.jwt(), projectId, s1Id, null, member.id(), "Purge Task");
        JsonNode started = patchTaskStatus(manager.jwt(), doneTask.path("id").asLong(), doneTask.path("version").asLong(), "IN_PROGRESS");
        JsonNode sub = submitTask(member.jwt(), started.path("id").asLong(), started.path("version").asLong(), List.of(pFile.getId()));
        acceptReview(manager.jwt(), started.path("id").asLong(), sub.path("submissionId").asLong(), sub.path("taskVersion").asLong(), 5);
        long taskId = started.path("id").asLong();

        send("POST", "/api/v1/tasks/" + taskId + "/comments", Map.of("content", "Purge comment"), manager.jwt(), Map.of());
        send("POST", "/api/v1/projects/" + projectId + "/sprints/" + s1Id + "/complete",
                Map.of("expectedSprintVersion", s1Ver, "requestId", UUID.randomUUID().toString(),
                        "carryover", Map.of("destination", "BACKLOG")),
                manager.jwt(), Map.of());

        String recSnapId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO public.recommendation_snapshots (
                    snapshot_id, requested_by_user_id, project_id, task_id, created_at,
                    heuristic_mode, presentation_contract_version, scoring_model_version,
                    differentiation_status, required_skills, candidate_count, request_source,
                    fit_weight, load_weight, performance_weight, normalization_contract
                ) VALUES (?, ?, ?, ?, NOW(), 'HEURISTIC', 'v1', 'v1', 'DIFF', 'java', 1, 'PURGE_TEST', 0.4, 0.3, 0.3, 'DEFAULT')
                """,
                recSnapId, manager.id(), projectId, taskId);
        String recEvId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO public.recommendation_decision_events (
                    decision_id, snapshot_id, project_id, task_id, decided_by_user_id,
                    decision_source, decision_type, recommended_candidate_id, selected_candidate_id
                ) VALUES (?, ?, ?, ?, ?, 'UI', 'ACCEPTED', ?, ?)
                """,
                recEvId, recSnapId, projectId, taskId, manager.id(), member.id(), member.id());
        jdbcTemplate.update(
                """
                INSERT INTO public.recommendation_task_outcomes (
                    outcome_id, snapshot_id, decision_id, project_id, task_id,
                    recommended_candidate_id, selected_candidate_id, decision_type, decision_source,
                    observed_assignee_id, task_status, outcome_type
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACCEPTED', 'UI', ?, 'DONE', 'COMPLETED')
                """,
                UUID.randomUUID().toString(), recSnapId, recEvId, projectId, taskId,
                member.id(), member.id(), member.id());
        Long docId = jdbcTemplate.queryForObject(
                "INSERT INTO public.documents (project_id, storage_key, original_filename) VALUES (?, 'rag-key-1', 'd.pdf') RETURNING id",
                Long.class, projectId);
        jdbcTemplate.update(
                "INSERT INTO public.ai_logs (project_id, user_id, action_taken, request) VALUES (?, ?, 'TEST', 'test')",
                projectId, manager.id());

        // Guard rails: member 403, missing requestId 400, bad confirmation 400
        assertThat(send("DELETE", "/api/v1/projects/" + projectId,
                Map.of("confirmationText", "DELETE " + projectId, "requestId", UUID.randomUUID().toString()),
                member.jwt(), Map.of()).statusCode()).isEqualTo(403);
        assertThat(send("DELETE", "/api/v1/projects/" + projectId,
                Map.of("confirmationText", "DELETE " + projectId),
                manager.jwt(), Map.of()).statusCode()).isEqualTo(400);
        assertThat(send("DELETE", "/api/v1/projects/" + projectId,
                Map.of("confirmationText", "WRONG", "requestId", UUID.randomUUID().toString()),
                manager.jwt(), Map.of()).statusCode()).isEqualTo(400);

        // Mid-transaction rollback proof: inject failure at cleanup enqueue inside transaction -> 0 rows deleted, 0 jobs, 0 provider deletes
        faultStorageCleanup.failNextEnqueue.set(true);
        HttpResponse<String> failedPurge = send("DELETE", "/api/v1/projects/" + projectId,
                Map.of("confirmationText", "DELETE " + projectId, "requestId", UUID.randomUUID().toString()),
                manager.jwt(), Map.of());
        assertThat(failedPurge.statusCode()).isGreaterThanOrEqualTo(500);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.projects WHERE id = ?", Integer.class, projectId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.tasks WHERE project_id = ?", Integer.class, projectId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.task_command_idempotency WHERE project_id = ?", Integer.class, projectId)).isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.storage_cleanup_jobs WHERE project_id = ?", Integer.class, projectId)).isZero();
        assertThat(deletionAuditRepository.findByProjectId(projectId)).isEmpty();
        assertThat(fakeS3.deletedKeys).isEmpty();
        assertThat(fakeDrive.deletedKeys).isEmpty();

        // Execute permanent purge successfully
        UUID purgeReqId = UUID.randomUUID();
        HttpResponse<String> purgeResp = send("DELETE", "/api/v1/projects/" + projectId,
                Map.of("confirmationText", "DELETE " + projectId, "requestId", purgeReqId.toString()),
                manager.jwt(), Map.of());
        assertThat(purgeResp.statusCode()).as(purgeResp.body()).isEqualTo(200);
        JsonNode audit = MAPPER.readTree(purgeResp.body()).path("data");
        assertThat(audit.path("projectId").asLong()).isEqualTo(projectId);
        assertThat(audit.path("enqueuedCleanupJobCount").asInt()).isGreaterThanOrEqualTo(2);

        // No pre-commit provider deletions occurred; jobs persisted PENDING
        assertThat(fakeS3.deletedKeys).isEmpty();
        assertThat(fakeDrive.deletedKeys).isEmpty();
        assertThat(cleanupJobRepository.findByProjectIdOrderByIdAsc(projectId)).isNotEmpty();

        // Verify ZERO surviving rows across ALL project/task/V47/V48/V49/AI tables (and surviving deletion audit)
        for (String sql : List.of(
                "SELECT COUNT(*) FROM public.projects WHERE id = ?",
                "SELECT COUNT(*) FROM public.project_members WHERE project_id = ?",
                "SELECT COUNT(*) FROM public.project_files WHERE project_id = ?",
                "SELECT COUNT(*) FROM public.sprints WHERE project_id = ?",
                "SELECT COUNT(*) FROM public.sprint_closure_snapshots WHERE project_id = ?",
                "SELECT COUNT(*) FROM public.tasks WHERE project_id = ?",
                "SELECT COUNT(*) FROM public.task_command_idempotency WHERE project_id = ?",
                "SELECT COUNT(*) FROM public.documents WHERE project_id = ?",
                "SELECT COUNT(*) FROM public.ai_logs WHERE project_id = ?",
                "SELECT COUNT(*) FROM public.recommendation_snapshots WHERE project_id = ?",
                "SELECT COUNT(*) FROM public.recommendation_decision_events WHERE project_id = ?")) {
            assertThat(jdbcTemplate.queryForObject(sql, Integer.class, projectId)).as(sql).isZero();
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.task_evaluations WHERE task_id = ?", Integer.class, taskId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.task_submissions WHERE task_id = ?", Integer.class, taskId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.task_workflow_journal WHERE task_id = ?", Integer.class, taskId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.recommendation_task_outcomes WHERE task_id = ?", Integer.class, taskId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.document_chunks WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(deletionAuditRepository.findByProjectId(projectId)).isPresent();

        // Idempotency & post-purge semantics: same requestId + matching confirm -> 200; mismatch -> 409; new requestId -> 404
        assertThat(send("DELETE", "/api/v1/projects/" + projectId,
                Map.of("confirmationText", "DELETE " + projectId, "requestId", purgeReqId.toString()),
                manager.jwt(), Map.of()).statusCode()).isEqualTo(200);
        assertThat(send("DELETE", "/api/v1/projects/" + projectId,
                Map.of("confirmationText", String.valueOf(projectId), "requestId", purgeReqId.toString()),
                manager.jwt(), Map.of()).statusCode()).isEqualTo(409);
        assertThat(send("DELETE", "/api/v1/projects/" + projectId,
                Map.of("confirmationText", "DELETE " + projectId, "requestId", UUID.randomUUID().toString()),
                manager.jwt(), Map.of()).statusCode()).isEqualTo(404);

        // Post-commit durable storage cleanup execution with transient retry recovery
        fakeS3.transientFailuresRemaining.put("documents:" + s3Key, 1);
        faultStorageCleanup.processPendingBatch(20, "p5-test-worker");
        assertThat(fakeDrive.deletedKeys).contains(driveDto.getFileId());
        assertThat(fakeS3.deletedKeys).doesNotContain(s3Key); // scheduled retry on 503

        // Move cleanup retry next_attempt_at into the past and process second pass -> SUCCEEDED
        jdbcTemplate.update("UPDATE public.storage_cleanup_jobs SET next_attempt_at = NOW() - INTERVAL '5 seconds' WHERE project_id = ?", projectId);
        faultStorageCleanup.processPendingBatch(20, "p5-test-worker");
        assertThat(fakeS3.deletedKeys).contains(s3Key);
        assertThat(cleanupJobRepository.findByProjectIdOrderByIdAsc(projectId))
                .allMatch(j -> "SUCCEEDED".equals(j.getStatus().name()));
    }

    private HttpResponse<String> send(
            String method,
            String path,
            Object body,
            String jwt,
            Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15));
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body));
        builder.method(method, publisher);
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        if (jwt != null) {
            builder.header("Authorization", "Bearer " + jwt);
        }
        headers.forEach(builder::header);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String[] appArgs() {
        return new String[]{
                "--server.port=0",
                "--spring.profiles.active=dev",
                "--spring.main.allow-bean-definition-overriding=true",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.jpa.hibernate.ddl-auto=none",
                "--spring.flyway.enabled=false",
                "--spring.mail.host=localhost",
                "--spring.mail.port=1025",
                "--spring.task.scheduling.enabled=false",
                "--taskpilot.storage.cleanup.max-attempts=3",
                "--taskpilot.storage.cleanup.base-backoff-seconds=2",
                "--ai.gemini.api-key=local-dummy-not-a-secret",
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
    static class FakeSprintLifecycleConfig {
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
        FakeS3StorageService fakeS3StorageService() {
            return new FakeS3StorageService();
        }

        @Bean
        @Primary
        FakeGoogleDriveService fakeGoogleDriveService(Drive drive) {
            return new FakeGoogleDriveService(drive);
        }

        @Bean
        @Primary
        FaultInjectableStorageCleanupService faultInjectableStorageCleanupService(
                StorageCleanupJobRepository jobRepository,
                JdbcTemplate jdbcTemplate,
                StorageService storageService,
                GoogleDriveService googleDriveService,
                PlatformTransactionManager transactionManager,
                @Value("${taskpilot.storage.cleanup.max-attempts:6}") int maxAttempts,
                @Value("${taskpilot.storage.cleanup.base-backoff-seconds:30}") long baseBackoffSeconds,
                @Value("${taskpilot.storage.cleanup.lease-seconds:120}") long leaseSeconds) {
            return new FaultInjectableStorageCleanupService(
                    jobRepository, jdbcTemplate, storageService, googleDriveService,
                    transactionManager, maxAttempts, baseBackoffSeconds, leaseSeconds);
        }

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        ControllableJournalConsumer controllableJournalConsumer() {
            return new ControllableJournalConsumer();
        }
    }

    static class FaultInjectableStorageCleanupService extends StorageCleanupService implements StorageCleanupPort {
        final AtomicBoolean failNextEnqueue = new AtomicBoolean(false);

        FaultInjectableStorageCleanupService(
                StorageCleanupJobRepository jobRepository,
                JdbcTemplate jdbcTemplate,
                StorageService storageService,
                GoogleDriveService googleDriveService,
                PlatformTransactionManager transactionManager,
                int maxAttempts,
                long baseBackoffSeconds,
                long leaseSeconds) {
            super(jobRepository, jdbcTemplate, storageService, googleDriveService,
                    transactionManager, maxAttempts, baseBackoffSeconds, leaseSeconds);
        }

        @Override
        public void enqueueCleanupBatch(List<StorageCleanupRequest> requests) {
            if (failNextEnqueue.getAndSet(false)) {
                throw new IllegalStateException("Simulated storage cleanup enqueue failure");
            }
            super.enqueueCleanupBatch(requests);
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
        private final List<UUID> acceptedEventIds = new CopyOnWriteArrayList<>();
        private final AtomicInteger invalidated = new AtomicInteger();

        void reset() {
            acceptedEventIds.clear();
            invalidated.set(0);
        }

        int invalidatedCount() {
            return invalidated.get();
        }

        @Override
        public void onEvaluationAccepted(TaskEvaluationAccepted event) {
            acceptedEventIds.add(event.eventId());
        }

        @Override
        public void onEvaluationInvalidated(TaskEvaluationInvalidated event) {
            invalidated.incrementAndGet();
        }
    }

    static class FakeS3StorageService implements StorageService {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        final Map<String, Integer> transientFailuresRemaining = new ConcurrentHashMap<>();
        final List<String> deletedKeys = new CopyOnWriteArrayList<>();

        void reset() {
            objects.clear();
            transientFailuresRemaining.clear();
            deletedKeys.clear();
        }

        @Override
        public String uploadFile(MultipartFile file, String folder, String bucket) throws IOException {
            String key = planObjectKey(folder, file.getOriginalFilename());
            return uploadFileAtKey(file, bucket, key);
        }

        @Override
        public String uploadFileAtKey(MultipartFile file, String bucket, String key) throws IOException {
            objects.put(bucket + ":" + key, file.getBytes());
            return key;
        }

        @Override
        public InputStream downloadFile(String bucket, String key) throws IOException {
            byte[] data = objects.get(bucket + ":" + key);
            if (data == null) {
                throw new IOException("No such object: " + key);
            }
            return new ByteArrayInputStream(data);
        }

        @Override
        public StorageReadResult readObject(StorageReadRequest request) throws IOException {
            byte[] data = objects.get(request.bucket() + ":" + request.key());
            if (data == null) {
                throw new IOException("No such object: " + request.key());
            }
            if (request.range().isPresent()) {
                ByteRange r = request.range().get();
                int start = (int) r.startInclusive();
                int endExclusive = (int) r.endInclusive() + 1;
                byte[] slice = Arrays.copyOfRange(data, start, endExclusive);
                return new StorageReadResult(
                        new ByteArrayInputStream(slice), data.length, slice.length,
                        "application/octet-stream", Optional.of(r), Optional.of("\"fake-s3\""));
            }
            return new StorageReadResult(
                    new ByteArrayInputStream(data), data.length, data.length,
                    "application/octet-stream", Optional.empty(), Optional.of("\"fake-s3\""));
        }

        @Override
        public Optional<StorageObjectStat> statObject(String bucket, String key) {
            byte[] data = objects.get(bucket + ":" + key);
            return data == null ? Optional.empty() : Optional.of(new StorageObjectStat(
                    data.length, "application/octet-stream", Optional.of("\"fake-s3\""),
                    Optional.of(Instant.parse("2026-11-17T00:00:00Z"))));
        }

        @Override
        public void deleteFile(String bucket, String key) {
            deleteObject(bucket, key);
        }

        @Override
        public StorageDeleteResult deleteObject(String bucket, String key) {
            String full = bucket + ":" + key;
            Integer remaining = transientFailuresRemaining.get(full);
            if (remaining != null && remaining > 0) {
                transientFailuresRemaining.put(full, remaining - 1);
                return StorageDeleteResult.retryableFailure("S3_HTTP_503", "Service Unavailable");
            }
            deletedKeys.add(key);
            byte[] removed = objects.remove(full);
            return removed != null ? StorageDeleteResult.deleted() : StorageDeleteResult.notFound();
        }
    }

    static class FakeGoogleDriveService extends GoogleDriveService {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        final List<String> deletedKeys = new CopyOnWriteArrayList<>();

        FakeGoogleDriveService(Drive drive) {
            super(drive, "fake-folder-id");
        }

        void reset() {
            objects.clear();
            deletedKeys.clear();
        }

        @Override
        public GoogleDriveFileDto uploadFile(MultipartFile multipartFile, String targetFolderId, UUID operationId) throws IOException {
            String id = "fake-drive-" + UUID.randomUUID();
            objects.put(id, multipartFile.getBytes());
            return GoogleDriveFileDto.builder()
                    .fileId(id)
                    .name(multipartFile.getOriginalFilename())
                    .mimeType(multipartFile.getContentType())
                    .size(multipartFile.getSize())
                    .webViewLink("https://drive.google.com/file/d/" + id + "/view")
                    .build();
        }

        @Override
        public StorageDeleteResult deleteObject(String fileId) {
            deletedKeys.add(fileId);
            byte[] removed = objects.remove(fileId);
            return removed != null ? StorageDeleteResult.deleted() : StorageDeleteResult.notFound();
        }
    }
}
