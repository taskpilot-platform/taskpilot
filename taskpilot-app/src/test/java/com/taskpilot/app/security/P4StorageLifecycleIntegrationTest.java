package com.taskpilot.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.drive.Drive;
import com.taskpilot.ai.rag.domain.DocumentStatus;
import com.taskpilot.ai.rag.entity.DocumentEntity;
import com.taskpilot.ai.rag.repository.DocumentRepository;
import com.taskpilot.ai.rag.service.ProjectDocumentService;
import com.taskpilot.app.TaskPilotApplication;
import com.taskpilot.app.migration.MigrationDatabaseConfiguration;
import com.taskpilot.contracts.storage.StorageCleanupJobSummary;
import com.taskpilot.contracts.storage.StorageCleanupPort;
import com.taskpilot.contracts.storage.StorageCleanupRequest;
import com.taskpilot.contracts.storage.StorageCleanupStatus;
import com.taskpilot.contracts.storage.StorageObjectLocator;
import com.taskpilot.contracts.storage.StorageProviderKind;
import com.taskpilot.contracts.storage.StorageTargetDomain;
import com.taskpilot.contracts.storage.StorageUploadOperationDto;
import com.taskpilot.contracts.storage.StorageUploadOperationPort;
import com.taskpilot.contracts.storage.StorageUploadOperationStatus;
import com.taskpilot.contracts.storage.StorageUploadReservationCommand;
import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.storage.StorageService;
import com.taskpilot.infrastructure.storage.cleanup.StorageCleanupService;
import com.taskpilot.infrastructure.storage.cleanup.StorageUploadOperationService;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveFileDto;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveService;
import com.taskpilot.infrastructure.storage.range.ByteRange;
import com.taskpilot.infrastructure.storage.range.StorageDeleteOutcome;
import com.taskpilot.infrastructure.storage.range.StorageDeleteResult;
import com.taskpilot.infrastructure.storage.range.StorageObjectStat;
import com.taskpilot.infrastructure.storage.range.StorageReadRequest;
import com.taskpilot.infrastructure.storage.range.StorageReadResult;
import com.taskpilot.projects.files.service.ProjectFileService;
import com.taskpilot.projects.projects.service.ProjectServiceImpl;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.UserRole;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL 17 + Flyway V46 + Spring Boot integration proof for P4.1 storage privacy,
 * single-range HTTP 206/416 downloads, durable upload compensation, Drive crash reconciliation,
 * after-commit delete/project-cascade cleanup, duplicate worker leasing, and archived RAG guards.
 */
@Testcontainers
class P4StorageLifecycleIntegrationTest {

    private static final String TEST_JWT_SECRET = "taskpilot-test-only-p4-storage-jwt-signing-key-2026-32bytes";
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
    private static FakeS3StorageService fakeS3;
    private static FakeGoogleDriveService fakeDrive;
    private static StorageCleanupService cleanupService;
    private static StorageUploadOperationService uploadOpService;
    private static ProjectFileService projectFileService;
    private static ProjectDocumentService projectDocumentService;
    private static ProjectServiceImpl projectService;
    private static DocumentRepository documentRepository;
    private static PlatformTransactionManager txManager;

    @BeforeAll
    static void startApp() {
        Flyway flyway = MigrationDatabaseConfiguration.configure(
                Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        ).load();
        var migrationResult = flyway.migrate();
        assertThat(migrationResult.migrationsExecuted).isGreaterThanOrEqualTo(6);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        context = new SpringApplicationBuilder(TaskPilotApplication.class, FakeProvidersConfig.class)
                .run(appArgs());
        baseUrl = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
        jdbc = context.getBean(JdbcTemplate.class);
        fakeS3 = context.getBean(FakeS3StorageService.class);
        fakeDrive = context.getBean(FakeGoogleDriveService.class);
        cleanupService = context.getBean(StorageCleanupService.class);
        uploadOpService = context.getBean(StorageUploadOperationService.class);
        projectFileService = context.getBean(ProjectFileService.class);
        projectDocumentService = context.getBean(ProjectDocumentService.class);
        projectService = context.getBean(ProjectServiceImpl.class);
        documentRepository = context.getBean(DocumentRepository.class);
        txManager = context.getBean(PlatformTransactionManager.class);
    }

    @AfterAll
    static void stopApp() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void resetFakes() {
        fakeS3.reset();
        fakeDrive.reset();
    }

    record Fixture(long uid, long projectId, String jwt, CurrentActor actor) {
    }

