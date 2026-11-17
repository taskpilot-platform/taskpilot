package com.taskpilot.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.app.TaskPilotApplication;
import com.taskpilot.app.migration.MigrationDatabaseConfiguration;
import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.infrastructure.config.security.JwtService;
import com.taskpilot.infrastructure.notification.OneSignalService;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.PriorityLevel;
import com.taskpilot.projects.common.enums.TaskStatus;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
import com.taskpilot.projects.projects.service.ProjectServiceImpl;
import com.taskpilot.projects.tasks.dto.CreateTaskCommentRequest;
import com.taskpilot.projects.tasks.service.TaskCommentService;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.enums.UserStatus;
import com.taskpilot.users.common.repository.NotificationRepository;
import com.taskpilot.users.common.repository.UserRepository;
import com.taskpilot.users.notifications.service.NotificationService;
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
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Real PostgreSQL 17 + Notification SSE + pausable Async dispatch proof for P4.2:
 * - Pausing async notification dispatch between comment commit and dispatch while removing a member
 *   blocks notification DB creation, push, email, and SSE for the removed recipient while delivering to eligible members.
 * - Excludes historical commenters removed before comment creation.
 * - Preserves personal system notifications without project scope.
 */
@Testcontainers
class P4NotificationDispatchIntegrationTest {

    private static final String TEST_JWT_SECRET = "taskpilot-test-only-p4-notif-jwt-signing-key-2026-32bytes";
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
    private static PausableTaskExecutor pausableExecutor;
    private static RecordingOneSignalService fakeOneSignal;
    private static JavaMailSender fakeMailSender;

    @BeforeAll
    static void startApp() {
        Flyway flyway = MigrationDatabaseConfiguration.configure(
                Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        ).load();
        assertThat(flyway.migrate().migrationsExecuted).isGreaterThanOrEqualTo(6);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        context = new SpringApplicationBuilder(TaskPilotApplication.class, FakeDispatchConfig.class)
                .run(appArgs());
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        baseUrl = "http://127.0.0.1:" + port;
        pausableExecutor = context.getBean(PausableTaskExecutor.class);
        fakeOneSignal = context.getBean(RecordingOneSignalService.class);
        fakeMailSender = context.getBean(JavaMailSender.class);
    }

    @AfterAll
    static void stopApp() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void reset() throws InterruptedException {
        pausableExecutor.reset();
        pausableExecutor.awaitIdle(10, TimeUnit.SECONDS);
        fakeOneSignal.clear();
    }

    record UserFixture(long userId, String email, String jwt) {
    }

    private UserFixture createUserAndLogin() throws Exception {
        String email = "p4-notif-" + UUID.randomUUID() + "@example.com";
        String password = "Pass!12345";
        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setFullName("Notif User");
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.AVAILABLE);
        user.setAccountState(AccountLifecycle.ACTIVE);
        user.setPassword(context.getBean(PasswordEncoder.class).encode(password));
        user.setEmailVerified(true);
        user = context.getBean(UserRepository.class).saveAndFlush(user);

