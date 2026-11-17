package com.taskpilot.app.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.app.TaskPilotApplication;
import com.taskpilot.app.migration.MigrationDatabaseConfiguration;
import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.contracts.user.dto.SecurityChangeReason;
import com.taskpilot.infrastructure.config.security.JwtProperties;
import com.taskpilot.infrastructure.config.security.JwtService;
import com.taskpilot.infrastructure.config.security.JwtTokenBlocklistService;
import com.taskpilot.infrastructure.notification.OneSignalService;
import com.taskpilot.projects.chat.dto.SendChatMessageRequest;
import com.taskpilot.projects.chat.service.ProjectChatService;
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
import com.taskpilot.users.auth.service.UserSessionService;
import com.taskpilot.users.common.entity.RefreshTokenEntity;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.enums.UserStatus;
import com.taskpilot.users.common.repository.RefreshTokenRepository;
import com.taskpilot.users.common.repository.UserRepository;
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
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Real PostgreSQL 17 + embedded STOMP broker + HTTP SSE integration proof for P4.2:
 * - Ordered persisted chat delivery & outsider/foreign/direct-topic denials
 * - Immediate and idle revocation on remove/leave/logout/suspend/demote/password-reset/expiry
 * - Single-session logout preserving other valid sessions
 * - Rollback non-delivery & renewed valid-session reconnect
 * - Two separate Spring contexts sharing isolated PG17 where cleanup event is missed in one
 */
@Testcontainers
class P4RealtimeRevocationIntegrationTest {

    private static final String TEST_JWT_SECRET = "taskpilot-test-only-p4-realtime-jwt-signing-key-2026-32bytes";
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
    private static String wsUrl;
    private static RecordingOneSignalService fakeOneSignal;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startApp() {
        Flyway flyway = MigrationDatabaseConfiguration.configure(
                Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        ).load();
        var migrationResult = flyway.migrate();
        assertThat(migrationResult.migrationsExecuted).isGreaterThanOrEqualTo(6);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        context = new SpringApplicationBuilder(TaskPilotApplication.class, FakeExternalProvidersConfig.class)
                .run(appArgs("ctx1"));
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        baseUrl = "http://127.0.0.1:" + port;
        wsUrl = "ws://127.0.0.1:" + port + "/ws/chat";
        fakeOneSignal = context.getBean(RecordingOneSignalService.class);
        jdbc = context.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stopApp() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void resetFakes() {
        fakeOneSignal.clear();
    }

    record UserSessionFixture(
            long userId,
            String email,
            String password,
            String jwt,
            String refreshToken,
            UUID sessionId,
            CurrentActor actor) {
    }

    record SseEventRecord(String event, String data) {
    }

    static class TestStompClient implements AutoCloseable {
        final WebSocketStompClient client;
        final StompSession session;
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        final CountDownLatch disconnected = new CountDownLatch(1);

        private TestStompClient(WebSocketStompClient client, StompSession session) {
            this.client = client;
            this.session = session;
        }

        void subscribeChat(long projectId) {
            session.subscribe("/topic/projects/" + projectId + "/chat", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return String.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    if (payload != null) {
                        messages.add(payload.toString());
                    }
                }
            });
        }

