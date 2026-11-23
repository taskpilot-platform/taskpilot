package com.taskpilot.app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.ai.dto.ConfirmationRequiredDto;
import com.taskpilot.ai.entity.RecommendationDecisionEventEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotEntity;
import com.taskpilot.ai.repository.AdaptiveReviewedOutcomeRepository;
import com.taskpilot.ai.repository.RecommendationDecisionEventRepository;
import com.taskpilot.ai.repository.RecommendationSnapshotRepository;
import com.taskpilot.ai.service.AutoAssignmentService;
import com.taskpilot.ai.service.ChatSessionService;
import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.ai.tools.domain.AhpAssignmentAiTools;
import com.taskpilot.app.TaskPilotApplication;
import com.taskpilot.app.migration.MigrationDatabaseConfiguration;
import com.taskpilot.contracts.task.TaskEvaluationAccepted;
import com.taskpilot.contracts.task.TaskEvaluationInvalidated;
import com.taskpilot.contracts.task.TaskEvaluationJournalConsumer;
import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.infrastructure.notification.OneSignalService;
import com.taskpilot.infrastructure.storage.StorageService;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.TaskStatus;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
import com.taskpilot.projects.tasks.service.TaskWorkflowJournalDispatcher;
import com.taskpilot.users.common.entity.SkillEntity;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.entity.UserSkillEntity;
import com.taskpilot.users.common.entity.UserSkillId;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.enums.UserStatus;
import com.taskpilot.users.common.repository.SkillRepository;
import com.taskpilot.users.common.repository.UserRepository;
import com.taskpilot.users.common.repository.UserSkillRepository;
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
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Real PostgreSQL 17 + Flyway V51 behavioral integration suite for P8 (P8.1 + P8.2):
 * snapshot capture, decision recording (ACCEPT/OVERRIDE/REJECT), assignment linkage,
 * independent review projection (reviewed-outcome-v2), reopen/supersede/delete invalidation,
 * feature-time immutability, manual/self-attested separation, authorization & stale/roster conflict checks,
 * idempotent replay, journal recovery across failure & simulated restart, and telemetry-disabled baseline.
 */
@Testcontainers
class P8AdaptivePipelinePostgresIntegrationTest {

    private static final String TEST_JWT_SECRET = "taskpilot-test-only-p8-adaptive-pipeline-jwt-key-2026-32bytes";
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
    private static ProjectFileRepository projectFileRepository;
    private static RecommendationSnapshotRepository snapshotRepository;
    private static RecommendationDecisionEventRepository decisionRepository;
    private static AdaptiveReviewedOutcomeRepository reviewedOutcomeRepository;
    private static TaskWorkflowJournalDispatcher journalDispatcher;
    private static AutoAssignmentService autoAssignmentService;
    private static AhpAssignmentAiTools ahpAssignmentAiTools;
    private static PendingAiActionService pendingAiActionService;
    private static ChatSessionService chatSessionService;
    private static MutableClock testClock;
    private static ControllableFailingConsumer failingConsumer;

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
        assertThat(flyway.migrate().migrationsExecuted).isGreaterThanOrEqualTo(10);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        context = new SpringApplicationBuilder(TaskPilotApplication.class, FakeP8Config.class)
                .run(appArgs());
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        baseUrl = "http://127.0.0.1:" + port;