    private Fixture createFixture() throws Exception {
        String email = "p4-storage-" + UUID.randomUUID() + "@example.com";
        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setFullName("P4 Storage User");
        user.setRole(UserRole.USER);
        user.setPassword(context.getBean(PasswordEncoder.class).encode("Pass!12345"));
        user.setEmailVerified(true);
        user = context.getBean(UserRepository.class).saveAndFlush(user);

        var login = postJson("/api/v1/auth/login", Map.of("email", email, "password", "Pass!12345"), null);
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String jwt = MAPPER.readTree(login.body()).path("data").path("token").asText();

        var project = postJson("/api/v1/projects", Map.of("name", "P4 Storage " + UUID.randomUUID()), jwt);
        assertThat(project.statusCode()).as(project.body()).isEqualTo(201);
        long projectId = MAPPER.readTree(project.body()).path("data").path("id").asLong();

        return new Fixture(user.getId(), projectId, jwt, new CurrentActor(user.getId(), email, "USER", 1L));
    }

    @Test
    void googleDriveConfigBeanPreservedAndV46UpgradesCleanlyFromV45() throws Exception {
        assertThat(context.getBean(Drive.class)).isNotNull();

        try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "test", "test");
             var s = c.createStatement()) {
            s.execute("CREATE DATABASE p4_upgrade_probe");
        }
        String upgradeUrl = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/p4_upgrade_probe";
        Flyway toV45 = MigrationDatabaseConfiguration.configure(
                Flyway.configure().dataSource(upgradeUrl, "test", "test")
        ).target("45").load();
        assertThat(toV45.migrate().migrationsExecuted).isEqualTo(5);

        Flyway toLatest = MigrationDatabaseConfiguration.configure(
                Flyway.configure().dataSource(upgradeUrl, "test", "test")
        ).target("46").load();
        assertThat(toLatest.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(toLatest.validateWithResult().validationSuccessful).isTrue();

        // Validate Hibernate mapping of new V46 entities (and core file/document entities) against the upgraded PG17 schema
        var registry = new org.hibernate.boot.registry.StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", upgradeUrl)
                .applySetting("hibernate.connection.username", "test")
                .applySetting("hibernate.connection.password", "test")
                .applySetting("hibernate.connection.driver_class", "org.postgresql.Driver")
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .build();
        try {
            var sources = new org.hibernate.boot.MetadataSources(registry)
                    .addAnnotatedClass(com.taskpilot.infrastructure.storage.cleanup.StorageCleanupJobEntity.class)
                    .addAnnotatedClass(com.taskpilot.infrastructure.storage.cleanup.StorageUploadOperationEntity.class)
                    .addAnnotatedClass(com.taskpilot.projects.common.entity.ProjectFileEntity.class)
                    .addAnnotatedClass(com.taskpilot.ai.rag.entity.DocumentEntity.class);
            try (var sf = sources.buildMetadata().buildSessionFactory()) {
                assertThat(sf).isNotNull();
            }
        } finally {
            org.hibernate.boot.registry.StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    void drivePrimaryAndFallbackUploadNeverExposeLinksAndHttpRangeServesAuthorizedExactBytes() throws Exception {
        Fixture f = createFixture();
        Fixture outsider = createFixture();

        byte[] videoBytes = "0123456789ABCDEFGHIJ".getBytes(StandardCharsets.UTF_8); // 20 bytes
        MockMultipartFile video = new MockMultipartFile("file", "meeting-clip.webm", "video/webm", videoBytes);
        var uploaded = projectFileService.uploadFile(f.projectId(), f.actor(), video, "Client note");

        assertThat(uploaded.storageBucket()).isEqualTo("googledrive");
        assertThat(uploaded.description()).isEqualTo("Client note");
        assertThat(uploaded.description()).doesNotContain("http");
        assertThat(fakeDrive.permissionsCalls.get()).isZero();

        // Authorized 200 full
        HttpResponse<byte[]> full = getBytes("/api/v1/projects/" + f.projectId() + "/files/" + uploaded.id() + "/download", f.jwt(), Map.of());
        assertThat(full.statusCode()).isEqualTo(200);
        assertThat(full.body()).isEqualTo(videoBytes);
        assertThat(full.headers().firstValue("Accept-Ranges")).contains("bytes");
        String etag = full.headers().firstValue("ETag").orElseThrow();

        // Bounded 206
        HttpResponse<byte[]> partial = getBytes("/api/v1/projects/" + f.projectId() + "/files/" + uploaded.id() + "/download", f.jwt(),
                Map.of("Range", "bytes=4-9", "If-Range", etag));
        assertThat(partial.statusCode()).isEqualTo(206);
        assertThat(partial.headers().firstValue("Content-Range")).contains("bytes 4-9/20");
        assertThat(partial.headers().firstValue("Content-Length")).contains("6");
        assertThat(partial.body()).isEqualTo("456789".getBytes(StandardCharsets.UTF_8));
        assertThat(fakeDrive.requestedRanges).contains("bytes=4-9");

        // Mismatched If-Range -> 200 full
        HttpResponse<byte[]> mismatchIfRange = getBytes("/api/v1/projects/" + f.projectId() + "/files/" + uploaded.id() + "/download", f.jwt(),
                Map.of("Range", "bytes=4-9", "If-Range", "\"stale-etag\""));
        assertThat(mismatchIfRange.statusCode()).isEqualTo(200);
        assertThat(mismatchIfRange.body()).isEqualTo(videoBytes);

        // Unsatisfiable 416
        HttpResponse<byte[]> unsatisfiable = getBytes("/api/v1/projects/" + f.projectId() + "/files/" + uploaded.id() + "/download", f.jwt(),
                Map.of("Range", "bytes=200-300"));
        assertThat(unsatisfiable.statusCode()).isEqualTo(416);
        assertThat(unsatisfiable.headers().firstValue("Content-Range")).contains("bytes */20");

        // Multi-range -> ignored -> 200
        HttpResponse<byte[]> multi = getBytes("/api/v1/projects/" + f.projectId() + "/files/" + uploaded.id() + "/download", f.jwt(),
                Map.of("Range", "bytes=0-1, 5-6"));
        assertThat(multi.statusCode()).isEqualTo(200);
        assertThat(multi.body()).isEqualTo(videoBytes);

        // Outsider denied BEFORE provider read
        int driveReadsBefore = fakeDrive.readCalls.get();
        HttpResponse<byte[]> denied = getBytes("/api/v1/projects/" + f.projectId() + "/files/" + uploaded.id() + "/download", outsider.jwt(),
                Map.of("Range", "bytes=0-3"));
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(fakeDrive.readCalls.get()).isEqualTo(driveReadsBefore);
    }

    @Test
    void uploadCompensationAndDriveCrashWindowReconciliationRecoverAcrossRestarts() throws Exception {
        Fixture f = createFixture();

        // 1. Simulate S3 upload succeeds, then metadata attach fails (project archived/deleted before attach TX) -> compensation!
        byte[] s3Bytes = "s3-orphan-candidate".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile docFile = new MockMultipartFile("file", "orphan.pdf", "application/pdf", s3Bytes);
        jdbc.update("UPDATE public.projects SET status = 'ARCHIVED' WHERE id = ?", f.projectId());
        // Re-activate temporarily? No, ProjectFileService checks active before upload; simulate archiving during upload by reserving and throwing during attach:
        jdbc.update("UPDATE public.projects SET status = 'ACTIVE' WHERE id = ?", f.projectId());
        String plannedKey = fakeS3.planObjectKey("projects/" + f.projectId() + "/files", "orphan.pdf");
        StorageUploadOperationDto s3Op = uploadOpService.reserve(new StorageUploadReservationCommand(
                f.uid(), f.projectId(), StorageTargetDomain.PROJECT_FILE, StorageProviderKind.S3,
                "documents", plannedKey, "orphan.pdf", "application/pdf", s3Bytes.length
        ));
        fakeS3.uploadFileAtKey(docFile, "documents", plannedKey);
        uploadOpService.markProviderWritten(s3Op.operationId(), plannedKey);
        assertThat(fakeS3.objects.containsKey("documents:" + plannedKey)).isTrue();

        // Simulate metadata save/commit rollback via TransactionTemplate
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            uploadOpService.registerRollbackCompensation(
                    s3Op.operationId(),
                    StorageObjectLocator.of("documents", plannedKey),
                    "TEST_ROLLBACK",
                    "Simulated metadata commit failure"
            );
            uploadOpService.markAttachedInCurrentTransaction(s3Op.operationId(), 999999L);
            status.setRollbackOnly();
        });

        String opStatus = jdbc.queryForObject(
                "SELECT status FROM public.storage_upload_operations WHERE operation_id = ?",
                String.class, s3Op.operationId());
        assertThat(opStatus).isEqualTo(StorageUploadOperationStatus.COMPENSATION_QUEUED.name());
        assertThat(cleanupService.processPendingBatch(10, "restarted-worker")).isGreaterThanOrEqualTo(1);
        assertThat(fakeS3.objects.containsKey("documents:" + plannedKey)).isFalse();

        // 2. Active non-expired upload reservation must NOT be touched by reconciliation
        String activeKey = "projects/" + f.projectId() + "/files/active-in-flight.webm";
        StorageUploadOperationDto activeOp = uploadOpService.reserve(new StorageUploadReservationCommand(
                f.uid(), f.projectId(), StorageTargetDomain.PROJECT_FILE, StorageProviderKind.S3,
                "documents", activeKey, "active.webm", "video/webm", 100L
        ));
        fakeS3.objects.put("documents:" + activeKey, "active-bytes".getBytes(StandardCharsets.UTF_8));
        uploadOpService.reconcileExpiredOperations(20);
        cleanupService.processPendingBatch(10, "worker-active-check");
        assertThat(fakeS3.objects.containsKey("documents:" + activeKey)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM public.storage_upload_operations WHERE operation_id = ?",
                String.class, activeOp.operationId())).isEqualTo("RESERVED");

        // 3. Drive crash-window reconciliation: Drive created file with appProperties.taskpilotOperationId, JVM crashed before markProviderWritten
        StorageUploadOperationDto crashedDriveOp = uploadOpService.reserve(new StorageUploadReservationCommand(
                f.uid(), f.projectId(), StorageTargetDomain.PROJECT_FILE, StorageProviderKind.GOOGLE_DRIVE,
                "googledrive", null, "crash.webm", "video/webm", 12L
        ));
        MockMultipartFile crashFile = new MockMultipartFile("file", "crash.webm", "video/webm", "crash-bytes!".getBytes());
        GoogleDriveFileDto driveWritten = fakeDrive.uploadFile(crashFile, null, crashedDriveOp.operationId());
        assertThat(fakeDrive.objects.containsKey(driveWritten.getFileId())).isTrue();

        // Expire lease to simulate restart recovery after crash
        jdbc.update("UPDATE public.storage_upload_operations SET lease_until = NOW() - INTERVAL '5 minutes' WHERE operation_id = ?",
                crashedDriveOp.operationId());

        uploadOpService.reconcileExpiredOperations(20);
        String reconciledStatus = jdbc.queryForObject(
                "SELECT status FROM public.storage_upload_operations WHERE operation_id = ?",
                String.class, crashedDriveOp.operationId());
        assertThat(reconciledStatus).isEqualTo("COMPENSATION_QUEUED");

        cleanupService.processPendingBatch(10, "restarted-drive-worker");
        assertThat(fakeDrive.objects.containsKey(driveWritten.getFileId())).isFalse();
    }

    @Test
    void deleteRollbackNeverDeletesProviderAndProjectDeleteInventoriesFilesAndRagAcrossRestart() throws Exception {
        Fixture f = createFixture();

        // Upload a project file (S3) and a Drive video, plus a RAG document (S3)
        var s3File = projectFileService.uploadFile(
                f.projectId(), f.actor(),
                new MockMultipartFile("file", "plan.pdf", "application/pdf", "plan-data".getBytes()),
                "Project plan"
        );
        var driveFile = projectFileService.uploadFile(
                f.projectId(), f.actor(),
                new MockMultipartFile("file", "demo.webm", "video/webm", "video-data".getBytes()),
                "Demo video"
        );
        var ragDoc = projectDocumentService.uploadDocument(
                f.projectId(),
                new MockMultipartFile("file", "rag-spec.md", "text/markdown", "# RAG spec".getBytes()),
                f.uid()
        );
        DocumentEntity ragEntity = documentRepository.findById(ragDoc.id()).orElseThrow();
        String ragKey = ragEntity.getStorageKey();

        assertThat(fakeS3.objects.containsKey("documents:" + s3File.storageKey())).isTrue();
        assertThat(fakeDrive.objects.containsKey(driveFile.storageKey())).isTrue();
        assertThat(fakeS3.objects.containsKey("documents:" + ragKey)).isTrue();

        // Rollback of deleteFile must enqueue 0 jobs and perform 0 provider deletes
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            projectFileService.deleteFile(f.projectId(), s3File.id(), f.uid());
            status.setRollbackOnly();
        });
        assertThat(cleanupService.findJobsByProject(f.projectId())).isEmpty();
        assertThat(fakeS3.deletedKeys).isEmpty();
        assertThat(fakeDrive.deletedKeys).isEmpty();

        // Delete entire project -> inventories project_files (S3 + Drive) AND RAG documents (S3) before cascades
        projectService.deleteProject(f.projectId(), f.uid());

        // Project and cascade children are gone, but cleanup jobs survive!
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.projects WHERE id = ?", Long.class, f.projectId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.project_files WHERE project_id = ?", Long.class, f.projectId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.documents WHERE project_id = ?", Long.class, f.projectId())).isZero();

        List<StorageCleanupJobSummary> jobs = cleanupService.findJobsByProject(f.projectId());
        assertThat(jobs).hasSize(3);
        assertThat(jobs).extracting(StorageCleanupJobSummary::objectKey)
                .containsExactlyInAnyOrder(s3File.storageKey(), driveFile.storageKey(), ragKey);

        // Process committed jobs outside business transaction
        cleanupService.processPendingBatch(10, "project-delete-worker");
        assertThat(fakeS3.objects.containsKey("documents:" + s3File.storageKey())).isFalse();
        assertThat(fakeDrive.objects.containsKey(driveFile.storageKey())).isFalse();
        assertThat(fakeS3.objects.containsKey("documents:" + ragKey)).isFalse();
        assertThat(cleanupService.findJobsByProject(f.projectId()))
                .allMatch(j -> j.status() == StorageCleanupStatus.SUCCEEDED);
    }

    @Test
    void cleanupWorkerHandles404IdempotenceDuplicateClaimsLeaseRecoveryTransientRetryAndDeadLetter() throws Exception {
        Fixture f = createFixture();

        // 1. 404 on missing object -> treated as SUCCEEDED
        cleanupService.enqueueCleanup(new StorageCleanupRequest(
                StorageObjectLocator.of("documents", "missing/404-key.pdf"),
                "PROJECT_FILE", "1", f.projectId(), null));

        // 2. Duplicate enqueue of same active key -> deduplicated to 1 row
        StorageObjectLocator dupLoc = StorageObjectLocator.of("documents", "dup/shared-key.pdf");
        fakeS3.objects.put("documents:dup/shared-key.pdf", "dup".getBytes(StandardCharsets.UTF_8));
        cleanupService.enqueueCleanup(new StorageCleanupRequest(dupLoc, "PROJECT_FILE", "2", f.projectId(), null));
        cleanupService.enqueueCleanup(new StorageCleanupRequest(dupLoc, "PROJECT_FILE", "2", f.projectId(), null));
        long dupCount = cleanupService.findJobsByProject(f.projectId()).stream()
                .filter(j -> "dup/shared-key.pdf".equals(j.objectKey()))
                .count();
        assertThat(dupCount).isEqualTo(1L);

        // 3. Concurrent workers claim disjoint jobs via FOR UPDATE SKIP LOCKED
        for (int i = 0; i < 6; i++) {
            String key = "concurrent/obj-" + i + ".txt";
            fakeS3.objects.put("documents:" + key, ("b" + i).getBytes(StandardCharsets.UTF_8));
            cleanupService.enqueueCleanup(new StorageCleanupRequest(
                    StorageObjectLocator.of("documents", key), "PROJECT_FILE", "c" + i, f.projectId(), null));
        }

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> w1 = () -> cleanupService.processPendingBatch(4, "worker-A");
            Callable<Integer> w2 = () -> cleanupService.processPendingBatch(4, "worker-B");
            List<Future<Integer>> results = pool.invokeAll(List.of(w1, w2));
            int totalClaimed = results.get(0).get() + results.get(1).get();
            assertThat(totalClaimed).isEqualTo(8);
        }
        assertThat(cleanupService.findJobsByProject(f.projectId()))
                .allMatch(j -> j.status() == StorageCleanupStatus.SUCCEEDED);

        // 4. Transient retry -> succeeds on second attempt; Auth failure -> hits DEAD_LETTER -> manual retry succeeds
        String transientKey = "retry/transient.txt";
        fakeS3.objects.put("documents:" + transientKey, "t".getBytes(StandardCharsets.UTF_8));
        fakeS3.transientFailuresRemaining.put("documents:" + transientKey, 1);
        cleanupService.enqueueCleanup(new StorageCleanupRequest(
                StorageObjectLocator.of("documents", transientKey), "PROJECT_FILE", "t1", f.projectId(), null));

        cleanupService.processPendingBatch(10, "worker-transient-1");
        StorageCleanupJobSummary afterFirstTry = cleanupService.findJobsByProject(f.projectId()).stream()
                .filter(j -> transientKey.equals(j.objectKey())).findFirst().orElseThrow();
        assertThat(afterFirstTry.status()).isEqualTo(StorageCleanupStatus.PENDING);
        assertThat(afterFirstTry.attempts()).isEqualTo(1);
        assertThat(afterFirstTry.nextAttemptAt()).isAfter(Instant.now().minusSeconds(1));

        jdbc.update("UPDATE public.storage_cleanup_jobs SET next_attempt_at = NOW() - INTERVAL '1 second' WHERE id = ?",
                afterFirstTry.id());
        cleanupService.processPendingBatch(10, "worker-transient-2");
        StorageCleanupJobSummary afterSecondTry = cleanupService.findJobsByProject(f.projectId()).stream()
                .filter(j -> transientKey.equals(j.objectKey())).findFirst().orElseThrow();
        assertThat(afterSecondTry.status()).isEqualTo(StorageCleanupStatus.SUCCEEDED);

        // Auth failure (403) reaches DEAD_LETTER after maxAttempts=3, then deliberate manual retry recovers
        String authFailKey = "auth-fail/locked.txt";
        fakeS3.objects.put("documents:" + authFailKey, "locked".getBytes(StandardCharsets.UTF_8));
        fakeS3.authFailingKeys.add("documents:" + authFailKey);
        cleanupService.enqueueCleanup(new StorageCleanupRequest(
                StorageObjectLocator.of("documents", authFailKey), "PROJECT_FILE", "af1", f.projectId(), null));

        for (int attempt = 0; attempt < 3; attempt++) {
            jdbc.update("UPDATE public.storage_cleanup_jobs SET next_attempt_at = NOW() - INTERVAL '1 second' WHERE object_key = ?",
                    authFailKey);
            cleanupService.processPendingBatch(10, "worker-auth-" + attempt);
        }

        StorageCleanupJobSummary deadJob = cleanupService.findJobsByProject(f.projectId()).stream()
                .filter(j -> authFailKey.equals(j.objectKey())).findFirst().orElseThrow();
        assertThat(deadJob.status()).isEqualTo(StorageCleanupStatus.DEAD_LETTER);
        assertThat(deadJob.lastErrorCode()).isEqualTo("S3_HTTP_403");

        // Fix permissions & invoke deliberate manual retry
        fakeS3.authFailingKeys.remove("documents:" + authFailKey);
        assertThat(cleanupService.retryDeadLetterJob(deadJob.id())).isTrue();
        cleanupService.processPendingBatch(10, "worker-manual-retry");
        StorageCleanupJobSummary recovered = cleanupService.findJobsByProject(f.projectId()).stream()
                .filter(j -> authFailKey.equals(j.objectKey())).findFirst().orElseThrow();
        assertThat(recovered.status()).isEqualTo(StorageCleanupStatus.SUCCEEDED);

        // 5. Expired IN_PROGRESS lease recovery (simulates crashed worker)
        String leaseKey = "lease/crashed-worker.txt";
        fakeS3.objects.put("documents:" + leaseKey, "lease".getBytes(StandardCharsets.UTF_8));
        cleanupService.enqueueCleanup(new StorageCleanupRequest(
                StorageObjectLocator.of("documents", leaseKey), "PROJECT_FILE", "l1", f.projectId(), null));
        jdbc.update("""
                UPDATE public.storage_cleanup_jobs
                SET status = 'IN_PROGRESS',
                    lease_owner = 'crashed-worker',
                    lease_until = NOW() - INTERVAL '2 minutes'
                WHERE object_key = ?
                """, leaseKey);
        cleanupService.processPendingBatch(10, "recovered-worker");
        assertThat(fakeS3.objects.containsKey("documents:" + leaseKey)).isFalse();
    }

    @Test
    void archivedProjectCannotUploadDeleteOrRetryRag() throws Exception {
        Fixture f = createFixture();
        var doc = projectDocumentService.uploadDocument(
                f.projectId(),
                new MockMultipartFile("file", "guide.md", "text/markdown", "# Guide".getBytes()),
                f.uid()
        );

        projectService.archiveProject(f.projectId(), f.uid());

        MockMultipartFile second = new MockMultipartFile("file", "second.md", "text/markdown", "# Second".getBytes());
        assertThatThrownBy(() -> projectDocumentService.uploadDocument(f.projectId(), second, f.uid()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Project is archived");
        assertThatThrownBy(() -> projectDocumentService.deleteDocument(f.projectId(), doc.id(), f.uid()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Project is archived");
        assertThatThrownBy(() -> projectDocumentService.retryIngestion(f.projectId(), doc.id(), f.uid()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Project is archived");

        // Document remains untouched
        DocumentEntity persisted = documentRepository.findById(doc.id()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(DocumentStatus.QUEUED);
    }

    @Test
    void cleanupWinnerRejectsLateAttachmentInsteadOfDeletingALinkedFile() throws Exception {
        Fixture f=createFixture();String key="review/"+UUID.randomUUID()+".pdf";
        var op=uploadOpService.reserve(new StorageUploadReservationCommand(f.uid(),f.projectId(),StorageTargetDomain.PROJECT_FILE,
            StorageProviderKind.S3,"documents",key,"late.pdf","application/pdf",4));
        fakeS3.objects.put("documents:"+key,new byte[]{1,2,3,4});
        uploadOpService.markProviderWritten(op.operationId(),key);
        uploadOpService.compensateFailedUpload(op.operationId(),StorageObjectLocator.of("documents",key),"EXPIRED","Lease ended");
        var attachment=context.getBean(com.taskpilot.projects.files.service.ProjectFileAttachmentService.class);
        assertThatThrownBy(()->attachment.attachUploadedFile(f.projectId(),f.uid(),"late.pdf",4,"application/pdf",key,"documents",null,op.operationId()))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from project_files where storage_key=?",Long.class,key)).isZero();
        cleanupService.processPendingBatch(10,"review-cleanup");
        assertThat(fakeS3.objects).doesNotContainKey("documents:"+key);
    }

    @Test
    void projectDeleteWaitsForAttachmentAndInventoriesItsCommittedObject() throws Exception {
        Fixture f=createFixture();String key="review/"+UUID.randomUUID()+".pdf";
        var op=uploadOpService.reserve(new StorageUploadReservationCommand(f.uid(),f.projectId(),StorageTargetDomain.PROJECT_FILE,
            StorageProviderKind.S3,"documents",key,"race.pdf","application/pdf",4));
        fakeS3.objects.put("documents:"+key,new byte[]{1,2,3,4});uploadOpService.markProviderWritten(op.operationId(),key);
        var attachment=context.getBean(com.taskpilot.projects.files.service.ProjectFileAttachmentService.class);
        var deleting=new java.util.concurrent.atomic.AtomicReference<Future<?>>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            new TransactionTemplate(txManager).executeWithoutResult(status->{
                jdbc.queryForObject("select id from projects where id=? for update",Long.class,f.projectId());
                deleting.set(pool.submit(()->projectService.deleteProject(f.projectId(),f.uid())));
                boolean waiting=false;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<deadline) {
                    jdbc.execute("select pg_stat_clear_snapshot()");
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid<>pg_backend_pid() and wait_event_type='Lock' and query ilike '%projects%')",Boolean.class));
                    if(waiting)break;
                    java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(20));
                }
                assertThat(waiting).as("Delete is waiting for the storage metadata transaction").isTrue();
                attachment.attachUploadedFile(f.projectId(),f.uid(),"race.pdf",4,"application/pdf",key,"documents",null,op.operationId());
            });
            deleting.get().get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(cleanupService.findJobsByProject(f.projectId())).anyMatch(job->key.equals(job.objectKey()));
    }

    private static HttpResponse<String> postJson(String path, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<byte[]> getBytes(String path, String token, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        headers.forEach(builder::header);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
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
    static class FakeProvidersConfig {
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
    }

    static class FakeS3StorageService implements StorageService {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        final Map<String, Integer> transientFailuresRemaining = new ConcurrentHashMap<>();
        final java.util.Set<String> authFailingKeys = ConcurrentHashMap.newKeySet();
        final List<String> deletedKeys = new CopyOnWriteArrayList<>();

        void reset() {
            objects.clear();
            transientFailuresRemaining.clear();
            authFailingKeys.clear();
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
                        new ByteArrayInputStream(slice),
                        data.length,
                        slice.length,
                        "application/octet-stream",
                        Optional.of(r),
                        Optional.of("\"fake-s3-" + data.length + "\"")
                );
            }
            return new StorageReadResult(
                    new ByteArrayInputStream(data),
                    data.length,
                    data.length,
                    "application/octet-stream",
                    Optional.empty(),
                    Optional.of("\"fake-s3-" + data.length + "\"")
            );
        }

        @Override
        public Optional<StorageObjectStat> statObject(String bucket, String key) {
            byte[] data = objects.get(bucket + ":" + key);
            if (data == null) {
                return Optional.empty();
            }
            return Optional.of(new StorageObjectStat(
                    data.length, "application/octet-stream",
                    Optional.of("\"fake-s3-" + data.length + "\""),
                    Optional.of(Instant.parse("2026-11-16T00:00:00Z"))
            ));
        }

        @Override
        public void deleteFile(String bucket, String key) {
            deleteObject(bucket, key);
        }

        @Override
        public StorageDeleteResult deleteObject(String bucket, String key) {
            String full = bucket + ":" + key;
            if (authFailingKeys.contains(full)) {
                return StorageDeleteResult.authFailure("S3_HTTP_403", "Access Denied");
            }
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
        final Map<String, UUID> appPropertyOpIds = new ConcurrentHashMap<>();
        final List<String> requestedRanges = new CopyOnWriteArrayList<>();
        final List<String> deletedKeys = new CopyOnWriteArrayList<>();
        final AtomicInteger permissionsCalls = new AtomicInteger();
        final AtomicInteger readCalls = new AtomicInteger();

        FakeGoogleDriveService(Drive drive) {
            super(drive, "fake-folder-id");
        }

        void reset() {
            objects.clear();
            appPropertyOpIds.clear();
            requestedRanges.clear();
            deletedKeys.clear();
            permissionsCalls.set(0);
            readCalls.set(0);
        }

        @Override
        public GoogleDriveFileDto uploadFile(MultipartFile multipartFile, String targetFolderId, UUID operationId) throws IOException {
            String id = "fake-drive-" + UUID.randomUUID();
            objects.put(id, multipartFile.getBytes());
            if (operationId != null) {
                appPropertyOpIds.put(id, operationId);
            }
            return GoogleDriveFileDto.builder()
                    .fileId(id)
                    .name(multipartFile.getOriginalFilename())
                    .mimeType(multipartFile.getContentType())
                    .size(multipartFile.getSize())
                    .md5Checksum("md5-" + id)
                    .modifiedTime(Instant.parse("2026-11-16T00:00:00Z"))
                    .build();
        }

        @Override
        public List<GoogleDriveFileDto> findFilesByOperationId(UUID operationId) {
            List<GoogleDriveFileDto> matches = new ArrayList<>();
            appPropertyOpIds.forEach((id, opId) -> {
                if (operationId.equals(opId) && objects.containsKey(id)) {
                    byte[] bytes = objects.get(id);
                    matches.add(GoogleDriveFileDto.builder()
                            .fileId(id)
                            .name("correlated.webm")
                            .size((long) bytes.length)
                            .build());
                }
            });
            return matches;
        }

        @Override
        public InputStream downloadFile(String fileId) throws IOException {
            readCalls.incrementAndGet();
            byte[] data = objects.get(fileId);
            if (data == null) {
                throw new IOException("Drive file not found: " + fileId);
            }
            return new ByteArrayInputStream(data);
        }

        @Override
        public StorageReadResult readObject(String fileId, Optional<ByteRange> range) throws IOException {
            readCalls.incrementAndGet();
            byte[] data = objects.get(fileId);
            if (data == null) {
                throw new IOException("Drive file not found: " + fileId);
            }
            if (range != null && range.isPresent()) {
                ByteRange r = range.get();
                requestedRanges.add(r.toHttpRangeHeader());
                byte[] slice = Arrays.copyOfRange(data, (int) r.startInclusive(), (int) r.endInclusive() + 1);
                return new StorageReadResult(
                        new ByteArrayInputStream(slice),
                        data.length,
                        slice.length,
                        "video/webm",
                        Optional.of(r),
                        Optional.of("\"fake-drive-" + data.length + "\"")
                );
            }
            return new StorageReadResult(
                    new ByteArrayInputStream(data),
                    data.length,
                    data.length,
                    "video/webm",
                    Optional.empty(),
                    Optional.of("\"fake-drive-" + data.length + "\"")
            );
        }

        @Override
        public Optional<StorageObjectStat> statObject(String fileId) {
            byte[] data = objects.get(fileId);
            if (data == null) {
                return Optional.empty();
            }
            return Optional.of(new StorageObjectStat(
                    data.length, "video/webm",
                    Optional.of("\"fake-drive-" + data.length + "\""),
                    Optional.of(Instant.parse("2026-11-16T00:00:00Z"))
            ));
        }

        @Override
        public StorageDeleteResult deleteObject(String fileId) {
            deletedKeys.add(fileId);
            byte[] removed = objects.remove(fileId);
            return removed != null ? StorageDeleteResult.deleted() : StorageDeleteResult.notFound();
        }
    }
}
