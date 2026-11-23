package com.taskpilot.app.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.app.TaskPilotApplication;
import com.taskpilot.app.migration.MigrationDatabaseConfiguration;
import com.taskpilot.projects.chat.config.StompAuthChannelInterceptor;
import com.taskpilot.users.common.entity.NotificationEntity;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.NotificationType;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.repository.NotificationRepository;
import com.taskpilot.users.common.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class AuthenticatedActorRegressionTest {

    private static final String TEST_JWT_SECRET = "taskpilot-test-only-app-security-jwt-signing-key-2026-32bytes";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final MessageChannel NOOP_CHANNEL = (message, timeout) -> true;

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
    private static NotificationRepository notificationRepository;
    private static StompAuthChannelInterceptor stompInterceptor;

    @BeforeAll
    static void startApp() {
        MigrationDatabaseConfiguration.configure(
                Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        ).load().migrate();

        context = SpringApplication.run(TaskPilotApplication.class, appArgs());
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        baseUrl = "http://127.0.0.1:" + port;
        userRepository = context.getBean(UserRepository.class);
        notificationRepository = context.getBean(NotificationRepository.class);
        stompInterceptor = context.getBean(StompAuthChannelInterceptor.class);
    }

    @AfterAll
    static void stopApp() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void adminDemotionInvalidatesExistingAdminJwtAndEnforcesUpdatedUserRole() throws Exception {
        String superAdminEmail = "super-admin-" + UUID.randomUUID() + "@example.com";
        String targetAdminEmail = "demote-admin-" + UUID.randomUUID() + "@example.com";

        registerUser(superAdminEmail, "Pass!12345", "Super Admin");
        registerUser(targetAdminEmail, "Pass!12345", "Demote Admin");
        promoteToAdmin(superAdminEmail);
        UserEntity targetAdmin = promoteToAdmin(targetAdminEmail);

        String superAdminJwt = login(superAdminEmail, "Pass!12345");
        String oldAdminJwt = login(targetAdminEmail, "Pass!12345");
        assertThat(get("/api/v1/admin/users", oldAdminJwt).statusCode()).isEqualTo(200);

        HttpResponse<String> demoteResp = putJson(
                "/api/v1/admin/users/" + targetAdmin.getId(),
                Map.of("role", "USER", "status", "AVAILABLE", "currentWorkload", 0),
                superAdminJwt
        );
        assertThat(demoteResp.statusCode()).isEqualTo(200);

        // Existing JWT carrying role=ADMIN claim is immediately invalidated by epoch + session revocation
        assertThat(get("/api/v1/admin/users", oldAdminJwt).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/users/me", oldAdminJwt).statusCode()).isEqualTo(401);

        // Fresh login issues a valid USER session; DB role enforcement blocks /admin with 403
        String demotedUserJwt = login(targetAdminEmail, "Pass!12345");
        assertThat(get("/api/v1/users/me", demotedUserJwt).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/admin/users", demotedUserJwt).statusCode()).isEqualTo(403);
    }

    @Test
    void suspensionSelfDeactivationAndAdminResetInvalidateHttpAndStompImmediately() throws Exception {
        String superAdminEmail = "sec-admin-" + UUID.randomUUID() + "@example.com";
        registerUser(superAdminEmail, "Pass!12345", "Security Admin");
        promoteToAdmin(superAdminEmail);
        String adminJwt = login(superAdminEmail, "Pass!12345");

        // 1. Admin suspension
        String suspendedEmail = "suspend-" + UUID.randomUUID() + "@example.com";
        registerUser(suspendedEmail, "Pass!12345", "Suspended User");
        UserEntity suspendedUser = userRepository.findByEmail(suspendedEmail).orElseThrow();
        String suspendedJwt = login(suspendedEmail, "Pass!12345");
        Principal suspendedStompPrincipal = connectStompAndVerifySendAllowed(suspendedJwt);

        HttpResponse<String> suspendResp = putJson(
                "/api/v1/admin/users/" + suspendedUser.getId(),
                Map.of("role", "USER", "status", "DEACTIVATED", "currentWorkload", 0),
                adminJwt
        );
        assertThat(suspendResp.statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/users/me", suspendedJwt).statusCode()).isEqualTo(401);
        assertStompRejected(suspendedJwt, suspendedStompPrincipal);
        HttpResponse<String> suspendedRelogin = postJson(
                "/api/v1/auth/login", Map.of("email", suspendedEmail, "password", "Pass!12345"), null);
        assertThat(suspendedRelogin.statusCode()).isEqualTo(403);

        // 2. Self-deactivation
        String selfEmail = "self-del-" + UUID.randomUUID() + "@example.com";
        registerUser(selfEmail, "Pass!12345", "Self Deactivate");
        String selfJwt = login(selfEmail, "Pass!12345");
        Principal selfStompPrincipal = connectStompAndVerifySendAllowed(selfJwt);

        HttpResponse<String> deleteResp = postJson(
                "/api/v1/users/me/self-deactivate",
                Map.of("confirmation", "DEACTIVATE", "currentPassword", "Pass!12345"),
                selfJwt
        );
        assertThat(deleteResp.statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/users/me", selfJwt).statusCode()).isEqualTo(401);
        assertStompRejected(selfJwt, selfStompPrincipal);

        // 3. Admin password reset
        String resetEmail = "admin-reset-" + UUID.randomUUID() + "@example.com";
        registerUser(resetEmail, "Pass!12345", "Reset User");
        UserEntity resetUser = userRepository.findByEmail(resetEmail).orElseThrow();
        String resetJwt = login(resetEmail, "Pass!12345");
        Principal resetStompPrincipal = connectStompAndVerifySendAllowed(resetJwt);

        HttpResponse<String> resetResp = putJson(
                "/api/v1/admin/users/" + resetUser.getId() + "/reset-password",
                Map.of(),
                adminJwt
        );
        assertThat(resetResp.statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/users/me", resetJwt).statusCode()).isEqualTo(401);
        assertStompRejected(resetJwt, resetStompPrincipal);
    }

    @Test
    void emailChangeAndReusePreserveUidIsolationAcrossProjectsNotificationsAndProfile() throws Exception {
        String sharedEmail = "reuse-" + UUID.randomUUID() + "@example.com";
        String renamedEmail = "renamed-" + UUID.randomUUID() + "@example.com";

        registerUser(sharedEmail, "Pass!12345", "Original Owner");
        UserEntity uid1 = userRepository.findByEmail(sharedEmail).orElseThrow();
        String uid1Jwt = login(sharedEmail, "Pass!12345");

        HttpResponse<String> createProj = postJson(
                "/api/v1/projects",
                Map.of("name", "UID1 Owned Project", "description", "Must survive email change"),
                uid1Jwt
        );
        assertThat(createProj.statusCode()).isEqualTo(201);
        long projectId = MAPPER.readTree(createProj.body()).path("data").path("id").asLong();

        notificationRepository.saveAndFlush(NotificationEntity.builder()
                .userId(uid1.getId())
                .title("UID1 Private Notice")
                .message("Owned strictly by uid1")
                .type(NotificationType.SYSTEM)
                .isRead(false)
                .build());

        // Change uid1's email directly (simulating email reassignment) and let uid2 register with old sharedEmail
        uid1.setEmail(renamedEmail);
        userRepository.saveAndFlush(uid1);

        registerUser(sharedEmail, "Pass!12345", "New Reuser");
        UserEntity uid2 = userRepository.findByEmail(sharedEmail).orElseThrow();
        assertThat(uid2.getId()).isNotEqualTo(uid1.getId());

        String uid1NewJwt = login(renamedEmail, "Pass!12345");
        String uid2Jwt = login(sharedEmail, "Pass!12345");

        // uid1 keeps project, notifications, and updated profile after email change
        HttpResponse<String> uid1Projects = get("/api/v1/projects/my", uid1NewJwt);
        assertThat(uid1Projects.statusCode()).isEqualTo(200);
        JsonNode uid1Content = MAPPER.readTree(uid1Projects.body()).path("data").path("content");
        assertThat(uid1Content.size()).isEqualTo(1);
        assertThat(uid1Content.get(0).path("id").asLong()).isEqualTo(projectId);

        assertThat(get("/api/v1/projects/" + projectId, uid1NewJwt).statusCode()).isEqualTo(200);

        HttpResponse<String> uid1Notices = get("/api/v1/notifications/my", uid1NewJwt);
        assertThat(uid1Notices.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(uid1Notices.body()).path("data").path("content").size()).isEqualTo(1);

        HttpResponse<String> uid1Me = get("/api/v1/users/me", uid1NewJwt);
        assertThat(uid1Me.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(uid1Me.body()).path("data").path("email").asText()).isEqualTo(renamedEmail);

        // uid2 holding uid1's old email sees 0 projects, 403 on project detail, and 0 notifications
        HttpResponse<String> uid2Projects = get("/api/v1/projects/my", uid2Jwt);
        assertThat(uid2Projects.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(uid2Projects.body()).path("data").path("content").size()).isEqualTo(0);

        assertThat(get("/api/v1/projects/" + projectId, uid2Jwt).statusCode()).isEqualTo(403);

        HttpResponse<String> uid2Notices = get("/api/v1/notifications/my", uid2Jwt);
        assertThat(uid2Notices.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(uid2Notices.body()).path("data").path("content").size()).isEqualTo(0);
    }

    @Test
    void forgedLegacyEmailSubjectJwtRejectedOnHttpAndStomp() throws Exception {
        String email = "legacy-cutover-" + UUID.randomUUID() + "@example.com";
        registerUser(email, "Pass!12345", "Legacy Cutover User");

        Instant now = Instant.now();
        String forgedLegacyJwt = Jwts.builder()
                .issuer("taskpilot")
                .audience().add("taskpilot-api").and()
                .subject(email)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(300)))
                .signWith(Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(get("/api/v1/users/me", forgedLegacyJwt).statusCode()).isEqualTo(401);
        assertThatThrownBy(() -> stompInterceptor.preSend(
                stompFrame(StompCommand.CONNECT, "Bearer " + forgedLegacyJwt, null, null), NOOP_CHANNEL))
                .isInstanceOf(AccessDeniedException.class);
    }

    private UserEntity promoteToAdmin(String email) {
        UserEntity user = userRepository.findByEmail(email).orElseThrow();
        user.setRole(UserRole.ADMIN);
        return userRepository.saveAndFlush(user);
    }

    private Principal connectStompAndVerifySendAllowed(String jwt) {
        Message<?> connected = stompInterceptor.preSend(
                stompFrame(StompCommand.CONNECT, "Bearer " + jwt, null, null),
                NOOP_CHANNEL
        );
        Principal principal = StompHeaderAccessor.wrap(connected).getUser();
        assertThat(principal).isNotNull();
        stompInterceptor.preSend(stompFrame(StompCommand.SEND, null, "/app/chat.send", principal), NOOP_CHANNEL);
        return principal;
    }

    private void assertStompRejected(String jwt, Principal existingPrincipal) {
        assertThatThrownBy(() -> stompInterceptor.preSend(
                stompFrame(StompCommand.CONNECT, "Bearer " + jwt, null, null), NOOP_CHANNEL))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> stompInterceptor.preSend(
                stompFrame(StompCommand.SEND, null, "/app/chat.send", existingPrincipal), NOOP_CHANNEL))
                .isInstanceOf(AccessDeniedException.class);
    }

    private static Message<byte[]> stompFrame(
            StompCommand command,
            String authHeader,
            String destination,
            Principal user
    ) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setLeaveMutable(true);
        if (authHeader != null) {
            accessor.addNativeHeader(HttpHeaders.AUTHORIZATION, authHeader);
        }
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (user != null) {
            accessor.setUser(user);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private void registerUser(String email, String password, String fullName) throws Exception {
        HttpResponse<String> reg = postJson("/api/v1/auth/register",
                Map.of("email", email, "password", password, "fullName", fullName), null);
        assertThat(reg.statusCode()).isEqualTo(201);
        UserEntity user = userRepository.findByEmail(email.trim().toLowerCase(java.util.Locale.ROOT)).orElseThrow();
        user.setEmailVerified(true);
        user.setAccountState(com.taskpilot.users.common.enums.AccountLifecycle.ACTIVE);
        user.setPassword(context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class).encode(password));
        userRepository.saveAndFlush(user);
    }

    private String login(String email, String password) throws Exception {
        HttpResponse<String> resp = postJson("/api/v1/auth/login",
                Map.of("email", email, "password", password), null);
        assertThat(resp.statusCode()).isEqualTo(200);
        return MAPPER.readTree(resp.body()).path("data").path("token").asText();
    }

    private HttpResponse<String> postJson(String path, Object body, String bearerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> putJson(String path, Object body, String bearerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path, String bearerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .DELETE();
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String bearerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .GET();
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String[] appArgs() {
        List<String> args = new ArrayList<>();
        args.add("--server.port=0");
        args.add("--spring.profiles.active=dev");
        args.add("--spring.datasource.url=" + postgres.getJdbcUrl());
        args.add("--spring.datasource.username=" + postgres.getUsername());
        args.add("--spring.datasource.password=" + postgres.getPassword());
        args.add("--spring.jpa.hibernate.ddl-auto=none");
        args.add("--ai.gemini.api-key=local-dummy-not-a-secret");
        args.add("--ai.gemini.api-keys=");
        args.add("--ai.groq.enabled=false");
        args.add("--ai.groq.api-key=");
        args.add("--ai.groq.api-keys=");
        args.add("--ai.openrouter.enabled=false");
        args.add("--ai.openrouter.api-key=");
        args.add("--ai.openrouter.api-keys=");
        args.add("--google.drive.client-id=");
        args.add("--google.drive.client-secret=");
        args.add("--google.drive.refresh-token=");
        args.add("--application.security.oauth2.google.client-id=");
        args.add("--application.security.oauth2.google.client-secret=");
        args.add("--application.security.oauth2.github.client-id=");
        args.add("--application.security.oauth2.github.client-secret=");
        args.add("--onesignal.app-id=");
        args.add("--onesignal.api-key=");
        args.add("--livekit.api-key=local-dummy");
        args.add("--livekit.api-secret=local-dummy");
        args.add("--supabase.s3.endpoint=http://127.0.0.1:9");
        args.add("--supabase.s3.access-key=local-dummy");
        args.add("--supabase.s3.secret-key=local-dummy");
        args.add("--application.security.jwt.secret-key=" + TEST_JWT_SECRET);
        args.add("--application.security.jwt.issuer=taskpilot");
        args.add("--application.security.jwt.audience=taskpilot-api");
        args.add("--application.security.jwt.blocklist.provider=memory");
        args.add("--application.security.password-reset.rate-limit.provider=memory");
        args.add("--rag.embedding.poll-interval-ms=86400000");
        args.add("--decorator.datasource.p6spy.enable-logging=false");
        args.add("--logging.level.root=WARN");
        return args.toArray(String[]::new);
    }
}