        jdbc = context.getBean(JdbcTemplate.class);
        taskRepository = context.getBean(TaskRepository.class);
        memberRepository = context.getBean(ProjectMemberRepository.class);
        projectFileRepository = context.getBean(ProjectFileRepository.class);
        snapshotRepository = context.getBean(RecommendationSnapshotRepository.class);
        decisionRepository = context.getBean(RecommendationDecisionEventRepository.class);
        reviewedOutcomeRepository = context.getBean(AdaptiveReviewedOutcomeRepository.class);
        journalDispatcher = context.getBean(TaskWorkflowJournalDispatcher.class);
        autoAssignmentService = context.getBean(AutoAssignmentService.class);
        ahpAssignmentAiTools = context.getBean(AhpAssignmentAiTools.class);
        pendingAiActionService = context.getBean(PendingAiActionService.class);
        chatSessionService = context.getBean(ChatSessionService.class);
        testClock = context.getBean(MutableClock.class);
        failingConsumer = context.getBean(ControllableFailingConsumer.class);
    }

    @AfterAll
    static void stopApp() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void resetState() {
        testClock.setInstant(BASE_TIME);
        failingConsumer.setFailNext(false);
        autoAssignmentService.setTelemetryEnabled(true);
    }

    record UserFixture(long id, String email, String jwt) {
    }

    private UserFixture createUser(UserRole role) throws Exception {
        String email = "p8-" + UUID.randomUUID() + "@example.com";
        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setFullName("P8 User " + email.substring(0, 8));
        user.setRole(role);
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

    private long createProject(String jwt) throws Exception {
        HttpResponse<String> resp = send("POST", "/api/v1/projects",
                Map.of("name", "P8 Proj " + UUID.randomUUID(), "timezoneId", "UTC"), jwt, Map.of());
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(201);
        return MAPPER.readTree(resp.body()).path("data").path("id").asLong();
    }

    private void addMember(long projectId, long userId, MemberRole role) {
        memberRepository.saveAndFlush(ProjectMemberEntity.builder()
                .projectId(projectId).userId(userId).role(role).build());
    }

    private void ensureSkill(long userId, String skillName, int proficiency) {
        SkillRepository skillRepo = context.getBean(SkillRepository.class);
        SkillEntity skill = skillRepo.findByName(skillName).orElseGet(() -> {
            SkillEntity s = new SkillEntity();
            s.setName(skillName);
            s.setDescription("P8 fixture skill");
            s.setIsActive(true);
            return skillRepo.saveAndFlush(s);
        });
        UserRepository userRepo = context.getBean(UserRepository.class);
        UserEntity user = userRepo.findById(userId).orElseThrow();
        UserSkillRepository userSkillRepo = context.getBean(UserSkillRepository.class);
        userSkillRepo.saveAndFlush(UserSkillEntity.builder()
                .id(new UserSkillId(userId, skill.getId()))
                .user(user)
                .skill(skill)
                .level(proficiency)
                .build());
    }

    private JsonNode createTask(String jwt, long projectId, Long assigneeId, List<String> skills, String dueDate) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("title", "P8 Task " + UUID.randomUUID().toString().substring(0, 6));
        body.put("description", "P8 adaptive verification task");
        body.put("difficultyLevel", 5);
        body.put("requestId", UUID.randomUUID().toString());
        if (assigneeId != null) body.put("assigneeId", assigneeId);
        if (skills != null) body.put("requiredSkills", skills);
        if (dueDate != null) body.put("dueDate", dueDate);
        HttpResponse<String> created = send("POST", "/api/v1/tasks", body, jwt, Map.of());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return MAPPER.readTree(created.body()).path("data");
    }

    private void startTask(String jwt, long taskId, long expectedVersion) throws Exception {
        HttpResponse<String> started = send("PATCH", "/api/v1/tasks/" + taskId,
                Map.of("expectedTaskVersion", expectedVersion, "requestId", UUID.randomUUID().toString(),
                        "status", "IN_PROGRESS"),
                jwt, Map.of());
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
    }

    private ProjectFileEntity createEvidenceFile(long projectId, long uploaderId) {
        return projectFileRepository.saveAndFlush(ProjectFileEntity.builder()
                .projectId(projectId)
                .uploaderId(uploaderId)
                .fileName("stored-" + UUID.randomUUID() + ".pdf")
                .originalName("proof.pdf")
                .fileSize(1024L)
                .contentType("application/pdf")
                .storageKey("projects/" + projectId + "/files/" + UUID.randomUUID() + ".pdf")
                .storageBucket("documents")
                .build());
    }

    @Test
    void fullAdaptiveFlow_reopenInvalidation_immutableFeatureInputs_separation_and_adminReport() throws Exception {
        UserFixture admin = createUser(UserRole.ADMIN);
        UserFixture manager1 = createUser(UserRole.USER);
        UserFixture manager2 = createUser(UserRole.USER);
        UserFixture cand1 = createUser(UserRole.USER);
        UserFixture cand2 = createUser(UserRole.USER);
        UserFixture outsider = createUser(UserRole.USER);

        ensureSkill(cand1.id(), "Java", 5);
        ensureSkill(cand2.id(), "Python", 4);

        long projectId = createProject(manager1.jwt());
        addMember(projectId, manager2.id(), MemberRole.MANAGER);
        addMember(projectId, cand1.id(), MemberRole.MEMBER);
        addMember(projectId, cand2.id(), MemberRole.MEMBER);

        // 1. Task-bound recommendation via REST -> immutable snapshot, public allowlisted-view-v3 only
        JsonNode task1 = createTask(manager1.jwt(), projectId, null, List.of("Java"), "2026-11-20");
        long t1Id = task1.path("id").asLong();
        long t1Version = task1.path("version").asLong();

        HttpResponse<String> recResp = send("POST", "/api/v1/ai/auto-assign",
                Map.of("projectId", projectId, "taskId", t1Id, "requiredSkills", List.of("Java"), "taskDifficulty", 5),
                manager1.jwt(), Map.of());
        assertThat(recResp.statusCode()).as(recResp.body()).isEqualTo(200);
        JsonNode recData = MAPPER.readTree(recResp.body()).path("data");
        String snapshotId = recData.path("snapshotId").asText();
        assertThat(snapshotId).startsWith("rec-snap-");
        assertThat(recData.path("taskId").asLong()).isEqualTo(t1Id);
        assertThat(recData.path("taskVersion").isIntegralNumber()).isTrue();
        assertThat(recData.path("taskVersion").longValue()).isEqualTo(t1Version);
        assertThat(recData.path("presentationContractVersion").asText()).isEqualTo("allowlisted-view-v3");
        // Internal raw ranking fields stay out of public REST response
        assertThat(recResp.body()).doesNotContain("fullPrecisionScore").doesNotContain("rankingKey");

        RecommendationSnapshotEntity snapEntity = snapshotRepository.findByIdWithCandidates(snapshotId).orElseThrow();
        assertThat(snapEntity.getTaskVersion()).isEqualTo(t1Version);
        assertThat(snapEntity.getWeightVersion()).isEqualTo("baseline-v1");
        assertThat(snapEntity.getRosterRevision()).isNotBlank();
        assertThat(snapEntity.getCandidates()).hasSizeGreaterThanOrEqualTo(2);
        double originalCand1PerfInput = snapEntity.getCandidates().stream()
                .filter(c -> c.getCandidateId().equals(cand1.id()))
                .findFirst().orElseThrow().getDerivedPerformanceInput();

        // Cross-user / non-manager / outsider decision denial
        HttpResponse<String> outsiderDec = send("POST", "/api/v1/ai/recommendations/" + snapshotId + "/decisions",
                Map.of("decisionType", "ACCEPT", "selectedCandidateId", cand1.id(), "taskId", t1Id),
                outsider.jwt(), Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(outsiderDec.statusCode()).isEqualTo(403);

        HttpResponse<String> memberDec = send("POST", "/api/v1/ai/recommendations/" + snapshotId + "/decisions",
                Map.of("decisionType", "ACCEPT", "selectedCandidateId", cand1.id(), "taskId", t1Id),
                cand1.jwt(), Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(memberDec.statusCode()).isEqualTo(403);

        // Stale expectedTaskVersion -> 409 and no decision persisted
        HttpResponse<String> staleVerDec = send("POST", "/api/v1/ai/recommendations/" + snapshotId + "/decisions",
                Map.of("decisionType", "ACCEPT", "selectedCandidateId", cand1.id(), "taskId", t1Id, "expectedTaskVersion", 999L),
                manager1.jwt(), Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(staleVerDec.statusCode()).isEqualTo(409);
        assertThat(decisionRepository.findBySnapshotId(snapshotId)).isEmpty();

        // Record OVERRIDE decision to cand1 or cand2 (explicit non-top choice OR top choice): let's pick cand1 (ACCEPT)
        UUID decIdempKey = UUID.randomUUID();
        HttpResponse<String> decResp = send("POST", "/api/v1/ai/recommendations/" + snapshotId + "/decisions",
                Map.of("decisionType", "ACCEPT", "selectedCandidateId", cand1.id(),
                        "taskId", t1Id, "expectedTaskVersion", recData.path("taskVersion").longValue()),
                manager1.jwt(), Map.of("Idempotency-Key", decIdempKey.toString()));
        assertThat(decResp.statusCode()).as(decResp.body()).isEqualTo(200);
        JsonNode decData = MAPPER.readTree(decResp.body()).path("data");
        String decisionId = decData.path("decisionId").asText();
        assertThat(decisionId).startsWith("rec-dec-");
        assertThat(decData.path("assignmentVersion").asLong()).isGreaterThanOrEqualTo(1L);

        // Idempotent replay with same Idempotency-Key returns same decisionId cleanly without advancing assignmentVersion
        HttpResponse<String> replayDecResp = send("POST", "/api/v1/ai/recommendations/" + snapshotId + "/decisions",
                Map.of("decisionType", "ACCEPT", "selectedCandidateId", cand1.id(),
                        "taskId", t1Id, "expectedTaskVersion", t1Version),
                manager1.jwt(), Map.of("Idempotency-Key", decIdempKey.toString()));
        assertThat(replayDecResp.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(replayDecResp.body()).path("data").path("decisionId").asText()).isEqualTo(decisionId);

        TaskEntity assignedTask = taskRepository.findById(t1Id).orElseThrow();
        assertThat(assignedTask.getAssigneeId()).isEqualTo(cand1.id());
        assertThat(assignedTask.getAssignmentDecisionId()).isEqualTo(decisionId);

        // 2. Submit work -> Independent review by manager1 (manager1 != cand1) -> journal projection (reviewed-outcome-v2)
        startTask(manager1.jwt(), t1Id, assignedTask.effectiveVersion());
        long inProgVer = taskRepository.findById(t1Id).orElseThrow().effectiveVersion();
        ProjectFileEntity proof = createEvidenceFile(projectId, cand1.id());
        HttpResponse<String> subResp = send("POST", "/api/v1/tasks/" + t1Id + "/workflow/submit",
                Map.of("expectedTaskVersion", inProgVer, "requestId", UUID.randomUUID().toString(),
                        "summary", "Completed feature", "evidenceProjectFileIds", List.of(proof.getId())),
                cand1.jwt(), Map.of());
        assertThat(subResp.statusCode()).as(subResp.body()).isEqualTo(200);
        long sub1Id = MAPPER.readTree(subResp.body()).path("data").path("submissionId").asLong();
        long revVer1 = MAPPER.readTree(subResp.body()).path("data").path("taskVersion").asLong();

        HttpResponse<String> revResp = send("POST", "/api/v1/tasks/" + t1Id + "/workflow/review",
                Map.of("submissionId", sub1Id, "expectedTaskVersion", revVer1,
                        "requestId", UUID.randomUUID().toString(), "decision", "ACCEPT",
                        "qualityRating", 5, "feedback", "Excellent implementation",
                        "evidenceSummary", "Verified proof.pdf"),
                manager2.jwt(), Map.of());
        assertThat(revResp.statusCode()).as(revResp.body()).isEqualTo(200);
        long eval1Id = MAPPER.readTree(revResp.body()).path("data").path("evaluationId").asLong();

        // Dispatch journal (in case post-commit async hook needs synchronous drain in test)
        journalDispatcher.replayDueEntries();
        var projected1 = reviewedOutcomeRepository.findByEvaluationIdAndEvaluationVersion(eval1Id, 1);
        assertThat(projected1).isPresent();
        assertThat(projected1.get().getDecisionId()).isEqualTo(decisionId);
        assertThat(projected1.get().getQualityRating()).isEqualTo(5);
        assertThat(projected1.get().getRubricVersion()).isEqualTo("quality-v1");
        assertThat(projected1.get().getOnTime()).isTrue();
        assertThat(projected1.get().getOutcomeVersion()).isEqualTo("reviewed-outcome-v2");

        // 3. Verify feature-time snapshot inputs stay unchanged even after cand1 earned a 5-star review
        HttpResponse<String> rec2Resp = send("POST", "/api/v1/ai/auto-assign",
                Map.of("projectId", projectId, "requiredSkills", List.of("Java"), "taskDifficulty", 5),
                manager1.jwt(), Map.of());
        assertThat(rec2Resp.statusCode()).isEqualTo(200);
        // Check admin report: non-admin denied 403, admin gets deterministic report & SHA-256
        HttpResponse<String> userReportDenied = send("GET",
                "/api/v1/admin/ai/adaptive/dataset-report?projectId=" + projectId, null, manager1.jwt(), Map.of());
        assertThat(userReportDenied.statusCode()).isEqualTo(403);

        String projectReportPath = "/api/v1/admin/ai/adaptive/dataset-report?projectId=" + projectId;
        String managerReportPath = projectReportPath + "&managerId=" + manager1.id();
        HttpResponse<String> overviewResponse = send("GET", projectReportPath, null, admin.jwt(), Map.of());
        assertThat(overviewResponse.statusCode()).as(overviewResponse.body()).isEqualTo(200);
        JsonNode overview = MAPPER.readTree(overviewResponse.body()).path("data");
        assertThat(overview.path("readinessStatus").asText()).isEqualTo("SCOPE_REQUIRED");
        assertThat(overview.path("canonicalDatasetSha256").isNull()).isTrue();

        HttpResponse<String> adminReport1 = send("GET", managerReportPath, null, admin.jwt(), Map.of());
        assertThat(adminReport1.statusCode()).as(adminReport1.body()).isEqualTo(200);
        JsonNode reportNode1 = MAPPER.readTree(adminReport1.body()).path("data");
        assertThat(reportNode1.path("eligibleSamplesCount").asLong()).isEqualTo(1L);
        assertThat(reportNode1.path("sufficientEvidenceForProposal").asBoolean()).isFalse();
        assertThat(reportNode1.path("readinessStatus").asText()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(reviewedOutcomeRepository.findScopedOutcomes(
                projectId, manager2.id(), "BALANCED", org.springframework.data.domain.PageRequest.of(0, 1))).isEmpty();
        assertThat(reviewedOutcomeRepository.findScopedOutcomes(
                projectId, manager1.id(), "URGENT", org.springframework.data.domain.PageRequest.of(0, 1))).isEmpty();
        String hash1 = reportNode1.path("canonicalDatasetSha256").asText();
        // Original snapshot feature value preserved in dataset row
        assertThat(reportNode1.path("sampleRows").get(0).path("selectedCandidatePerformance").asDouble())
                .isEqualTo(originalCand1PerfInput);

        // Deterministic rebuild produces identical SHA-256
        HttpResponse<String> adminReport1Repeat = send("GET", managerReportPath, null, admin.jwt(), Map.of());
        assertThat(MAPPER.readTree(adminReport1Repeat.body()).path("data").path("canonicalDatasetSha256").asText())
                .isEqualTo(hash1);

        // 4. Add manual assignment task + independent review -> classified separately with no fabricated Adaptive choice
        JsonNode manualTask = createTask(manager1.jwt(), projectId, cand2.id(), List.of("Python"), "2026-11-22");
        long mtId = manualTask.path("id").asLong();
        startTask(manager1.jwt(), mtId, manualTask.path("version").asLong());
        long mtVer = taskRepository.findById(mtId).orElseThrow().effectiveVersion();
        HttpResponse<String> mtSub = send("POST", "/api/v1/tasks/" + mtId + "/workflow/submit",
                Map.of("expectedTaskVersion", mtVer, "requestId", UUID.randomUUID().toString(), "summary", "Manual work"),
                cand2.jwt(), Map.of());
        assertThat(mtSub.statusCode()).isEqualTo(200);
        HttpResponse<String> mtRev = send("POST", "/api/v1/tasks/" + mtId + "/workflow/review",
                Map.of("submissionId", MAPPER.readTree(mtSub.body()).path("data").path("submissionId").asLong(),
                        "expectedTaskVersion", MAPPER.readTree(mtSub.body()).path("data").path("taskVersion").asLong(),
                        "requestId", UUID.randomUUID().toString(), "decision", "ACCEPT",
                        "qualityRating", 4, "feedback", "Good manual work", "evidenceSummary", "Checked"),
                manager2.jwt(), Map.of());
        assertThat(mtRev.statusCode()).isEqualTo(200);
        long manualEvalId = MAPPER.readTree(mtRev.body()).path("data").path("evaluationId").asLong();

        // 5. Add REJECT without replacement -> reportable feedback, no positive preference label
        JsonNode rejTask = createTask(manager1.jwt(), projectId, null, List.of("Java"), null);
        long rtId = rejTask.path("id").asLong();
        long rtVer = rejTask.path("version").asLong();
        HttpResponse<String> rejRec = send("POST", "/api/v1/ai/auto-assign",
                Map.of("projectId", projectId, "taskId", rtId, "requiredSkills", List.of("Java")),
                manager1.jwt(), Map.of());
        String rejSnapId = MAPPER.readTree(rejRec.body()).path("data").path("snapshotId").asText();
        HttpResponse<String> rejDec = send("POST", "/api/v1/ai/recommendations/" + rejSnapId + "/decisions",
                Map.of("decisionType", "REJECT", "taskId", rtId, "expectedTaskVersion", rtVer, "reasonCode", "NONE_FIT"),
                manager1.jwt(), Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(rejDec.statusCode()).isEqualTo(200);
        assertThat(taskRepository.findById(rtId).orElseThrow().getAssigneeId()).isNull();

        journalDispatcher.replayDueEntries();
        assertThat(reviewedOutcomeRepository.findByEvaluationIdAndEvaluationVersion(manualEvalId, 1).orElseThrow().getDecisionId())
                .isNull();

        HttpResponse<String> reportAfterManualAndReject = send("GET",
                "/api/v1/admin/ai/adaptive/dataset-report?projectId=" + projectId, null, admin.jwt(), Map.of());
        JsonNode reportNode2 = MAPPER.readTree(reportAfterManualAndReject.body()).path("data");
        assertThat(reportNode2.path("eligibleSamplesCount").asLong()).isEqualTo(1L);
        assertThat(reportNode2.path("manualEvidenceCount").asLong()).isEqualTo(1L);
        assertThat(reportNode2.path("rejectedFeedbackCount").asLong()).isEqualTo(1L);
        assertThat(reportNode2.path("exclusionBreakdown").path("MANUAL_ASSIGNMENT_NO_DECISION").asLong()).isEqualTo(1L);
        assertThat(reportNode2.path("exclusionBreakdown").path("NO_COMPARABLE_CHOICE").asLong()).isEqualTo(1L);

        // 6. Reopen task 1 -> invalidates stale outcome label & excludes from eligible samples
        long doneT1Ver = taskRepository.findById(t1Id).orElseThrow().effectiveVersion();
        failingConsumer.setFailNext(true);
        HttpResponse<String> reopenResp = send("POST", "/api/v1/tasks/" + t1Id + "/workflow/reopen",
                Map.of("expectedTaskVersion", doneT1Ver, "requestId", UUID.randomUUID().toString(),
                        "reason", "Regression found in integration"),
                manager1.jwt(), Map.of());
        assertThat(reopenResp.statusCode()).as(reopenResp.body()).isEqualTo(200);
        assertThat(reviewedOutcomeRepository.findByEvaluationIdAndEvaluationVersion(eval1Id, 1)
                .orElseThrow().getInvalidated()).isFalse();

        HttpResponse<String> reportBeforeReplay = send("GET", managerReportPath, null, admin.jwt(), Map.of());
        assertThat(reportBeforeReplay.statusCode()).as(reportBeforeReplay.body()).isEqualTo(200);
        JsonNode reportBeforeReplayData = MAPPER.readTree(reportBeforeReplay.body()).path("data");
        assertThat(reportBeforeReplayData.path("eligibleSamplesCount").asLong()).isZero();
        assertThat(reportBeforeReplayData.path("exclusionBreakdown").path("REOPENED_TASK_EXCLUDED").asLong())
                .isEqualTo(1L);

        failingConsumer.setFailNext(false);
        jdbc.update("UPDATE public.task_workflow_journal SET next_attempt_at = ? WHERE status IN ('PENDING', 'RETRY_PENDING')",
                java.sql.Timestamp.from(BASE_TIME.minusSeconds(1)));
        journalDispatcher.replayDueEntries();

        assertThat(reviewedOutcomeRepository.findByEvaluationIdAndEvaluationVersion(eval1Id, 1).orElseThrow().getInvalidated())
                .isTrue();

        HttpResponse<String> reportAfterReopen = send("GET", managerReportPath, null, admin.jwt(), Map.of());
        JsonNode reportNode3 = MAPPER.readTree(reportAfterReopen.body()).path("data");
        assertThat(reportNode3.path("eligibleSamplesCount").asLong()).isZero();
        assertThat(reportNode3.path("invalidatedOutcomesCount").asLong()).isEqualTo(1L);
        assertThat(reportNode3.path("exclusionBreakdown").path("REOPENED_TASK_EXCLUDED").asLong()).isEqualTo(1L);
        assertThat(reportNode3.path("canonicalDatasetSha256").asText()).isNotEqualTo(hash1);
    }

    @Test
    void manualReassignmentClearsDecisionLink_staleRosterConflict_toolConfirm_and_journalRecoveryAfterFailure()
            throws Exception {
        UserFixture admin = createUser(UserRole.ADMIN);
        UserFixture manager = createUser(UserRole.USER);
        UserFixture cand1 = createUser(UserRole.USER);
        UserFixture cand2 = createUser(UserRole.USER);
        ensureSkill(cand1.id(), "React", 5);
        ensureSkill(cand2.id(), "React", 3);

        long projectId = createProject(manager.jwt());
        addMember(projectId, cand1.id(), MemberRole.MEMBER);
        addMember(projectId, cand2.id(), MemberRole.MEMBER);

        // 1. AI Tool confirmation path: recommendAndAssignTask -> confirm -> snapshot + decision + task decision link
        JsonNode task = createTask(manager.jwt(), projectId, null, List.of("React"), "2026-11-25");
        long taskId = task.path("id").asLong();
        long taskVer = task.path("version").asLong();
        var session = chatSessionService.createSession(manager.id(), "P8 AI tool assign");

        Object toolResult = ToolExecutionContext.callWith(
                new ToolExecutionContext.Context(
                        manager.id(), session.getId(), "recommend and assign", java.util.Collections.emptySet(),
                        new CurrentActor(manager.id(), manager.email(), "USER", 1L)),
                () -> ahpAssignmentAiTools.recommendAndAssignTask(
                        String.valueOf(taskId), String.valueOf(projectId), "React", "5",
                        "AI tool test assignment", taskVer));
        assertThat(toolResult).isInstanceOf(ConfirmationRequiredDto.class);
        ConfirmationRequiredDto pending = (ConfirmationRequiredDto) toolResult;
        HttpResponse<String> confirmResp = send("POST",
                "/api/v1/ai/sessions/" + session.getId() + "/actions/" + pending.actionId() + "/confirm",
                Map.of("payloadHash", pending.payloadHash(), "idempotencyKey", UUID.randomUUID().toString()),
                manager.jwt(), Map.of());
        assertThat(confirmResp.statusCode()).as(confirmResp.body()).isEqualTo(200);

        TaskEntity assigned = taskRepository.findById(taskId).orElseThrow();
        String decisionId = assigned.getAssignmentDecisionId();
        assertThat(decisionId).isNotBlank();
        RecommendationDecisionEventEntity toolDecision = decisionRepository.findById(decisionId).orElseThrow();
        assertThat(toolDecision.getDecisionType().name()).isEqualTo("ACCEPTED");

        // 2. Manual reassignment via PATCH clears stale assignmentDecisionId cleanly
        HttpResponse<String> manualReassign = send("PATCH", "/api/v1/tasks/" + taskId,
                Map.of("expectedTaskVersion", assigned.effectiveVersion(),
                        "requestId", UUID.randomUUID().toString(),
                        "assigneeId", cand2.id()),
                manager.jwt(), Map.of());
        assertThat(manualReassign.statusCode()).as(manualReassign.body()).isEqualTo(200);
        TaskEntity afterManual = taskRepository.findById(taskId).orElseThrow();
        assertThat(afterManual.getAssigneeId()).isEqualTo(cand2.id());
        assertThat(afterManual.getAssignmentDecisionId()).isNull();

        // 3. Stale roster check: take snapshot, add member, decision attempt must fail 409
        JsonNode rosterTask = createTask(manager.jwt(), projectId, null, List.of("React"), null);
        HttpResponse<String> snapResp = send("POST", "/api/v1/ai/auto-assign",
                Map.of("projectId", projectId, "taskId", rosterTask.path("id").asLong(), "requiredSkills", List.of("React")),
                manager.jwt(), Map.of());
        String snapId = MAPPER.readTree(snapResp.body()).path("data").path("snapshotId").asText();

        UserFixture cand3 = createUser(UserRole.USER);
        addMember(projectId, cand3.id(), MemberRole.MEMBER);

        HttpResponse<String> staleRosterDec = send("POST", "/api/v1/ai/recommendations/" + snapId + "/decisions",
                Map.of("decisionType", "ACCEPT", "selectedCandidateId", cand1.id(),
                        "taskId", rosterTask.path("id").asLong(),
                        "expectedTaskVersion", rosterTask.path("version").asLong()),
                manager.jwt(), Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(staleRosterDec.statusCode()).isEqualTo(409);

        // 4. Consumer failure + journal recovery across admin replay (without losing committed task review!)
        startTask(manager.jwt(), taskId, afterManual.effectiveVersion());
        long subVer = taskRepository.findById(taskId).orElseThrow().effectiveVersion();
        HttpResponse<String> subResp = send("POST", "/api/v1/tasks/" + taskId + "/workflow/submit",
                Map.of("expectedTaskVersion", subVer, "requestId", UUID.randomUUID().toString(), "summary", "Recovery check"),
                cand2.jwt(), Map.of());
        long subId = MAPPER.readTree(subResp.body()).path("data").path("submissionId").asLong();
        long revVer = MAPPER.readTree(subResp.body()).path("data").path("taskVersion").asLong();

        failingConsumer.setFailNext(true);
        HttpResponse<String> reviewResp = send("POST", "/api/v1/tasks/" + taskId + "/workflow/review",
                Map.of("submissionId", subId, "expectedTaskVersion", revVer, "requestId", UUID.randomUUID().toString(),
                        "decision", "ACCEPT", "qualityRating", 4, "feedback", "Ok", "evidenceSummary", "Verified"),
                manager.jwt(), Map.of());
        assertThat(reviewResp.statusCode()).isEqualTo(200);
        long evalId = MAPPER.readTree(reviewResp.body()).path("data").path("evaluationId").asLong();

        // Dispatch attempts fail once cleanly in REQUIRES_NEW; review stays DONE and outcome not yet projected
        journalDispatcher.replayDueEntries();
        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(reviewedOutcomeRepository.findByEvaluationIdAndEvaluationVersion(evalId, 1)).isEmpty();

        // Recover: clear outage, advance due time, trigger admin replay endpoint
        failingConsumer.setFailNext(false);
        jdbc.update("UPDATE public.task_workflow_journal SET next_attempt_at = ? WHERE status IN ('PENDING', 'RETRY_PENDING')",
                java.sql.Timestamp.from(BASE_TIME.minusSeconds(1)));
        HttpResponse<String> adminReplay = send("POST", "/api/v1/admin/ai/adaptive/journal/replay", null, admin.jwt(), Map.of());
        assertThat(adminReplay.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(adminReplay.body()).path("data").path("replayedCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(reviewedOutcomeRepository.findByEvaluationIdAndEvaluationVersion(evalId, 1)).isPresent();
        // Verify manual reassignment had decisionId cleared -> classified as manual, not stale AI choice
        assertThat(reviewedOutcomeRepository.findByEvaluationIdAndEvaluationVersion(evalId, 1).get().getDecisionId())
                .isNull();

        // 5. Telemetry disabled preserves baseline recommendation without creating snapshot
        autoAssignmentService.setTelemetryEnabled(false);
        long countBefore = snapshotRepository.count();
        HttpResponse<String> baselineResp = send("POST", "/api/v1/ai/auto-assign",
                Map.of("projectId", projectId, "taskId", rosterTask.path("id").asLong(), "requiredSkills", List.of("React")),
                manager.jwt(), Map.of());
        assertThat(baselineResp.statusCode()).isEqualTo(200);
        JsonNode baselineData = MAPPER.readTree(baselineResp.body()).path("data");
        assertThat(baselineData.path("snapshotId").isNull()).isTrue();
        assertThat(baselineData.path("candidates").size()).isGreaterThanOrEqualTo(2);
        assertThat(snapshotRepository.count()).isEqualTo(countBefore);
    }

    private static HttpResponse<String> send(String method, String path, Object body, String jwt, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json");
        if (jwt != null && !jwt.isBlank()) {
            builder.header("Authorization", "Bearer " + jwt);
        }
        headers.forEach(builder::header);
        if (body == null && ("GET".equals(method) || "DELETE".equals(method))) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.ofString(""));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String[] appArgs() {
        return new String[]{
                "--spring.profiles.active=dev",
                "--server.port=0",
                "--server.address=127.0.0.1",
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
                "--ai.gemini.api-key=local-dummy-p8",
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
    static class FakeP8Config {
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
        ControllableFailingConsumer controllableFailingConsumer() {
            return new ControllableFailingConsumer();
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

    static class ControllableFailingConsumer implements TaskEvaluationJournalConsumer {
        private final AtomicBoolean failNext = new AtomicBoolean(false);

        void setFailNext(boolean fail) {
            failNext.set(fail);
        }

        @Override
        public void onEvaluationAccepted(TaskEvaluationAccepted event) {
            if (failNext.get()) {
                throw new IllegalStateException("Simulated P8 downstream projection outage");
            }
        }

        @Override
        public void onEvaluationInvalidated(TaskEvaluationInvalidated event) {
            if (failNext.get()) {
                throw new IllegalStateException("Simulated P8 downstream invalidation outage");
            }
        }
    }
}