        HttpResponse<String> login = postJson("/api/v1/auth/login",
                Map.of("email", email, "password", password), null);
        assertThat(login.statusCode()).isEqualTo(200);
        String jwt = MAPPER.readTree(login.body()).path("data").path("token").asText();
        return new UserFixture(user.getId(), email, jwt);
    }

    private long createProject(String jwt) throws Exception {
        HttpResponse<String> resp = postJson("/api/v1/projects",
                Map.of("name", "P4 Notif " + UUID.randomUUID()), jwt);
        assertThat(resp.statusCode()).isEqualTo(201);
        return MAPPER.readTree(resp.body()).path("data").path("id").asLong();
    }

    private void addMember(long projectId, long userId, MemberRole role) {
        context.getBean(ProjectMemberRepository.class).saveAndFlush(ProjectMemberEntity.builder()
                .projectId(projectId)
                .userId(userId)
                .role(role)
                .build());
    }

    private long createTask(long projectId, long assigneeId, long reporterId) {
        TaskEntity task = context.getBean(TaskRepository.class).saveAndFlush(TaskEntity.builder()
                .projectId(projectId)
                .title("Sensitive Task Title XYZ")
                .status(TaskStatus.TODO)
                .priority(PriorityLevel.HIGH)
                .assigneeId(assigneeId)
                .reporterId(reporterId)
                .build());
        return task.getId();
    }

    @Test
    void pauseAsyncDispatch_removeMember_resume_blocksDbPushEmailSseForRemovedWhileDeliveringToEligible() throws Exception {
        UserFixture managerA = createUserAndLogin();
        UserFixture memberB = createUserAndLogin();
        UserFixture memberC = createUserAndLogin();
        UserFixture formerCommenterD = createUserAndLogin();

        long projectId = createProject(managerA.jwt());
        addMember(projectId, memberB.userId(), MemberRole.MEMBER);
        addMember(projectId, memberC.userId(), MemberRole.MEMBER);
        addMember(projectId, formerCommenterD.userId(), MemberRole.MEMBER);
        long taskId = createTask(projectId, memberB.userId(), memberC.userId());

        // Historical commenter D comments then leaves before next comment
        TaskCommentService commentService = context.getBean(TaskCommentService.class);
        commentService.createComment(taskId,
                new CreateTaskCommentRequest("Earlier comment by D", null, Set.of()), formerCommenterD.userId());
        pausableExecutor.awaitIdle(5, TimeUnit.SECONDS);
        fakeOneSignal.clear();

        context.getBean(ProjectServiceImpl.class).removeMember(projectId, formerCommenterD.userId(), managerA.userId());

        try (var sseB = P4RealtimeRevocationIntegrationTest.TestSseReader.open(
                     baseUrl + "/api/v1/notifications/my/stream", memberB.jwt());
             var sseC = P4RealtimeRevocationIntegrationTest.TestSseReader.open(
                     baseUrl + "/api/v1/notifications/my/stream", memberC.jwt())) {

            assertThat(sseB.statusCode).isEqualTo(200);
            assertThat(sseC.statusCode).isEqualTo(200);
            assertThat(sseB.events().poll(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(sseC.events().poll(5, TimeUnit.SECONDS)).isNotNull();

            long initialNotifCountB = context.getBean(NotificationRepository.class)
                    .countByUserIdAndIsReadFalse(memberB.userId());
            long initialNotifCountD = context.getBean(NotificationRepository.class)
                    .countByUserIdAndIsReadFalse(formerCommenterD.userId());

            // Pause async dispatch before creating comment mentioning B
            pausableExecutor.pauseNextBatch(2);

            HttpResponse<String> commentResp = postJson("/api/v1/tasks/" + taskId + "/comments",
                    Map.of("content", "Confidential comment for current team", "mentionedUserIds", List.of(memberB.userId())),
                    managerA.jwt());
            assertThat(commentResp.statusCode()).isEqualTo(201);

            // Wait until async tasks are parked on the gate AFTER business commit
            assertThat(pausableExecutor.awaitPaused(5, TimeUnit.SECONDS)).isTrue();

            // Remove B while async notification tasks are still queued/paused
            context.getBean(ProjectServiceImpl.class).removeMember(projectId, memberB.userId(), managerA.userId());

            // Resume async dispatch and wait for nested async push dispatch to settle
            pausableExecutor.resumeAndAwaitCompletion(10, TimeUnit.SECONDS);
            pausableExecutor.awaitIdle(5, TimeUnit.SECONDS);

            // Eligible member C receives DB notification, push, and SSE
            NotificationRepository notifRepo = context.getBean(NotificationRepository.class);
            assertThat(notifRepo.countByUserIdAndIsReadFalse(memberC.userId())).isGreaterThan(0);
            assertThat(fakeOneSignal.calls().stream().anyMatch(
                    c -> String.valueOf(memberC.userId()).equals(c.userId())
                            && c.message().contains("Sensitive Task Title XYZ"))).isTrue();

            var sseEventC = sseC.events().poll(5, TimeUnit.SECONDS);
            assertThat(sseEventC).isNotNull();
            assertThat(sseEventC.event()).isEqualTo("notification.created");
            assertThat(sseEventC.data()).contains("Sensitive Task Title XYZ");

            // Removed member B and historical former commenter D receive NOTHING
            assertThat(notifRepo.countByUserIdAndIsReadFalse(memberB.userId())).isEqualTo(initialNotifCountB);
            assertThat(notifRepo.countByUserIdAndIsReadFalse(formerCommenterD.userId())).isEqualTo(initialNotifCountD);
            assertThat(fakeOneSignal.calls()).as("provider calls after revocation and complete queue drain")
                    .noneMatch(c -> String.valueOf(memberB.userId()).equals(c.userId())
                            || String.valueOf(formerCommenterD.userId()).equals(c.userId()));
            assertThat(sseB.events().stream()
                    .filter(e -> "notification.created".equals(e.event()))
                    .toList()).isEmpty();
            verifyNoInteractions(fakeMailSender);

            // Personal system notification without project scope still works for active user B
            context.getBean(NotificationService.class).createSystemNotification(
                    memberB.userId(), "Account Security", "Personal notice", "/profile");
            assertThat(notifRepo.countByUserIdAndIsReadFalse(memberB.userId())).isEqualTo(initialNotifCountB + 1);
            var ssePersonalB = sseB.events().poll(5, TimeUnit.SECONDS);
            assertThat(ssePersonalB).isNotNull();
            assertThat(ssePersonalB.event()).isEqualTo("notification.created");
            assertThat(ssePersonalB.data()).contains("Personal notice");
        }
    }

    @Test
    void queuedPushRechecksScopeAfterMembershipRevoked() throws Exception {
        UserFixture manager = createUserAndLogin();
        UserFixture removed = createUserAndLogin();
        UserFixture eligible = createUserAndLogin();
        long projectId = createProject(manager.jwt());
        addMember(projectId, removed.userId(), MemberRole.MEMBER);
        addMember(projectId, eligible.userId(), MemberRole.MEMBER);
        pausableExecutor.awaitIdle(10, TimeUnit.SECONDS);
        fakeOneSignal.clear();
        pausableExecutor.pauseNextBatch(1);
        context.getBean(NotificationService.class).createNotification(
                new com.taskpilot.contracts.user.dto.SystemNotificationCommandDto(
                        removed.userId(), "Private", "queued confidential payload", "/projects/" + projectId,
                        com.taskpilot.contracts.user.dto.NotificationTypeDto.SYSTEM, projectId, null));
        assertThat(pausableExecutor.awaitPaused(5, TimeUnit.SECONDS)).isTrue();
        context.getBean(ProjectServiceImpl.class).removeMember(projectId, removed.userId(), manager.userId());
        pausableExecutor.resumeAndAwaitCompletion(10, TimeUnit.SECONDS);
        pausableExecutor.awaitIdle(10, TimeUnit.SECONDS);
        assertThat(fakeOneSignal.calls()).as("queued payload must be dropped after committed revocation").isEmpty();

        context.getBean(NotificationService.class).createNotification(
                new com.taskpilot.contracts.user.dto.SystemNotificationCommandDto(
                        eligible.userId(), "Private", "eligible control", "/projects/" + projectId,
                        com.taskpilot.contracts.user.dto.NotificationTypeDto.SYSTEM, projectId, null));
        pausableExecutor.awaitIdle(10, TimeUnit.SECONDS);
        assertThat(fakeOneSignal.calls()).singleElement().satisfies(call -> {
            assertThat(call.userId()).isEqualTo(String.valueOf(eligible.userId()));
            assertThat(call.message()).isEqualTo("eligible control");
        });
    }

    @Test
    void taskAssignmentEventRetainsScopeWhenRecipientRemovedBeforeListenerRuns() throws Exception {
        UserFixture manager = createUserAndLogin();
        UserFixture removed = createUserAndLogin();
        UserFixture eligible = createUserAndLogin();
        long projectId = createProject(manager.jwt());
        addMember(projectId, removed.userId(), MemberRole.MEMBER);
        addMember(projectId, eligible.userId(), MemberRole.MEMBER);
        long taskId = createTask(projectId, manager.userId(), manager.userId());
        pausableExecutor.awaitIdle(10, TimeUnit.SECONDS);
        fakeOneSignal.clear();
        pausableExecutor.pauseNextBatch(1);
        updateAssignee(taskId, removed.userId(), manager.userId());
        assertThat(pausableExecutor.awaitPaused(5, TimeUnit.SECONDS)).isTrue();
        context.getBean(ProjectServiceImpl.class).removeMember(projectId, removed.userId(), manager.userId());
        pausableExecutor.resumeAndAwaitCompletion(10, TimeUnit.SECONDS);
        pausableExecutor.awaitIdle(10, TimeUnit.SECONDS);
        assertThat(context.getBean(NotificationRepository.class).countByUserIdAndIsReadFalse(removed.userId())).isZero();
        assertThat(fakeOneSignal.calls()).noneMatch(call -> call.userId().equals(String.valueOf(removed.userId())));

        updateAssignee(taskId, eligible.userId(), manager.userId());
        pausableExecutor.awaitIdle(10, TimeUnit.SECONDS);
        assertThat(fakeOneSignal.calls()).anyMatch(call -> call.userId().equals(String.valueOf(eligible.userId()))
                && call.title().equals("Task Assigned"));
    }

    private void updateAssignee(long taskId, long assigneeId, long managerId) {
        context.getBean(com.taskpilot.projects.tasks.service.TaskService.class).updateTask(taskId,
                new com.taskpilot.projects.tasks.dto.UpdateTaskRequest(
                        null, null, null, null, null, null, null, null, assigneeId, null, null), managerId);
    }

    @Test
    void projectMemberEventRetainsScopeWhenManagerRemovedBeforeListenerRuns() throws Exception {
        UserFixture manager = createUserAndLogin();
        UserFixture removedManager = createUserAndLogin();
        UserFixture leaving = createUserAndLogin();
        long projectId = createProject(manager.jwt());
        addMember(projectId, removedManager.userId(), MemberRole.MANAGER);
        addMember(projectId, leaving.userId(), MemberRole.MEMBER);
        pausableExecutor.awaitIdle(10, TimeUnit.SECONDS);
        fakeOneSignal.clear();
        pausableExecutor.pauseNextBatch(2);
        context.getBean(ProjectServiceImpl.class).leaveProject(projectId, leaving.userId());
        assertThat(pausableExecutor.awaitPaused(5, TimeUnit.SECONDS)).isTrue();
        context.getBean(ProjectServiceImpl.class).removeMember(projectId, removedManager.userId(), manager.userId());
        pausableExecutor.resumeAndAwaitCompletion(10, TimeUnit.SECONDS);
        pausableExecutor.awaitIdle(10, TimeUnit.SECONDS);
        assertThat(context.getBean(NotificationRepository.class).countByUserIdAndIsReadFalse(removedManager.userId())).isZero();
        assertThat(fakeOneSignal.calls()).noneMatch(call -> call.userId().equals(String.valueOf(removedManager.userId())));
        assertThat(fakeOneSignal.calls()).anyMatch(call -> call.userId().equals(String.valueOf(manager.userId())));
    }

    @Test
    void aiAssignmentRetainsScopeWhenPushRecipientRemovedAfterCommit() throws Exception {
        UserFixture manager = createUserAndLogin();
        UserFixture removed = createUserAndLogin();
        UserFixture eligible = createUserAndLogin();
        long projectId = createProject(manager.jwt());
        addMember(projectId, removed.userId(), MemberRole.MEMBER);
        addMember(projectId, eligible.userId(), MemberRole.MEMBER);
        long taskId = createTask(projectId, manager.userId(), manager.userId());
        var adapter = context.getBean(com.taskpilot.projects.aiquery.adapter.out.AiQueryModuleAdapter.class);
        pausableExecutor.awaitIdle(10, TimeUnit.SECONDS);
        fakeOneSignal.clear();
        pausableExecutor.pauseNextBatch(1);
        assertThat(adapter.assignTaskToMember(taskId, removed.userId(), "scope regression", manager.userId(), false)).isNotNull();
        assertThat(pausableExecutor.awaitPaused(5, TimeUnit.SECONDS)).isTrue();
        context.getBean(ProjectServiceImpl.class).removeMember(projectId, removed.userId(), manager.userId());
        pausableExecutor.resumeAndAwaitCompletion(10, TimeUnit.SECONDS);
        pausableExecutor.awaitIdle(10, TimeUnit.SECONDS);
        assertThat(fakeOneSignal.calls()).noneMatch(call -> call.userId().equals(String.valueOf(removed.userId())));

        adapter.assignTaskToMember(taskId, eligible.userId(), "eligible control", manager.userId(), false);
        pausableExecutor.awaitIdle(10, TimeUnit.SECONDS);
        assertThat(fakeOneSignal.calls()).anyMatch(call -> call.userId().equals(String.valueOf(eligible.userId()))
                && call.title().equals("Task Assigned"));
    }

    @Test
    void personalNotificationJoinsLockedBusinessTransactionAndPreservesRollback() throws Exception {
        assertNotificationTransactionParticipation(false);
    }

    @Test
    void projectNotificationJoinsLockedBusinessTransactionAndPreservesRollback() throws Exception {
        assertNotificationTransactionParticipation(true);
    }

    private void assertNotificationTransactionParticipation(boolean projectScoped) throws Exception {
        var user = createUserAndLogin();
        long projectId = createProject(user.jwt());
        var jdbc = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var service = context.getBean(NotificationService.class);
        var command = new com.taskpilot.contracts.user.dto.SystemNotificationCommandDto(
                user.userId(), "Transaction notice", "Must roll back with business data", "/profile",
                com.taskpilot.contracts.user.dto.NotificationTypeDto.SYSTEM,
                projectScoped ? projectId : null, null);
        for (boolean rollback : new boolean[]{true, false}) {
            tx.executeWithoutResult(status -> {
                // Same order as AI confirmation: project, then current recipient account.
                jdbc.queryForObject("select id from projects where id=? for update", Long.class, projectId);
                jdbc.queryForObject("select id from users where id=? for update", Long.class, user.userId());
                service.createNotification(command);
                assertThat(jdbc.queryForObject("select count(*) from notifications where user_id=?", Long.class, user.userId()))
                        .isEqualTo(1);
                assertThat(fakeOneSignal.calls()).isEmpty();
                if (rollback) status.setRollbackOnly();
            });
            // The scoped dispatcher now owns the async boundary (the fake transport is synchronous).
            pausableExecutor.awaitIdle(10, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select count(*) from notifications where user_id=?", Long.class, user.userId()))
                    .isEqualTo(rollback ? 0 : 1);
            assertThat(fakeOneSignal.calls()).hasSize(rollback ? 0 : 1);
        }
    }

    private static HttpResponse<String> postJson(String path, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
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
                // Bound regressions where REQUIRES_NEW waits on its own suspended business transaction.
                "--spring.datasource.hikari.connection-init-sql=SET lock_timeout = '2s'",
                "--spring.jpa.hibernate.ddl-auto=none",
                "--spring.flyway.enabled=false",
                "--spring.mail.host=localhost",
                "--spring.mail.port=1025",
                "--spring.task.scheduling.enabled=false",
                "--ai.gemini.api-key=local-dummy-notif",
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
    static class FakeDispatchConfig implements AsyncConfigurer {
        private final PausableTaskExecutor executor = new PausableTaskExecutor();

        @Bean
        @Primary
        PausableTaskExecutor pausableTaskExecutor() {
            return executor;
        }

        @Override
        public java.util.concurrent.Executor getAsyncExecutor() {
            return executor;
        }

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

    static class PausableTaskExecutor implements TaskExecutor {
        private final java.util.concurrent.atomic.AtomicInteger outstanding = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicReference<Throwable> taskFailure = new java.util.concurrent.atomic.AtomicReference<>();
        private final ThreadPoolTaskExecutor delegate;
        private final AtomicBoolean pauseEnabled = new AtomicBoolean(false);
        private final AtomicReference<CountDownLatch> pausedLatch = new AtomicReference<>(new CountDownLatch(0));
        private final AtomicReference<CountDownLatch> releaseLatch = new AtomicReference<>(new CountDownLatch(0));
        private final AtomicReference<CountDownLatch> completedLatch = new AtomicReference<>(new CountDownLatch(0));

        PausableTaskExecutor() {
            this.delegate = new ThreadPoolTaskExecutor();
            this.delegate.setCorePoolSize(2);
            this.delegate.setMaxPoolSize(4);
            this.delegate.setThreadNamePrefix("p4-notif-async-");
            this.delegate.initialize();
        }

        void reset() {
            pauseEnabled.set(false);
            releaseLatch.get().countDown();
        }

        void pauseNextBatch(int expectedTasks) {
            pausedLatch.set(new CountDownLatch(expectedTasks));
            releaseLatch.set(new CountDownLatch(1));
            completedLatch.set(new CountDownLatch(expectedTasks));
            pauseEnabled.set(true);
        }

        boolean awaitPaused(long timeout, TimeUnit unit) throws InterruptedException {
            return pausedLatch.get().await(timeout, unit);
        }

        void resumeAndAwaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
            pauseEnabled.set(false);
            releaseLatch.get().countDown();
            assertThat(completedLatch.get().await(timeout, unit)).as("paused tasks completed").isTrue();
        }

        void awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (outstanding.get() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertThat(outstanding.get()).as("outstanding tasks, including nested submissions").isZero();
            assertThat(taskFailure.get()).as("async executor failure").isNull();
        }

        @jakarta.annotation.PreDestroy
        void shutdown() {
            delegate.shutdown();
        }

        @Override
        public void execute(Runnable task) {
            boolean shouldPause = pauseEnabled.get();
            CountDownLatch pauseGate = pausedLatch.get();
            CountDownLatch releaseGate = releaseLatch.get();
            CountDownLatch doneGate = completedLatch.get();

            outstanding.incrementAndGet();
            try {
                delegate.execute(() -> {
                    try {
                        if (shouldPause) {
                            pauseGate.countDown();
                            if (!releaseGate.await(30, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Timed out waiting for explicit test release");
                            }
                        }
                        task.run();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        taskFailure.compareAndSet(null, ex);
                    } catch (Throwable ex) {
                        taskFailure.compareAndSet(null, ex);
                    } finally {
                        if (shouldPause) {
                            doneGate.countDown();
                        }
                        outstanding.decrementAndGet();
                    }
                });
            } catch (RuntimeException ex) {
                outstanding.decrementAndGet();
                throw ex;
            }
        }
    }

    static class RecordingOneSignalService extends OneSignalService {
        record PushCall(String userId, String title, String message, String linkAction,
                        java.time.Instant sentAt, String thread) {
        }

        private final List<PushCall> recordedCalls = new CopyOnWriteArrayList<>();

        @Override
        public void sendNotificationToUser(String targetUserId, String title, String message, String linkAction) {
            recordedCalls.add(new PushCall(targetUserId, title, message, linkAction,
                    java.time.Instant.now(), Thread.currentThread().getName()));
        }

        List<PushCall> calls() {
            return List.copyOf(recordedCalls);
        }

        void clear() {
            recordedCalls.clear();
        }
    }
}
