package com.taskpilot.app.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.app.TaskPilotApplication;
import com.taskpilot.app.migration.MigrationDatabaseConfiguration;
import com.taskpilot.contracts.aiquery.port.out.ProjectInsightsPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.ProjectInvitationEntity;
import com.taskpilot.projects.common.repository.ProjectInvitationRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ProjectInvitationPostgresIntegrationTest {

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
    private static ProjectInvitationRepository invitationRepository;
    private static ProjectMemberRepository memberRepository;
    private static ProjectInsightsPort projectInsightsPort;

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
        invitationRepository = context.getBean(ProjectInvitationRepository.class);
        memberRepository = context.getBean(ProjectMemberRepository.class);
        projectInsightsPort = context.getBean(ProjectInsightsPort.class);
    }

    @AfterAll
    static void stopApp() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void retiredPrjIdJoinFailsWith410OnHttpAndAiToolPort() throws Exception {
        String ownerEmail = "inv-owner-" + UUID.randomUUID() + "@example.com";
        String attackerEmail = "inv-atk-" + UUID.randomUUID() + "@example.com";
        createVerifiedUser(ownerEmail, "Pass!12345", "Owner");
        UserEntity attacker = createVerifiedUser(attackerEmail, "Pass!12345", "Attacker");

        String ownerJwt = login(ownerEmail, "Pass!12345");
        String atkJwt = login(attackerEmail, "Pass!12345");

        long projectId = MAPPER.readTree(
                postJson("/api/v1/projects", Map.of("name", "Private Project"), ownerJwt).body()
        ).path("data").path("id").asLong();

        HttpResponse<String> joinResp = postJson(
                "/api/v1/projects/join", Map.of("projectCode", "PRJ-" + projectId), atkJwt);
        assertThat(joinResp.statusCode()).isEqualTo(410);

        assertThatThrownBy(() -> projectInsightsPort.joinProject("PRJ-" + projectId, attacker.getId()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getStatus()).isEqualTo(410));

        assertThat(memberRepository.findByProjectIdAndUserId(projectId, attacker.getId())).isEmpty();
    }

    @Test
    void invitationIsHashOnlyEmailBoundSingleUseConcurrentIdempotentAndRevokedOnRemovalOrReissue() throws Exception {
        String mgrEmail = "inv-mgr-" + UUID.randomUUID() + "@example.com";
        String targetEmail = "inv-tgt-" + UUID.randomUUID() + "@example.com";
        String wrongEmail = "inv-wrong-" + UUID.randomUUID() + "@example.com";

        createVerifiedUser(mgrEmail, "Pass!12345", "Manager");
        UserEntity target = createVerifiedUser(targetEmail, "Pass!12345", "Invitee");
        createVerifiedUser(wrongEmail, "Pass!12345", "Wrong User");

        String mgrJwt = login(mgrEmail, "Pass!12345");
        String targetJwt = login(targetEmail, "Pass!12345");
        String wrongJwt = login(wrongEmail, "Pass!12345");

        long projectId = MAPPER.readTree(
                postJson("/api/v1/projects", Map.of("name", "Invited Project"), mgrJwt).body()
        ).path("data").path("id").asLong();

        // Issue invitation 1
        HttpResponse<String> createResp = postJson(
                "/api/v1/projects/" + projectId + "/invitations", Map.of("email", targetEmail), mgrJwt);
        assertThat(createResp.statusCode()).isEqualTo(201);
        JsonNode createdData = MAPPER.readTree(createResp.body()).path("data");
        long inv1Id = createdData.path("id").asLong();
        String inv1Token = createdData.path("inviteToken").asText();
        assertThat(inv1Token).isNotBlank();
        assertThat(createdData.path("inviteUrl").asText()).contains("/invitations/accept?token=" + inv1Token);

        // Redacted listing does NOT leak raw token or hash
        HttpResponse<String> listResp = get("/api/v1/projects/" + projectId + "/invitations", mgrJwt);
        assertThat(listResp.statusCode()).isEqualTo(200);
        assertThat(listResp.body()).doesNotContain(inv1Token);

        // PG stores 64-hex hash only
        try (Connection c = openDb();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT token_hash FROM public.project_invitations WHERE id = ?")) {
            ps.setLong(1, inv1Id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("token_hash")).hasSize(64).doesNotContain(inv1Token);
            }
        }

        // Wrong email rejected 403 without consuming invitation
        assertThat(postJson("/api/v1/projects/invitations/accept", Map.of("token", inv1Token), wrongJwt).statusCode())
                .isEqualTo(403);

        // Reissue revokes inv1Token and returns inv2Token
        HttpResponse<String> reissueResp = postJson(
                "/api/v1/projects/" + projectId + "/invitations", Map.of("email", targetEmail), mgrJwt);
        assertThat(reissueResp.statusCode()).isEqualTo(201);
        String inv2Token = MAPPER.readTree(reissueResp.body()).path("data").path("inviteToken").asText();
        assertThat(postJson("/api/v1/projects/invitations/accept", Map.of("token", inv1Token), targetJwt).statusCode())
                .isEqualTo(410);

        // Concurrent double accept by target creates single membership
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> a1 = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return postJson("/api/v1/projects/invitations/accept", Map.of("token", inv2Token), targetJwt).statusCode();
            });
            Future<Integer> a2 = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return postJson("/api/v1/projects/invitations/accept", Map.of("token", inv2Token), targetJwt).statusCode();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> codes = List.of(a1.get(10, TimeUnit.SECONDS), a2.get(10, TimeUnit.SECONDS));
            assertThat(codes).allMatch(c -> c == 200);
        }
        assertThat(memberRepository.findMembers(projectId)).hasSize(2);

        // Removing member blocks replay of previously accepted invitation!
        assertThat(delete("/api/v1/projects/" + projectId + "/members/" + target.getId(), mgrJwt).statusCode())
                .isEqualTo(200);
        assertThat(postJson("/api/v1/projects/invitations/accept", Map.of("token", inv2Token), targetJwt).statusCode())
                .isEqualTo(409);

        // Expiry check (backdate invitation directly in DB)
        HttpResponse<String> expCreate = postJson(
                "/api/v1/projects/" + projectId + "/invitations", Map.of("email", targetEmail), mgrJwt);
        long inv3Id = MAPPER.readTree(expCreate.body()).path("data").path("id").asLong();
        String inv3Token = MAPPER.readTree(expCreate.body()).path("data").path("inviteToken").asText();
        ProjectInvitationEntity inv3 = invitationRepository.findById(inv3Id).orElseThrow();
        inv3.setExpiresAt(Instant.now().minusSeconds(60));
        invitationRepository.saveAndFlush(inv3);
        assertThat(postJson("/api/v1/projects/invitations/accept", Map.of("token", inv3Token), targetJwt).statusCode())
                .isEqualTo(410);
    }

    @Test
    void unverifiedRevokedArchivedAndLeaveRevokeEdgeCasesEnforced() throws Exception {
        String mgrEmail = "inv-edge-mgr-" + UUID.randomUUID() + "@example.com";
        String unverifiedEmail = "inv-edge-uv-" + UUID.randomUUID() + "@example.com";
        String memberEmail = "inv-edge-mem-" + UUID.randomUUID() + "@example.com";

        createVerifiedUser(mgrEmail, "Pass!12345", "Edge Manager");
        UserEntity unverified = createVerifiedUser(unverifiedEmail, "Pass!12345", "Edge Unverified");
        UserEntity member = createVerifiedUser(memberEmail, "Pass!12345", "Edge Member");

        String mgrJwt = login(mgrEmail, "Pass!12345");
        String uvJwt = login(unverifiedEmail, "Pass!12345");
        String memJwt = login(memberEmail, "Pass!12345");

        unverified.setEmailVerified(false);
        userRepository.saveAndFlush(unverified);

        long projectId = MAPPER.readTree(
                postJson("/api/v1/projects", Map.of("name", "Invitation Edge Project"), mgrJwt).body()
        ).path("data").path("id").asLong();

        // 1. Unverified user cannot accept an invitation (401 at JWT filter; 403 at invitation service boundary)
        HttpResponse<String> uvCreate = postJson(
                "/api/v1/projects/" + projectId + "/invitations", Map.of("email", unverifiedEmail), mgrJwt);
        assertThat(uvCreate.statusCode()).isEqualTo(201);
        long uvInvId = MAPPER.readTree(uvCreate.body()).path("data").path("id").asLong();
        String uvToken = MAPPER.readTree(uvCreate.body()).path("data").path("inviteToken").asText();
        assertThat(postJson("/api/v1/projects/invitations/accept", Map.of("token", uvToken), uvJwt).statusCode())
                .isEqualTo(401);
        var invService = context.getBean(com.taskpilot.projects.projects.service.ProjectInvitationService.class);
        assertThatThrownBy(() -> invService.acceptInvitation(uvToken, unverified.getId()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getStatus()).isEqualTo(403));

        // 2. Explicit DELETE revoke marks invitation revoked and returns 410 on accept
        assertThat(delete("/api/v1/projects/" + projectId + "/invitations/" + uvInvId, mgrJwt).statusCode())
                .isEqualTo(200);
        assertThat(invitationRepository.findById(uvInvId).orElseThrow().getRevokedAt()).isNotNull();
        unverified.setEmailVerified(true);
        userRepository.saveAndFlush(unverified);
        assertThat(postJson("/api/v1/projects/invitations/accept", Map.of("token", uvToken), uvJwt).statusCode())
                .isEqualTo(410);

        // 3. Accept -> leaveProject blocks consumed replay (409) and revokes any pending invite for the leaver (410)
        HttpResponse<String> memCreate = postJson(
                "/api/v1/projects/" + projectId + "/invitations", Map.of("email", memberEmail), mgrJwt);
        assertThat(memCreate.statusCode()).isEqualTo(201);
        String memToken = MAPPER.readTree(memCreate.body()).path("data").path("inviteToken").asText();
        assertThat(postJson("/api/v1/projects/invitations/accept", Map.of("token", memToken), memJwt).statusCode())
                .isEqualTo(200);
        assertThat(delete("/api/v1/projects/" + projectId + "/leave", memJwt).statusCode()).isEqualTo(200);
        assertThat(postJson("/api/v1/projects/invitations/accept", Map.of("token", memToken), memJwt).statusCode())
                .isEqualTo(409);

        HttpResponse<String> preLeaveInv = postJson(
                "/api/v1/projects/" + projectId + "/invitations", Map.of("email", unverifiedEmail), mgrJwt);
        assertThat(preLeaveInv.statusCode()).isEqualTo(201);
        String leaveRevokeToken = MAPPER.readTree(preLeaveInv.body()).path("data").path("inviteToken").asText();
        assertThat(postJson("/api/v1/projects/invitations/accept", Map.of("token", leaveRevokeToken), uvJwt).statusCode())
                .isEqualTo(200);
        String pendingForUvToken = "inv_raw_" + UUID.randomUUID();
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        String pendingForUvHash = java.util.HexFormat.of().formatHex(
                digest.digest(pendingForUvToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        long mgrId = userRepository.findByEmail(mgrEmail.toLowerCase(Locale.ROOT)).orElseThrow().getId();
        var pendingForUvEntity = invitationRepository.saveAndFlush(ProjectInvitationEntity.builder()
                .projectId(projectId)
                .inviteeEmail(unverifiedEmail.toLowerCase(Locale.ROOT))
                .tokenHash(pendingForUvHash)
                .invitedByUserId(mgrId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build());

        assertThat(delete("/api/v1/projects/" + projectId + "/leave", uvJwt).statusCode()).isEqualTo(200);
        assertThat(invitationRepository.findById(pendingForUvEntity.getId()).orElseThrow().getRevokedAt()).isNotNull();
        assertThat(postJson("/api/v1/projects/invitations/accept", Map.of("token", pendingForUvToken), uvJwt).statusCode())
                .isEqualTo(410);

        // 4. Archived project blocks both issuing and accepting invitations (409)
        String pendingArchToken = MAPPER.readTree(
                postJson("/api/v1/projects/" + projectId + "/invitations", Map.of("email", memberEmail), mgrJwt).body()
        ).path("data").path("inviteToken").asText();
        assertThat(postJson("/api/v1/projects/" + projectId + "/archive", Map.of(), mgrJwt).statusCode())
                .isEqualTo(200);
        assertThat(postJson("/api/v1/projects/" + projectId + "/invitations", Map.of("email", unverifiedEmail), mgrJwt)
                .statusCode()).isEqualTo(409);
        assertThat(postJson("/api/v1/projects/invitations/accept", Map.of("token", pendingArchToken), memJwt)
                .statusCode()).isEqualTo(409);
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

    private static Connection openDb() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
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