        void subscribeRaw(String destination) {
            session.subscribe(destination, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return String.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    if (payload != null) {
                        messages.add(payload.toString());
                    }
                }
            });
        }

        @Override
        public void close() {
            try {
                if (session.isConnected()) {
                    session.disconnect();
                }
            } catch (Exception ignored) {
            }
            client.stop();
        }
    }

    static final class TestStompClientWithQueues extends TestStompClient {
        private final BlockingQueue<String> sharedMessages;
        private final BlockingQueue<String> sharedErrors;
        private final CountDownLatch sharedDisconnect;

        TestStompClientWithQueues(
                WebSocketStompClient client,
                StompSession session,
                BlockingQueue<String> sharedMessages,
                BlockingQueue<String> sharedErrors,
                CountDownLatch sharedDisconnect
        ) {
            super(client, session);
            this.sharedMessages = sharedMessages;
            this.sharedErrors = sharedErrors;
            this.sharedDisconnect = sharedDisconnect;
        }

        @Override
        void subscribeChat(long projectId) {
            session.subscribe("/topic/projects/" + projectId + "/chat", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return byte[].class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    if (payload instanceof byte[] bytes) {
                        sharedMessages.add(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                    } else if (payload != null) {
                        sharedMessages.add(payload.toString());
                    }
                }
            });
        }

        @Override
        void subscribeRaw(String destination) {
            session.subscribe(destination, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return byte[].class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    if (payload instanceof byte[] bytes) {
                        sharedMessages.add(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                    } else if (payload != null) {
                        sharedMessages.add(payload.toString());
                    }
                }
            });
        }

        BlockingQueue<String> sharedMessages() {
            return sharedMessages;
        }

        BlockingQueue<String> sharedErrors() {
            return sharedErrors;
        }

        CountDownLatch sharedDisconnect() {
            return sharedDisconnect;
        }
    }

    static class TestSseReader implements AutoCloseable {
        final int statusCode;
        final BlockingQueue<SseEventRecord> events;
        final CountDownLatch closed;
        private final CompletableFuture<Void> readerFuture;
        private final AtomicBoolean stop;

        private TestSseReader(
                int statusCode,
                BlockingQueue<SseEventRecord> events,
                CountDownLatch closed,
                CompletableFuture<Void> readerFuture,
                AtomicBoolean stop
        ) {
            this.statusCode = statusCode;
            this.events = events;
            this.closed = closed;
            this.readerFuture = readerFuture;
            this.stop = stop;
        }

        static TestSseReader open(String url, String jwt) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + jwt)
                    .header("Accept", "text/event-stream")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<Stream<String>> response = HTTP.send(request, HttpResponse.BodyHandlers.ofLines());
            BlockingQueue<SseEventRecord> queue = new LinkedBlockingQueue<>();
            CountDownLatch closedLatch = new CountDownLatch(1);
            AtomicBoolean stopFlag = new AtomicBoolean(false);

            if (response.statusCode() != 200) {
                closedLatch.countDown();
                return new TestSseReader(response.statusCode(), queue, closedLatch,
                        CompletableFuture.completedFuture(null), stopFlag);
            }

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try (Stream<String> lines = response.body()) {
                    String currentEvent = "message";
                    StringBuilder dataBuf = new StringBuilder();
                    var it = lines.iterator();
                    while (!stopFlag.get() && it.hasNext()) {
                        String line = it.next();
                        if (line.isEmpty()) {
                            if (dataBuf.length() > 0) {
                                queue.add(new SseEventRecord(currentEvent, dataBuf.toString()));
                                currentEvent = "message";
                                dataBuf.setLength(0);
                            }
                        } else if (line.startsWith("event:")) {
                            currentEvent = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            if (dataBuf.length() > 0) {
                                dataBuf.append('\n');
                            }
                            dataBuf.append(line.substring(5).trim());
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    closedLatch.countDown();
                }
            });

            return new TestSseReader(response.statusCode(), queue, closedLatch, future, stopFlag);
        }

        public BlockingQueue<SseEventRecord> events() {
            return events;
        }

        public CountDownLatch closed() {
            return closed;
        }

        @Override
        public void close() {
            stop.set(true);
            readerFuture.cancel(true);
        }
    }

    private static TestStompClientWithQueues connectStomp(String url, String jwt) throws Exception {
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new SimpleMessageConverter());
        StompHeaders connectHeaders = new StompHeaders();
        if (jwt != null) {
            connectHeaders.add(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
        }
        BlockingQueue<String> errorQueue = new LinkedBlockingQueue<>();
        BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
        CountDownLatch disconnectLatch = new CountDownLatch(1);

        StompSession session = stompClient.connectAsync(
                url,
                new WebSocketHttpHeaders(),
                connectHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void handleException(StompSession s, StompCommand command, StompHeaders headers,
                                                byte[] payload, Throwable exception) {
                        errorQueue.add(command + ":" + exception.getMessage());
                    }

                    @Override
                    public void handleTransportError(StompSession s, Throwable exception) {
                        errorQueue.add("TRANSPORT:" + exception.getMessage());
                        disconnectLatch.countDown();
                    }
                }
        ).get(10, TimeUnit.SECONDS);
        return new TestStompClientWithQueues(stompClient, session, messageQueue, errorQueue, disconnectLatch);
    }

    private UserSessionFixture createUserAndLogin(UserRole role) throws Exception {
        String email = "p4-rt-" + UUID.randomUUID() + "@example.com";
        String password = "Pass!12345";
        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setFullName("RT User " + email.substring(0, 12));
        user.setRole(role);
        user.setStatus(UserStatus.AVAILABLE);
        user.setAccountState(AccountLifecycle.ACTIVE);
        user.setPassword(context.getBean(PasswordEncoder.class).encode(password));
        user.setEmailVerified(true);
        user = context.getBean(UserRepository.class).saveAndFlush(user);

        return loginUser(user.getId(), email, password, role.name());
    }

    private UserSessionFixture loginUser(long userId, String email, String password, String role) throws Exception {
        HttpResponse<String> login = postJson(baseUrl, "/api/v1/auth/login",
                Map.of("email", email, "password", password), null);
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        JsonNode data = MAPPER.readTree(login.body()).path("data");
        String jwt = data.path("token").asText();
        String refreshToken = data.path("refreshToken").asText();
        var claims = context.getBean(JwtService.class).parseAndValidate(jwt).orElseThrow();
        return new UserSessionFixture(
                userId, email, password, jwt, refreshToken, claims.sessionId(),
                new CurrentActor(userId, email, role, claims.tokenVersion()));
    }

    private long createProject(String jwt) throws Exception {
        HttpResponse<String> resp = postJson(baseUrl, "/api/v1/projects",
                Map.of("name", "P4 RT " + UUID.randomUUID()), jwt);
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(201);
        return MAPPER.readTree(resp.body()).path("data").path("id").asLong();
    }

    private void addMember(long projectId, long userId, MemberRole role) {
        ProjectMemberRepository repo = context.getBean(ProjectMemberRepository.class);
        repo.saveAndFlush(ProjectMemberEntity.builder()
                .projectId(projectId)
                .userId(userId)
                .role(role)
                .build());
    }

    private long createTask(long projectId, long reporterId) {
        TaskRepository taskRepo = context.getBean(TaskRepository.class);
        TaskEntity task = taskRepo.saveAndFlush(TaskEntity.builder()
                .projectId(projectId)
                .title("Realtime Task " + UUID.randomUUID())
                .status(TaskStatus.TODO)
                .priority(PriorityLevel.MEDIUM)
                .reporterId(reporterId)
                .build());
        return task.getId();
    }

    @Test
    void verificationRevokedAfterStompConnect_cannotPersistChat() throws Exception {
        UserSessionFixture member = createUserAndLogin(UserRole.USER);
        long projectId = createProject(member.jwt());
        try (var stomp = connectStomp(wsUrl, member.jwt())) {
            String destination = "/app/projects/" + projectId + "/chat.sendMessage";
            stomp.session.send(destination,
                    "{\"content\":\"verified control\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (jdbc.queryForObject("SELECT COUNT(*) FROM public.project_chat_messages WHERE project_id = ?",
                    Long.class, projectId) == 0 && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.project_chat_messages WHERE project_id = ?",
                    Long.class, projectId)).isEqualTo(1);
            jdbc.update("UPDATE public.users SET email_verified = false WHERE id = ?", member.userId());
            stomp.session.send(destination,
                    "{\"content\":\"must not persist\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(stomp.sharedErrors().poll(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.project_chat_messages WHERE project_id = ?",
                    Long.class, projectId)).isEqualTo(1);
        }
    }

    @Test
    void validMembersExchangeOrderedPersistedChatAndComments_outsiderForeignAndDirectTopicDenied() throws Exception {
        UserSessionFixture memberA = createUserAndLogin(UserRole.USER);
        UserSessionFixture memberB = createUserAndLogin(UserRole.USER);
        UserSessionFixture outsider = createUserAndLogin(UserRole.USER);

        long projectId = createProject(memberA.jwt());
        addMember(projectId, memberB.userId(), MemberRole.MEMBER);
        long foreignProjectId = createProject(outsider.jwt());
        long taskId = createTask(projectId, memberA.userId());

        try (var stompA = connectStomp(wsUrl, memberA.jwt());
             var stompB = connectStomp(wsUrl, memberB.jwt());
             var stompOutsider = connectStomp(wsUrl, outsider.jwt());
             var commentSseB = TestSseReader.open(baseUrl + "/api/v1/tasks/" + taskId + "/comments/stream", memberB.jwt());
             var commentSseOutsider = TestSseReader.open(baseUrl + "/api/v1/tasks/" + taskId + "/comments/stream", outsider.jwt())) {

            assertThat(commentSseB.statusCode).isEqualTo(200);
            SseEventRecord connectedEvt = commentSseB.events().poll(5, TimeUnit.SECONDS);
            assertThat(connectedEvt).isNotNull();
            assertThat(connectedEvt.event()).isEqualTo("comment.connected");
            assertThat(commentSseOutsider.statusCode).isEqualTo(403);

            stompA.subscribeChat(projectId);
            stompB.subscribeChat(projectId);
            // Brief sync via dummy wait until subscribe frames are processed on broker
            Thread.sleep(250);

            // Outsider subscribe denied
            stompOutsider.subscribeChat(projectId);
            String outsiderErr = stompOutsider.sharedErrors().poll(5, TimeUnit.SECONDS);
            assertThat(outsiderErr).isNotNull();

            // Foreign project and wildcard subscribe denied
            try (var stompForeign = connectStomp(wsUrl, memberA.jwt())) {
                stompForeign.subscribeChat(foreignProjectId);
                assertThat(stompForeign.sharedErrors().poll(5, TimeUnit.SECONDS)).isNotNull();
            }
            try (var stompWildcard = connectStomp(wsUrl, memberA.jwt())) {
                stompWildcard.subscribeRaw("/topic/**");
                assertThat(stompWildcard.sharedErrors().poll(5, TimeUnit.SECONDS)).isNotNull();
            }

            // Direct-topic SEND denied
            try (var stompDirectSend = connectStomp(wsUrl, memberA.jwt())) {
                stompDirectSend.session.send("/topic/projects/" + projectId + "/chat",
                        "{\"content\":\"spoof\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                assertThat(stompDirectSend.sharedErrors().poll(5, TimeUnit.SECONDS)).isNotNull();
            }

            // Exchange ordered persisted messages via REST API
            HttpResponse<String> m1 = postJson(baseUrl, "/api/v1/projects/" + projectId + "/chat/messages",
                    Map.of("content", "msg-1", "messageType", "TEXT"), memberA.jwt());
            assertThat(m1.statusCode()).isEqualTo(200);
            HttpResponse<String> m2 = postJson(baseUrl, "/api/v1/projects/" + projectId + "/chat/messages",
                    Map.of("content", "msg-2", "messageType", "TEXT"), memberB.jwt());
            assertThat(m2.statusCode()).isEqualTo(200);

            String bMsg1 = stompB.sharedMessages().poll(5, TimeUnit.SECONDS);
            String bMsg2 = stompB.sharedMessages().poll(5, TimeUnit.SECONDS);
            assertThat(bMsg1).contains("msg-1");
            assertThat(bMsg2).contains("msg-2");

            String aMsg1 = stompA.sharedMessages().poll(5, TimeUnit.SECONDS);
            String aMsg2 = stompA.sharedMessages().poll(5, TimeUnit.SECONDS);
            assertThat(aMsg1).contains("msg-1");
            assertThat(aMsg2).contains("msg-2");

            assertThat(stompOutsider.sharedMessages().poll(300, TimeUnit.MILLISECONDS)).isNull();

            // Verify persisted in DB
            Integer persistedCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM public.project_chat_messages WHERE project_id = ?",
                    Integer.class, projectId);
            assertThat(persistedCount).isEqualTo(2);

            // Comment SSE receives persisted comment
            HttpResponse<String> commentResp = postJson(baseUrl, "/api/v1/tasks/" + taskId + "/comments",
                    Map.of("content", "Live comment 1", "mentionedUserIds", List.of()), memberA.jwt());
            assertThat(commentResp.statusCode()).isEqualTo(201);
            SseEventRecord commentEvt = commentSseB.events().poll(5, TimeUnit.SECONDS);
            assertThat(commentEvt).isNotNull();
            assertThat(commentEvt.event()).isEqualTo("comment.created");
            assertThat(commentEvt.data()).contains("Live comment 1");
        }
    }

    @Test
    void subscribeThenLogoutRemoveLeaveDemoteSuspendResetAndExpiry_blocksIdleDeliveryAndPreservesOtherSession() throws Exception {
        UserSessionFixture manager = createUserAndLogin(UserRole.ADMIN);
        UserSessionFixture member = createUserAndLogin(UserRole.USER);
        UserSessionFixture otherMember = createUserAndLogin(UserRole.USER);
        long projectId = createProject(manager.jwt());
        addMember(projectId, member.userId(), MemberRole.MEMBER);
        addMember(projectId, otherMember.userId(), MemberRole.MEMBER);
        long taskId = createTask(projectId, manager.userId());

        // 1. Logout Session 1 (member) closes Session 1 while Session 2 (otherMember) remains live
        try (var stompS1 = connectStomp(wsUrl, member.jwt());
             var stompS2 = connectStomp(wsUrl, otherMember.jwt());
             var notifS1 = TestSseReader.open(baseUrl + "/api/v1/notifications/my/stream", member.jwt());
             var notifS2 = TestSseReader.open(baseUrl + "/api/v1/notifications/my/stream", otherMember.jwt())) {

            assertThat(notifS1.statusCode).isEqualTo(200);
            assertThat(notifS2.statusCode).isEqualTo(200);
            assertThat(notifS1.events().poll(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(notifS2.events().poll(5, TimeUnit.SECONDS)).isNotNull();

            stompS1.subscribeChat(projectId);
            stompS2.subscribeChat(projectId);
            Thread.sleep(250);

            HttpResponse<String> logoutS1 = postJson(baseUrl, "/api/v1/auth/logout",
                    Map.of("refreshToken", member.refreshToken()), member.jwt());
            assertThat(logoutS1.statusCode()).as(logoutS1.body()).isEqualTo(200);

            assertThat(stompS1.sharedDisconnect().await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(notifS1.closed().await(5, TimeUnit.SECONDS)).isTrue();

            postJson(baseUrl, "/api/v1/projects/" + projectId + "/chat/messages",
                    Map.of("content", "after-s1-logout", "messageType", "TEXT"), manager.jwt());

            assertThat(stompS2.sharedMessages().poll(5, TimeUnit.SECONDS)).contains("after-s1-logout");
            assertThat(stompS1.sharedMessages().poll(300, TimeUnit.MILLISECONDS)).isNull();
            assertThat(notifS2.closed().await(200, TimeUnit.MILLISECONDS)).isFalse();
        }

        // 2. Remove member closes idle STOMP + comment SSE
        UserSessionFixture memberRemovedSession = loginUser(member.userId(), member.email(), member.password(), "USER");
        try (var stompRemoved = connectStomp(wsUrl, memberRemovedSession.jwt());
             var stompMgr = connectStomp(wsUrl, manager.jwt());
             var commentSseRemoved = TestSseReader.open(
                     baseUrl + "/api/v1/tasks/" + taskId + "/comments/stream", memberRemovedSession.jwt())) {

            assertThat(commentSseRemoved.statusCode).isEqualTo(200);
            assertThat(commentSseRemoved.events().poll(5, TimeUnit.SECONDS).event()).isEqualTo("comment.connected");
            stompRemoved.subscribeChat(projectId);
            stompMgr.subscribeChat(projectId);
            Thread.sleep(250);

            context.getBean(ProjectServiceImpl.class).removeMember(projectId, member.userId(), manager.userId());

            assertThat(stompRemoved.sharedDisconnect().await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(commentSseRemoved.closed().await(5, TimeUnit.SECONDS)).isTrue();

            postJson(baseUrl, "/api/v1/projects/" + projectId + "/chat/messages",
                    Map.of("content", "secret-after-remove", "messageType", "TEXT"), manager.jwt());
            postJson(baseUrl, "/api/v1/tasks/" + taskId + "/comments",
                    Map.of("content", "secret-comment-after-remove", "mentionedUserIds", List.of()), manager.jwt());

            assertThat(stompMgr.sharedMessages().poll(5, TimeUnit.SECONDS)).contains("secret-after-remove");
            assertThat(stompRemoved.sharedMessages().poll(300, TimeUnit.MILLISECONDS)).isNull();
            assertThat(drainPrivatePayloads(commentSseRemoved.events())).isEmpty();
        }

        // 3. Re-add and leave project -> idle STOMP closes & gets no later messages
        addMember(projectId, member.userId(), MemberRole.MEMBER);
        UserSessionFixture memberLeaveSession = loginUser(member.userId(), member.email(), member.password(), "USER");
        try (var stompLeave = connectStomp(wsUrl, memberLeaveSession.jwt());
             var stompMgr = connectStomp(wsUrl, manager.jwt())) {
            stompLeave.subscribeChat(projectId);
            stompMgr.subscribeChat(projectId);
            Thread.sleep(250);

            context.getBean(ProjectServiceImpl.class).leaveProject(projectId, member.userId());
            assertThat(stompLeave.sharedDisconnect().await(5, TimeUnit.SECONDS)).isTrue();

            postJson(baseUrl, "/api/v1/projects/" + projectId + "/chat/messages",
                    Map.of("content", "secret-after-leave", "messageType", "TEXT"), manager.jwt());
            assertThat(stompMgr.sharedMessages().poll(5, TimeUnit.SECONDS)).contains("secret-after-leave");
            assertThat(stompLeave.sharedMessages().poll(300, TimeUnit.MILLISECONDS)).isNull();
        }

        // 4. Role demote (account role change), password reset, suspend, and renewed login reconnect
        UserSessionFixture demotedTarget = createUserAndLogin(UserRole.ADMIN);
        addMember(projectId, demotedTarget.userId(), MemberRole.MEMBER);
        try (var stompDemote = connectStomp(wsUrl, demotedTarget.jwt());
             var stompMgr = connectStomp(wsUrl, manager.jwt())) {
            stompDemote.subscribeChat(projectId);
            stompMgr.subscribeChat(projectId);
            Thread.sleep(250);

            HttpResponse<String> demoteResp = putJson(baseUrl, "/api/v1/admin/users/" + demotedTarget.userId(),
                    Map.of("role", "USER"), manager.jwt());
            assertThat(demoteResp.statusCode()).as(demoteResp.body()).isEqualTo(200);
            assertThat(stompDemote.sharedDisconnect().await(5, TimeUnit.SECONDS)).isTrue();

            postJson(baseUrl, "/api/v1/projects/" + projectId + "/chat/messages",
                    Map.of("content", "secret-after-demote", "messageType", "TEXT"), manager.jwt());
            assertThat(stompMgr.sharedMessages().poll(5, TimeUnit.SECONDS)).contains("secret-after-demote");
            assertThat(stompDemote.sharedMessages().poll(300, TimeUnit.MILLISECONDS)).isNull();
        }

        // Password reset -> old socket closed, renewed valid session reconnects
        UserSessionFixture resetSession = loginUser(demotedTarget.userId(), demotedTarget.email(), demotedTarget.password(), "USER");
        try (var stompReset = connectStomp(wsUrl, resetSession.jwt());
             var stompMgr = connectStomp(wsUrl, manager.jwt())) {
            stompReset.subscribeChat(projectId);
            stompMgr.subscribeChat(projectId);
            Thread.sleep(250);

            context.getBean(UserSessionService.class).invalidateAll(demotedTarget.userId(), SecurityChangeReason.PASSWORD_RESET);
            assertThat(stompReset.sharedDisconnect().await(5, TimeUnit.SECONDS)).isTrue();

            // Renewed valid session reconnects and receives
            UserSessionFixture renewed = loginUser(demotedTarget.userId(), demotedTarget.email(), demotedTarget.password(), "USER");
            try (var stompRenewed = connectStomp(wsUrl, renewed.jwt())) {
                stompRenewed.subscribeChat(projectId);
                Thread.sleep(250);
                postJson(baseUrl, "/api/v1/projects/" + projectId + "/chat/messages",
                        Map.of("content", "hello-renewed-session", "messageType", "TEXT"), manager.jwt());
                assertThat(stompRenewed.sharedMessages().poll(5, TimeUnit.SECONDS)).contains("hello-renewed-session");
            }
        }

        // Admin suspend -> closes idle STOMP + notification SSE
        UserSessionFixture suspendedTarget = loginUser(demotedTarget.userId(), demotedTarget.email(), demotedTarget.password(), "USER");
        try (var stompSuspend = connectStomp(wsUrl, suspendedTarget.jwt());
             var notifSuspend = TestSseReader.open(baseUrl + "/api/v1/notifications/my/stream", suspendedTarget.jwt())) {
            assertThat(notifSuspend.statusCode).isEqualTo(200);
            assertThat(notifSuspend.events().poll(5, TimeUnit.SECONDS)).isNotNull();
            stompSuspend.subscribeChat(projectId);
            Thread.sleep(250);

            HttpResponse<String> suspendResp = postJson(baseUrl, "/api/v1/admin/users/" + demotedTarget.userId() + "/suspend",
                    Map.of("reason", "security-test"), manager.jwt());
            assertThat(suspendResp.statusCode()).as(suspendResp.body()).isEqualTo(200);
            assertThat(stompSuspend.sharedDisconnect().await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(notifSuspend.closed().await(5, TimeUnit.SECONDS)).isTrue();
        }

        // Short-TTL JWT idle expiry -> expires without any frame sent
        UserSessionFixture expiryUser = createUserAndLogin(UserRole.USER);
        addMember(projectId, expiryUser.userId(), MemberRole.MEMBER);
        Instant now = Instant.now();
        String shortJwt = io.jsonwebtoken.Jwts.builder()
                .issuer("taskpilot")
                .audience().add("taskpilot-api").and()
                .subject(Long.toString(expiryUser.userId()))
                .id(UUID.randomUUID().toString())
                .claim("token_schema", JwtService.TOKEN_SCHEMA_V2)
                .claim("ver", expiryUser.actor().tokenVersion())
                .claim("sid", expiryUser.sessionId().toString())
                .claim("role", "USER")
                .issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(now.plusMillis(2500L)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        TEST_JWT_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();

        try (var stompExpiring = connectStomp(wsUrl, shortJwt);
             var sseExpiring = TestSseReader.open(baseUrl + "/api/v1/notifications/my/stream", shortJwt)) {
            assertThat(sseExpiring.statusCode).isEqualTo(200);
            assertThat(sseExpiring.events().poll(5, TimeUnit.SECONDS)).isNotNull();
            stompExpiring.subscribeChat(projectId);

            assertThat(stompExpiring.sharedDisconnect().await(8, TimeUnit.SECONDS)).isTrue();
            assertThat(sseExpiring.closed().await(8, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void transactionRollbackEmitsZeroStompAndSseEvents() throws Exception {
        UserSessionFixture memberA = createUserAndLogin(UserRole.USER);
        UserSessionFixture memberB = createUserAndLogin(UserRole.USER);
        long projectId = createProject(memberA.jwt());
        addMember(projectId, memberB.userId(), MemberRole.MEMBER);
        long taskId = createTask(projectId, memberA.userId());

        try (var stompB = connectStomp(wsUrl, memberB.jwt());
             var commentSseB = TestSseReader.open(baseUrl + "/api/v1/tasks/" + taskId + "/comments/stream", memberB.jwt())) {

            assertThat(commentSseB.statusCode).isEqualTo(200);
            assertThat(commentSseB.events().poll(5, TimeUnit.SECONDS).event()).isEqualTo("comment.connected");
            stompB.subscribeChat(projectId);
            Thread.sleep(250);

            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            ProjectChatService chatService = context.getBean(ProjectChatService.class);
            TaskCommentService commentService = context.getBean(TaskCommentService.class);

            tx.executeWithoutResult(status -> {
                chatService.sendMessage(projectId, memberA.actor(),
                        new SendChatMessageRequest("phantom-chat-msg", "TEXT", null));
                commentService.createComment(taskId,
                        new CreateTaskCommentRequest("phantom-comment", null, Set.of()), memberA.userId());
                status.setRollbackOnly();
            });

            assertThat(stompB.sharedMessages().poll(400, TimeUnit.MILLISECONDS)).isNull();
            assertThat(drainPrivatePayloads(commentSseB.events())).isEmpty();
        }
    }

    @Test
    void twoAppContextsSharingIsolatedPostgres_missedCleanupEventStillDeniesUnauthorizedDelivery() throws Exception {
        UserSessionFixture manager = createUserAndLogin(UserRole.USER);
        UserSessionFixture member = createUserAndLogin(UserRole.USER);
        long projectId = createProject(manager.jwt());
        addMember(projectId, member.userId(), MemberRole.MEMBER);
        long taskId = createTask(projectId, manager.userId());

        try (ConfigurableApplicationContext context2 = new SpringApplicationBuilder(
                TaskPilotApplication.class, FakeExternalProvidersConfig.class).run(appArgs("ctx2"))) {

            try (var stompMemberCtx1 = connectStomp(wsUrl, member.jwt());
                 var stompMgrCtx1 = connectStomp(wsUrl, manager.jwt());
                 var commentSseMemberCtx1 = TestSseReader.open(
                         baseUrl + "/api/v1/tasks/" + taskId + "/comments/stream", member.jwt())) {

                assertThat(commentSseMemberCtx1.statusCode).isEqualTo(200);
                assertThat(commentSseMemberCtx1.events().poll(5, TimeUnit.SECONDS).event()).isEqualTo("comment.connected");
                stompMemberCtx1.subscribeChat(projectId);
                stompMgrCtx1.subscribeChat(projectId);
                Thread.sleep(250);

                // Revoke membership in context2 ONLY — context1 NEVER receives ProjectAccessRevokedEvent!
                context2.getBean(ProjectServiceImpl.class).removeMember(projectId, member.userId(), manager.userId());

                // Also logout a separate user's session in context2 so context1 misses UserSessionRevokedEvent too
                UserSessionFixture s2 = createUserAndLogin(UserRole.USER);
                context2.getBean(UserSessionService.class).logoutSession(null, "Bearer " + s2.jwt());
                assertThat(context.getBean(RefreshTokenRepository.class).findBySessionId(s2.sessionId())
                        .orElseThrow().isRevoked()).isTrue();

                // Broadcast on context1: manager receives, removed member on context1 receives NOTHING
                postJson(baseUrl, "/api/v1/projects/" + projectId + "/chat/messages",
                        Map.of("content", "ctx1-post-cross-ctx-remove", "messageType", "TEXT"), manager.jwt());
                postJson(baseUrl, "/api/v1/tasks/" + taskId + "/comments",
                        Map.of("content", "ctx1-comment-post-cross-ctx-remove", "mentionedUserIds", List.of()), manager.jwt());

                assertThat(stompMgrCtx1.sharedMessages().poll(5, TimeUnit.SECONDS))
                        .contains("ctx1-post-cross-ctx-remove");
                assertThat(stompMemberCtx1.sharedMessages().poll(400, TimeUnit.MILLISECONDS)).isNull();
                assertThat(stompMemberCtx1.sharedDisconnect().await(5, TimeUnit.SECONDS)).isTrue();

                assertThat(drainPrivatePayloads(commentSseMemberCtx1.events())).isEmpty();
                assertThat(commentSseMemberCtx1.closed().await(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private static List<SseEventRecord> drainPrivatePayloads(BlockingQueue<SseEventRecord> queue) {
        List<SseEventRecord> list = new ArrayList<>();
        queue.drainTo(list);
        return list.stream()
                .filter(e -> !"comment.connected".equals(e.event()) && !"realtime.access_lost".equals(e.event()))
                .toList();
    }

    private static HttpResponse<String> postJson(String rootUrl, String path, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(rootUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> putJson(String rootUrl, String path, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(rootUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String[] appArgs(String tag) {
        return new String[]{
                "--server.port=0",
                "--spring.jmx.enabled=false",
                "--spring.profiles.active=dev",
                "--spring.main.allow-bean-definition-overriding=true",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.datasource.hikari.maximum-pool-size=8",
                "--spring.jpa.hibernate.ddl-auto=none",
                "--spring.flyway.enabled=false",
                "--spring.mail.host=localhost",
                "--spring.mail.port=1025",
                "--spring.task.scheduling.enabled=false",
                "--ai.gemini.api-key=local-dummy-" + tag,
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
    static class FakeExternalProvidersConfig {
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

        private final List<PushCall> recordedCalls = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void sendNotificationToUser(String targetUserId, String title, String message, String linkAction) {
            recordedCalls.add(new PushCall(targetUserId, title, message, linkAction));
        }

        List<PushCall> calls() {
            return List.copyOf(recordedCalls);
        }

        void clear() {
            recordedCalls.clear();
        }
    }
}
