package com.taskpilot.app.meetings;

import com.taskpilot.app.TaskPilotApplication;
import com.taskpilot.contracts.storage.StorageProviderKind;
import com.taskpilot.contracts.storage.StorageTargetDomain;
import com.taskpilot.contracts.storage.StorageUploadOperationStatus;
import com.taskpilot.contracts.storage.StorageUploadReservationCommand;
import com.taskpilot.infrastructure.config.security.FileStreamTokenService;
import com.taskpilot.infrastructure.config.security.PresignedUploadTokenService;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.storage.EgressS3StorageService;
import com.taskpilot.infrastructure.storage.cleanup.StorageCleanupService;
import com.taskpilot.infrastructure.storage.cleanup.StorageUploadOperationRepository;
import com.taskpilot.infrastructure.storage.cleanup.StorageUploadOperationService;
import com.taskpilot.infrastructure.storage.range.StorageDeleteResult;
import com.taskpilot.projects.common.entity.MeetingRecordingEntity;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.entity.ProjectMeetingEntity;
import com.taskpilot.projects.common.enums.MeetingRecordingStatus;
import com.taskpilot.projects.common.enums.ProjectMeetingStatus;
import com.taskpilot.projects.common.repository.MeetingRecordingRepository;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectMeetingRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.meetings.dto.CompleteRecordingUploadRequest;
import com.taskpilot.projects.meetings.dto.PresignRecordingUploadRequest;
import com.taskpilot.projects.meetings.dto.PresignRecordingUploadResponse;
import com.taskpilot.projects.meetings.service.EgressReconciliationJob;
import com.taskpilot.projects.meetings.service.EgressSnapshot;
import com.taskpilot.projects.meetings.service.LiveKitEgressClientAdapter;
import com.taskpilot.projects.meetings.service.MeetingRecordingService;
import com.taskpilot.projects.meetings.service.ProjectMeetingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Real PostgreSQL 17 + Spring Data JPA transaction boundary test (no mocked repositories).
 * Guarantees that internal self-invocations inside MeetingRecordingService (applyEgressSnapshot,
 * recoverStartingClaim, markMissingEgressFailed, linkFallbackRecording) execute all @Modifying
 * JPQL queries inside valid transactions without TransactionRequiredException.
 */
@Testcontainers
class MeetingRecordingTransactionIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("egress_tx_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    @Timeout(150)
    void applyEgressSnapshotAndRecoveryExecuteModifyingQueriesInsideRealTransactions() throws Exception {
        String url = postgres.getJdbcUrl();
        String[] args = {
                "--spring.profiles.active=dev",
                "--application.security.jwt.secret-key=taskpilot-egress-integration-test-key-0123456789abcdef0123456789abcdef",
                "--application.security.jwt.issuer=taskpilot",
                "--application.security.jwt.audience=taskpilot-api",
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=test",
                "--spring.datasource.password=test",
                "--spring.flyway.url=" + url,
                "--spring.flyway.user=test",
                "--spring.flyway.password=test",
                // MigrationSchemaIntegrationTest declares a synthetic test @Entity (migration_schema_probe)
                // on the test classpath; Flyway validates real schema migrations cleanly here.
                "--spring.jpa.hibernate.ddl-auto=none",
                "--spring.output.ansi.enabled=never",
                "--ai.gemini.api-key=local-dummy-not-a-secret",
                "--ai.gemini.api-keys=",
                "--ai.groq.enabled=false",
                "--ai.groq.api-key=",
                "--ai.groq.api-keys=",
                "--ai.openrouter.enabled=false",
                "--ai.openrouter.api-key=",
                "--ai.openrouter.api-keys=",
                "--google.drive.client-id=",
                "--google.drive.client-secret=",
                "--google.drive.refresh-token=",
                "--application.security.oauth2.google.client-id=",
                "--application.security.oauth2.google.client-secret=",
                "--application.security.oauth2.github.client-id=",
                "--application.security.oauth2.github.client-secret=",
                "--onesignal.app-id=",
                "--onesignal.api-key=",
                "--livekit.api-key=local-dummy",
                "--livekit.api-secret=local-dummy",
                "--supabase.s3.endpoint=http://127.0.0.1:9",
                "--supabase.s3.access-key=local-dummy",
                "--supabase.s3.secret-key=local-dummy",
                "--application.security.jwt.blocklist.provider=memory",
                "--application.security.password-reset.rate-limit.provider=memory",
                "--rag.embedding.poll-interval-ms=86400000",
                "--decorator.datasource.p6spy.enable-logging=false",
                "--egress.reconciliation.initial-delay-ms=86400000",
                "--egress.archive.gdrive.initial-delay-ms=86400000",
                "--logging.level.root=WARN"
        };

        try (ConfigurableApplicationContext ctx = SpringApplication.run(TaskPilotApplication.class, args)) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            ProjectMeetingRepository meetingRepo = ctx.getBean(ProjectMeetingRepository.class);
            ProjectFileRepository fileRepo = ctx.getBean(ProjectFileRepository.class);
            MeetingRecordingService recordingService = ctx.getBean(MeetingRecordingService.class);

            Long userId = jdbc.queryForObject(
                    "INSERT INTO public.users(email, full_name) VALUES ('host@txtest.local', 'Host User') RETURNING id",
                    Long.class
            );
            Long projectId = jdbc.queryForObject(
                    "INSERT INTO public.projects(name, description) VALUES ('Tx Project', 'desc') RETURNING id",
                    Long.class
            );
            jdbc.update(
                    "INSERT INTO public.project_members(project_id, user_id, role) VALUES (?, ?, 'MANAGER')",
                    projectId, userId
            );

            // 1. Simulate a meeting stuck in STARTING with egress_id = NULL after crash
            ProjectMeetingEntity meeting = meetingRepo.saveAndFlush(ProjectMeetingEntity.builder()
                    .projectId(projectId)
                    .hostId(userId)
                    .title("Real PG17 Egress Tx Test")
                    .roomName("tp-tx-room-1")
                    .status(ProjectMeetingStatus.ACTIVE)
                    .recordingEnabled(true)
                    .recordingStatus(MeetingRecordingStatus.STARTING)
                    .egressId(null)
                    .startedAt(Instant.now())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now().minusSeconds(300))
                    .build());

            // 2. Recover stale STARTING claim -> attaches egress_id + transitions to RECORDING inside real transactions
            EgressSnapshot activeSnap = new EgressSnapshot(
                    "EG_PG17_TX_1", "tp-tx-room-1", "EGRESS_ACTIVE", "", false, "", 0L, 0L
            );
            recordingService.recoverStartingClaim(meeting.getId(), activeSnap);

            ProjectMeetingEntity afterRecover = meetingRepo.findById(meeting.getId()).orElseThrow();
            assertThat(afterRecover.getEgressId()).isEqualTo("EG_PG17_TX_1");
            assertThat(afterRecover.getRecordingStatus()).isEqualTo(MeetingRecordingStatus.RECORDING);

            // 3. Apply EGRESS_ENDING -> PROCESSING (self-invoked modifying JPQL)
            recordingService.applyEgressSnapshot(new EgressSnapshot(
                    "EG_PG17_TX_1", "tp-tx-room-1", "EGRESS_ENDING", "", false, "", 0L, 0L
            ));
            assertThat(meetingRepo.findById(meeting.getId()).orElseThrow().getRecordingStatus())
                    .isEqualTo(MeetingRecordingStatus.PROCESSING);

            // 4. Conditional SQL guard on linkFallbackRecording MUST NOT clobber active/PROCESSING cloud Egress
            ProjectFileEntity dummyFallbackFile = fileRepo.saveAndFlush(ProjectFileEntity.builder()
                    .projectId(projectId)
                    .uploaderId(userId)
                    .fileName("fb.webm")
                    .originalName("fb.webm")
                    .fileSize(1234L)
                    .contentType("video/webm")
                    .storageKey("recordings/" + projectId + "/" + meeting.getId() + "/fb.webm")
                    .storageBucket("b2")
                    .storageProvider("b2")
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());
            // Execute inside a real transaction via TransactionTemplate
            var txTemplate = new org.springframework.transaction.support.TransactionTemplate(
                    ctx.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            Integer clobbered = txTemplate.execute(tx ->
                    meetingRepo.linkFallbackRecording(meeting.getId(), dummyFallbackFile.getId(), Instant.now()));
            assertThat(clobbered).isZero();
            assertThat(meetingRepo.findById(meeting.getId()).orElseThrow().getEgressId()).isEqualTo("EG_PG17_TX_1");

            // 5. Apply EGRESS_COMPLETE -> creates ProjectFileEntity + durable CLOUD_EGRESS multi-clip history + transitions meeting to COMPLETED
            recordingService.applyEgressSnapshot(new EgressSnapshot(
                    "EG_PG17_TX_1", "tp-tx-room-1", "EGRESS_COMPLETE", "", false,
                    "recordings/project-" + projectId + "/meeting-" + meeting.getId() + ".mp4",
                    204800L, 10_000_000_000L
            ));
            // Replay EGRESS_COMPLETE idempotently -> must NOT create duplicate recording history row
            recordingService.applyEgressSnapshot(new EgressSnapshot(
                    "EG_PG17_TX_1", "tp-tx-room-1", "EGRESS_COMPLETE", "", false,
                    "recordings/project-" + projectId + "/meeting-" + meeting.getId() + ".mp4",
                    204800L, 10_000_000_000L
            ));
            ProjectMeetingEntity completed = meetingRepo.findById(meeting.getId()).orElseThrow();
            assertThat(completed.getRecordingStatus()).isEqualTo(MeetingRecordingStatus.COMPLETED);
            assertThat(completed.getRecordingFileId()).isNotNull();
            assertThat(fileRepo.findBySourceEgressId("EG_PG17_TX_1")).isPresent();

            MeetingRecordingRepository recHistoryRepo = ctx.getBean(MeetingRecordingRepository.class);
            List<MeetingRecordingEntity> historyRows = recHistoryRepo.findByMeetingIdAndProjectIdOrderByCreatedAtDesc(meeting.getId(), projectId);
            assertThat(historyRows).hasSize(1);
            assertThat(historyRows.get(0).getOrigin()).isEqualTo(MeetingRecordingEntity.Origin.CLOUD_EGRESS);
            assertThat(historyRows.get(0).getCoverage()).isEqualTo(MeetingRecordingEntity.Coverage.CLOUD_COMPOSITE);
            assertThat(historyRows.get(0).getState()).isEqualTo(MeetingRecordingEntity.State.READY);
            assertThat(historyRows.get(0).getProjectFileId()).isEqualTo(completed.getRecordingFileId());

            // 5b. Real PG17 proof for R5: expired RESERVED B2 upload operation reconciles plannedObjectKey into storage_cleanup_jobs
            StorageUploadOperationService uploadOpService = ctx.getBean(StorageUploadOperationService.class);
            StorageUploadOperationRepository uploadOpRepo = ctx.getBean(StorageUploadOperationRepository.class);
            StorageCleanupService cleanupService = ctx.getBean(StorageCleanupService.class);
            var b2Reservation = uploadOpService.reserve(new StorageUploadReservationCommand(
                    userId, projectId, StorageTargetDomain.PROJECT_FILE, StorageProviderKind.B2,
                    "b2-recordings", "recordings/" + projectId + "/" + meeting.getId() + "/abandoned-pg17.webm",
                    "abandoned-pg17.webm", "video/webm", 0L, java.time.Duration.ofMinutes(95)
            ));
            assertThat(b2Reservation.leaseUntil()).isAfter(Instant.now().plus(java.time.Duration.ofMinutes(85)));
            // Force expiry in PG17 and reconcile -> must enqueue B2 cleanup job for planned key
            jdbc.update("UPDATE public.storage_upload_operations SET lease_until = NOW() - INTERVAL '5 minutes' WHERE operation_id = ?",
                    b2Reservation.operationId());
            int reconciledOps = uploadOpService.reconcileExpiredOperations(10);
            assertThat(reconciledOps).isGreaterThanOrEqualTo(1);
            assertThat(uploadOpRepo.findById(b2Reservation.operationId()).orElseThrow().getStatus())
                    .isEqualTo(StorageUploadOperationStatus.COMPENSATION_QUEUED);
            assertThat(cleanupService.findJobsByProject(projectId))
                    .anySatisfy(job -> {
                        assertThat(job.provider()).isEqualTo(StorageProviderKind.B2);
                        assertThat(job.bucket()).isEqualTo("b2-recordings");
                        assertThat(job.objectKey()).isEqualTo("recordings/" + projectId + "/" + meeting.getId() + "/abandoned-pg17.webm");
                    });

            // 5c. Real PG17 proof for R6: attachStartedEgress on ENDING meeting keeps egressId durably persisted for reconciliation
            ProjectMeetingEntity endingRaceMeeting = meetingRepo.saveAndFlush(ProjectMeetingEntity.builder()
                    .projectId(projectId)
                    .hostId(userId)
                    .title("Ending Race PG17")
                    .roomName("tp-tx-room-race")
                    .status(ProjectMeetingStatus.ENDING)
                    .recordingEnabled(true)
                    .recordingStatus(MeetingRecordingStatus.STARTING)
                    .egressId(null)
                    .startedAt(Instant.now())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());
            Integer attachedEnding = txTemplate.execute(tx -> meetingRepo.attachStartedEgress(
                    endingRaceMeeting.getId(), "EG_ENDING_PG17", MeetingRecordingStatus.RECORDING, Instant.now()));
            assertThat(attachedEnding).isEqualTo(1);
            ProjectMeetingEntity persistedEnding = meetingRepo.findById(endingRaceMeeting.getId()).orElseThrow();
            assertThat(persistedEnding.getStatus()).isEqualTo(ProjectMeetingStatus.ENDING);
            assertThat(persistedEnding.getEgressId()).isEqualTo("EG_ENDING_PG17");
            assertThat(persistedEnding.getRecordingStatus()).isEqualTo(MeetingRecordingStatus.RECORDING);
            assertThat(meetingRepo.findByRecordingStatusInAndEgressIdIsNotNull(
                    List.of(MeetingRecordingStatus.STARTING, MeetingRecordingStatus.RECORDING, MeetingRecordingStatus.PROCESSING)))
                    .extracting(ProjectMeetingEntity::getEgressId)
                    .contains("EG_ENDING_PG17");

            // 6. Test stale STARTING release when LiveKit has no egress
            ProjectMeetingEntity stuckMeeting = meetingRepo.saveAndFlush(ProjectMeetingEntity.builder()
                    .projectId(projectId)
                    .hostId(userId)
                    .title("Stuck Starting Claim")
                    .roomName("tp-tx-room-2")
                    .status(ProjectMeetingStatus.ACTIVE)
                    .recordingEnabled(true)
                    .recordingStatus(MeetingRecordingStatus.STARTING)
                    .egressId(null)
                    .startedAt(Instant.now())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now().minusSeconds(300))
                    .build());
            List<ProjectMeetingEntity> foundStale = meetingRepo
                    .findByRecordingStatusAndEgressIdIsNullAndUpdatedAtBefore(
                            MeetingRecordingStatus.STARTING, Instant.now().minusSeconds(120));
            assertThat(foundStale).extracting(ProjectMeetingEntity::getId).contains(stuckMeeting.getId());

            recordingService.releaseStaleStartingClaim(stuckMeeting.getId(), "Released after restart");
            ProjectMeetingEntity released = meetingRepo.findById(stuckMeeting.getId()).orElseThrow();
            assertThat(released.getRecordingStatus()).isEqualTo(MeetingRecordingStatus.FAILED);
            assertThat(released.getRecordingError()).contains("Released after restart");

            // =========================================================================
            // 7. Real PG17 proofs for Follow-up Findings F1, F2, F3, F4 & R6 Concurrent RPC
            // =========================================================================
            PlatformTransactionManager txManager = ctx.getBean(PlatformTransactionManager.class);
            PresignedUploadTokenService uploadTokenService = ctx.getBean(PresignedUploadTokenService.class);
            ProjectMemberRepository memberRepo = ctx.getBean(ProjectMemberRepository.class);
            ProjectSecurityService securityService = ctx.getBean(ProjectSecurityService.class);
            ProjectMeetingService projectMeetingService = ctx.getBean(ProjectMeetingService.class);
            FileStreamTokenService streamTokenService = ctx.getBean(FileStreamTokenService.class);

            Set<String> fakeB2Objects = ConcurrentHashMap.newKeySet();
            EgressS3StorageService controlledB2 = Mockito.mock(EgressS3StorageService.class);
            when(controlledB2.isConfigured()).thenReturn(true);
            when(controlledB2.getBucket()).thenReturn("b2-pg17-bucket");
            when(controlledB2.getPresignPutTtl()).thenReturn(Duration.ofMinutes(15));
            when(controlledB2.generatePresignedPutUrl(anyString(), anyString())).thenAnswer(inv ->
                    new EgressS3StorageService.PresignedUrlResult(
                            "https://b2.invalid/put/" + inv.getArgument(0),
                            Instant.now().plus(Duration.ofMinutes(15))));
            when(controlledB2.deleteObject(anyString(), anyString())).thenAnswer(inv -> {
                fakeB2Objects.remove((String) inv.getArgument(1));
                return StorageDeleteResult.deleted();
            });
            Mockito.doAnswer(inv -> {
                fakeB2Objects.remove((String) inv.getArgument(0));
                return null;
            }).when(controlledB2).deleteFile(anyString());

            LiveKitEgressClientAdapter controlledEgress = Mockito.mock(LiveKitEgressClientAdapter.class);
            when(controlledEgress.isConfigured()).thenReturn(true);

            MeetingRecordingService controlledService = new MeetingRecordingService(
                    meetingRepo, fileRepo, memberRepo, securityService, projectMeetingService,
                    controlledEgress, controlledB2, streamTokenService, uploadTokenService,
                    txManager, false, 10L * 1024L * 1024L, 60L
            );
            controlledService.setMeetingRecordingRepository(recHistoryRepo);
            controlledService.setStorageUploadOperationPort(uploadOpService);
            cleanupService.setEgressS3StorageService(controlledB2);

            // 7a (F1 PG17): Cloud recording wins claimRecordingStart while fallback complete is verifying B2 ->
            // 409 rolls back ProjectFileEntity AND keeps StorageUploadOperation PROVIDER_WRITTEN (never ATTACHED),
            // so expiry reconciliation still enqueues durable B2 cleanup.
            ProjectMeetingEntity f1Meeting = meetingRepo.saveAndFlush(ProjectMeetingEntity.builder()
                    .projectId(projectId).hostId(userId).title("F1 Atomic PG17")
                    .roomName("tp-pg17-f1").status(ProjectMeetingStatus.ACTIVE)
                    .recordingEnabled(true).recordingStatus(MeetingRecordingStatus.IDLE)
                    .startedAt(Instant.now()).createdAt(Instant.now()).updatedAt(Instant.now()).build());
            PresignRecordingUploadResponse f1Presign = controlledService.presignFallbackUpload(
                    projectId, f1Meeting.getId(), userId,
                    new PresignRecordingUploadRequest("video/webm", "f1.webm", "clip-f1-pg17"));
            var f1Claims = uploadTokenService.verifyToken(f1Presign.uploadToken(), projectId, f1Meeting.getId(), userId).orElseThrow();
            fakeB2Objects.add(f1Claims.objectKey());
            when(controlledB2.headObject(f1Claims.objectKey())).thenAnswer(inv -> {
                // Cloud start races in and claims STARTING right before fallback atomic commit!
                txTemplate.executeWithoutResult(t -> meetingRepo.claimRecordingStart(f1Meeting.getId(), Instant.now()));
                return new EgressS3StorageService.ObjectMetadata(4096L, "video/webm", "etag-f1");
            });

            assertThatThrownBy(() -> controlledService.completeFallbackUpload(
                    projectId, f1Meeting.getId(), userId,
                    new CompleteRecordingUploadRequest(f1Presign.uploadToken(), 10L)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("status").isEqualTo(409);

            assertThat(fileRepo.findByProjectIdAndStorageKey(projectId, f1Claims.objectKey())).isEmpty();
            assertThat(uploadOpRepo.findById(f1Claims.operationId()).orElseThrow().getStatus())
                    .isEqualTo(StorageUploadOperationStatus.PROVIDER_WRITTEN);
            assertThat(recHistoryRepo.findByMeetingIdAndProjectIdOrderByCreatedAtDesc(f1Meeting.getId(), projectId)).isEmpty();
            // After lease expiry, reconciliation cleans up the unattached B2 object.
            jdbc.update("UPDATE public.storage_upload_operations SET lease_until = NOW() - INTERVAL '5 minutes' WHERE operation_id = ?",
                    f1Claims.operationId());
            uploadOpService.reconcileExpiredOperations(10);
            assertThat(uploadOpRepo.findById(f1Claims.operationId()).orElseThrow().getStatus())
                    .isEqualTo(StorageUploadOperationStatus.COMPENSATION_QUEUED);

            // 7b (F2 PG17): Already-COMPLETED meeting missing history is repaired on replay, and starting a 2nd clip preserves clip 1.
            jdbc.update("DELETE FROM public.project_meeting_recordings WHERE meeting_id = ?", meeting.getId());
            assertThat(recHistoryRepo.findByMeetingIdAndProjectIdOrderByCreatedAtDesc(meeting.getId(), projectId)).isEmpty();
            recordingService.applyEgressSnapshot(new EgressSnapshot(
                    "EG_PG17_TX_1", "tp-tx-room-1", "EGRESS_COMPLETE", "", false,
                    "recordings/project-" + projectId + "/meeting-" + meeting.getId() + ".mp4",
                    204800L, 10_000_000_000L
            ));
            assertThat(recHistoryRepo.findByMeetingIdAndProjectIdOrderByCreatedAtDesc(meeting.getId(), projectId)).hasSize(1);
            // Second cloud clip on the same meeting MUST keep clip 1 in history.
            txTemplate.executeWithoutResult(t -> {
                meetingRepo.claimRecordingStart(meeting.getId(), Instant.now());
                meetingRepo.attachStartedEgress(meeting.getId(), "EG_PG17_TX_2", MeetingRecordingStatus.RECORDING, Instant.now());
            });
            recordingService.applyEgressSnapshot(new EgressSnapshot(
                    "EG_PG17_TX_2", "tp-tx-room-1", "EGRESS_COMPLETE", "", false,
                    "recordings/project-" + projectId + "/meeting-" + meeting.getId() + "-clip2.mp4",
                    102400L, 8_000_000_000L
            ));
            assertThat(recHistoryRepo.findByMeetingIdAndProjectIdOrderByCreatedAtDesc(meeting.getId(), projectId)).hasSize(2);

            // 7c (F3 PG17): Rejected oversized upload schedules deferred post-PUT cleanup -> early worker does NOT mark SUCCEEDED ->
            // still-valid PUT rewrites object -> after PUT window expires, worker deletes rewritten object and marks SUCCEEDED.
            ProjectMeetingEntity f3Meeting = meetingRepo.saveAndFlush(ProjectMeetingEntity.builder()
                    .projectId(projectId).hostId(userId).title("F3 Deferred Cleanup PG17")
                    .roomName("tp-pg17-f3").status(ProjectMeetingStatus.ACTIVE)
                    .recordingEnabled(true).recordingStatus(MeetingRecordingStatus.IDLE)
                    .startedAt(Instant.now()).createdAt(Instant.now()).updatedAt(Instant.now()).build());
            PresignRecordingUploadResponse f3Presign = controlledService.presignFallbackUpload(
                    projectId, f3Meeting.getId(), userId,
                    new PresignRecordingUploadRequest("video/webm", "f3.webm", "clip-f3-pg17"));
            var f3Claims = uploadTokenService.verifyToken(f3Presign.uploadToken(), projectId, f3Meeting.getId(), userId).orElseThrow();
            fakeB2Objects.add(f3Claims.objectKey());
            when(controlledB2.headObject(f3Claims.objectKey()))
                    .thenReturn(new EgressS3StorageService.ObjectMetadata(25L * 1024L * 1024L, "video/webm", "etag-big"));

            assertThatThrownBy(() -> controlledService.completeFallbackUpload(
                    projectId, f3Meeting.getId(), userId,
                    new CompleteRecordingUploadRequest(f3Presign.uploadToken(), 10L)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("status").isEqualTo(413);

            // Immediate best-effort delete cleared the oversized bytes, but client re-PUTs using the still-valid URL!
            assertThat(fakeB2Objects).doesNotContain(f3Claims.objectKey());
            fakeB2Objects.add(f3Claims.objectKey());
            // Worker running BEFORE PUT window closes MUST NOT claim or mark the deferred job SUCCEEDED.
            cleanupService.processPendingBatch(20, "worker-during-put-window");
            String statusDuringWindow = jdbc.queryForObject(
                    "SELECT status FROM public.storage_cleanup_jobs WHERE object_key = ?",
                    String.class, f3Claims.objectKey());
            assertThat(statusDuringWindow).isEqualTo("PENDING");
            assertThat(fakeB2Objects).contains(f3Claims.objectKey());

            // Simulate PUT window expiring -> worker claims deferred job, deletes rewritten object, marks SUCCEEDED.
            jdbc.update("UPDATE public.storage_cleanup_jobs SET next_attempt_at = NOW() - INTERVAL '5 seconds' WHERE object_key = ?",
                    f3Claims.objectKey());
            cleanupService.processPendingBatch(20, "worker-after-put-window");
            String statusAfterWindow = jdbc.queryForObject(
                    "SELECT status FROM public.storage_cleanup_jobs WHERE object_key = ?",
                    String.class, f3Claims.objectKey());
            assertThat(statusAfterWindow).isEqualTo("SUCCEEDED");
            assertThat(fakeB2Objects).doesNotContain(f3Claims.objectKey());

            // 7d (F4-A PG17 TRUE CONCURRENCY): Two concurrent threads both pass the precheck before either commits,
            // racing into commitFallbackUploadAtomically on real PostgreSQL 17 with the same clientRecordingId ->
            // exactly 1 ProjectFileEntity + 1 MeetingRecordingEntity commit, latest points to winner, and loser rolls back
            // and is compensated durably into storage_cleanup_jobs.
            ProjectMeetingEntity f4Meeting = meetingRepo.saveAndFlush(ProjectMeetingEntity.builder()
                    .projectId(projectId).hostId(userId).title("F4 Concurrent & A->B->Retry A PG17")
                    .roomName("tp-pg17-f4").status(ProjectMeetingStatus.ACTIVE)
                    .recordingEnabled(true).recordingStatus(MeetingRecordingStatus.IDLE)
                    .startedAt(Instant.now()).createdAt(Instant.now()).updatedAt(Instant.now()).build());
            PresignRecordingUploadResponse f4Presign1 = controlledService.presignFallbackUpload(
                    projectId, f4Meeting.getId(), userId,
                    new PresignRecordingUploadRequest("video/webm", "f4-a.webm", "clip-f4-A"));
            PresignRecordingUploadResponse f4Presign2 = controlledService.presignFallbackUpload(
                    projectId, f4Meeting.getId(), userId,
                    new PresignRecordingUploadRequest("video/webm", "f4-a.webm", "clip-f4-A"));
            var f4Claims1 = uploadTokenService.verifyToken(f4Presign1.uploadToken(), projectId, f4Meeting.getId(), userId).orElseThrow();
            var f4Claims2 = uploadTokenService.verifyToken(f4Presign2.uploadToken(), projectId, f4Meeting.getId(), userId).orElseThrow();
            fakeB2Objects.add(f4Claims1.objectKey());
            fakeB2Objects.add(f4Claims2.objectKey());

            // Barrier inside headObject guarantees BOTH threads pass the precheck before EITHER enters commitFallbackUploadAtomically!
            CountDownLatch bothPassedPrecheck = new CountDownLatch(2);
            when(controlledB2.headObject(f4Claims1.objectKey())).thenAnswer(inv -> {
                bothPassedPrecheck.countDown();
                assertThat(bothPassedPrecheck.await(10, TimeUnit.SECONDS)).isTrue();
                return new EgressS3StorageService.ObjectMetadata(2048L, "video/webm", "etag-1");
            });
            when(controlledB2.headObject(f4Claims2.objectKey())).thenAnswer(inv -> {
                bothPassedPrecheck.countDown();
                assertThat(bothPassedPrecheck.await(10, TimeUnit.SECONDS)).isTrue();
                return new EgressS3StorageService.ObjectMetadata(2048L, "video/webm", "etag-2");
            });

            var f4Pool = Executors.newFixedThreadPool(2);
            Long clipAFileId;
            try {
                var fut1 = f4Pool.submit(() -> controlledService.completeFallbackUpload(
                        projectId, f4Meeting.getId(), userId,
                        new CompleteRecordingUploadRequest(f4Presign1.uploadToken(), 15L)));
                var fut2 = f4Pool.submit(() -> controlledService.completeFallbackUpload(
                        projectId, f4Meeting.getId(), userId,
                        new CompleteRecordingUploadRequest(f4Presign2.uploadToken(), 15L)));
                var r1 = fut1.get(20, TimeUnit.SECONDS);
                var r2 = fut2.get(20, TimeUnit.SECONDS);

                assertThat(r1.completedRecordingFileId()).isNotNull();
                assertThat(r2.completedRecordingFileId()).isEqualTo(r1.completedRecordingFileId());
                clipAFileId = r1.completedRecordingFileId();
            } finally {
                f4Pool.shutdownNow();
            }

            // Exactly 1 file committed between the 2 concurrent keys, 1 history row, 1 ATTACHED op, 1 COMPENSATION_QUEUED op!
            boolean key1Committed = fileRepo.findByProjectIdAndStorageKey(projectId, f4Claims1.objectKey()).isPresent();
            boolean key2Committed = fileRepo.findByProjectIdAndStorageKey(projectId, f4Claims2.objectKey()).isPresent();
            assertThat(key1Committed ^ key2Committed).isTrue();
            var winnerClaims = key1Committed ? f4Claims1 : f4Claims2;
            var loserClaims = key1Committed ? f4Claims2 : f4Claims1;

            assertThat(meetingRepo.findById(f4Meeting.getId()).orElseThrow().getRecordingFileId()).isEqualTo(clipAFileId);
            assertThat(recHistoryRepo.findByMeetingIdAndProjectIdOrderByCreatedAtDesc(f4Meeting.getId(), projectId)).hasSize(1);
            assertThat(uploadOpRepo.findById(winnerClaims.operationId()).orElseThrow().getStatus())
                    .isEqualTo(StorageUploadOperationStatus.ATTACHED);
            assertThat(uploadOpRepo.findById(loserClaims.operationId()).orElseThrow().getStatus())
                    .isEqualTo(StorageUploadOperationStatus.COMPENSATION_QUEUED);
            assertThat(cleanupService.findJobsByProject(projectId))
                    .anySatisfy(job -> assertThat(job.objectKey()).isEqualTo(loserClaims.objectKey()));

            // 7d-ii (F4-B PG17 A -> B -> Retry A): Complete a newer clip B on the same meeting, then replay clip A via
            // (1) original winner token, (2) presign alreadyCompleted, and (3) duplicate logical-ID token ->
            // ALL return clip A's fileId (NOT B's), while meeting latest pointer stays on clip B!
            PresignRecordingUploadResponse f4PresignB = controlledService.presignFallbackUpload(
                    projectId, f4Meeting.getId(), userId,
                    new PresignRecordingUploadRequest("video/webm", "f4-b.webm", "clip-f4-B"));
            var f4ClaimsB = uploadTokenService.verifyToken(f4PresignB.uploadToken(), projectId, f4Meeting.getId(), userId).orElseThrow();
            fakeB2Objects.add(f4ClaimsB.objectKey());
            when(controlledB2.headObject(f4ClaimsB.objectKey()))
                    .thenReturn(new EgressS3StorageService.ObjectMetadata(3072L, "video/webm", "etag-b"));
            var f4DoneB = controlledService.completeFallbackUpload(
                    projectId, f4Meeting.getId(), userId,
                    new CompleteRecordingUploadRequest(f4PresignB.uploadToken(), 20L));
            Long clipBFileId = f4DoneB.completedRecordingFileId();
            assertThat(clipBFileId).isNotNull().isNotEqualTo(clipAFileId);
            assertThat(meetingRepo.findById(f4Meeting.getId()).orElseThrow().getRecordingFileId()).isEqualTo(clipBFileId);

            // (1) Replay clip A using its original committed token -> receipt = A, latest stays B!
            String winnerToken = key1Committed ? f4Presign1.uploadToken() : f4Presign2.uploadToken();
            var replayAByToken = controlledService.completeFallbackUpload(
                    projectId, f4Meeting.getId(), userId,
                    new CompleteRecordingUploadRequest(winnerToken, 15L));
            assertThat(replayAByToken.completedRecordingFileId()).isEqualTo(clipAFileId);
            assertThat(replayAByToken.recordingFileId()).isEqualTo(clipBFileId);
            assertThat(meetingRepo.findById(f4Meeting.getId()).orElseThrow().getRecordingFileId()).isEqualTo(clipBFileId);

            // (2) Presign clip A again -> alreadyCompleted=true with existingFileId = A!
            PresignRecordingUploadResponse presignARetry = controlledService.presignFallbackUpload(
                    projectId, f4Meeting.getId(), userId,
                    new PresignRecordingUploadRequest("video/webm", "f4-a.webm", "clip-f4-A"));
            assertThat(presignARetry.alreadyCompleted()).isTrue();
            assertThat(presignARetry.existingFileId()).isEqualTo(clipAFileId);

            // (3) Replay clip A using loser's duplicate logical-ID token -> receipt = A, latest stays B!
            String loserToken = key1Committed ? f4Presign2.uploadToken() : f4Presign1.uploadToken();
            var replayAByLogicalId = controlledService.completeFallbackUpload(
                    projectId, f4Meeting.getId(), userId,
                    new CompleteRecordingUploadRequest(loserToken, 15L));
            assertThat(replayAByLogicalId.completedRecordingFileId()).isEqualTo(clipAFileId);
            assertThat(replayAByLogicalId.recordingFileId()).isEqualTo(clipBFileId);
            assertThat(recHistoryRepo.findByMeetingIdAndProjectIdOrderByCreatedAtDesc(f4Meeting.getId(), projectId)).hasSize(2);

            // 7d-iii (F4-P1 PG17 SAME-TOKEN CONCURRENCY): Two concurrent threads call completeFallbackUpload
            // with the EXACT SAME uploadToken/key/operationId after both pass precheck and markProviderWritten ->
            // 1 file, 1 history row, operation stays ATTACHED, BOTH return winner receipt, ZERO deleteFile/cleanup jobs
            // for winner key, and winner B2 object survives cleanup worker!
            ProjectMeetingEntity sameTokenMeeting = meetingRepo.saveAndFlush(ProjectMeetingEntity.builder()
                    .projectId(projectId).hostId(userId).title("F4 Same-Token Concurrent PG17")
                    .roomName("tp-pg17-f4-same-tok").status(ProjectMeetingStatus.ACTIVE)
                    .recordingEnabled(true).recordingStatus(MeetingRecordingStatus.IDLE)
                    .startedAt(Instant.now()).createdAt(Instant.now()).updatedAt(Instant.now()).build());
            PresignRecordingUploadResponse sameTokPresign = controlledService.presignFallbackUpload(
                    projectId, sameTokenMeeting.getId(), userId,
                    new PresignRecordingUploadRequest("video/webm", "same-tok.webm", "clip-same-tok-pg17"));
            var sameTokClaims = uploadTokenService
                    .verifyToken(sameTokPresign.uploadToken(), projectId, sameTokenMeeting.getId(), userId).orElseThrow();
            fakeB2Objects.add(sameTokClaims.objectKey());

            // Pre-mark PROVIDER_WRITTEN (allowed idempotently for same key) and barrier inside headObject so BOTH threads
            // cross precheck and enter markProviderWritten + commitFallbackUploadAtomically simultaneously!
            CountDownLatch bothSameTokEntered = new CountDownLatch(2);
            when(controlledB2.headObject(sameTokClaims.objectKey())).thenAnswer(inv -> {
                bothSameTokEntered.countDown();
                assertThat(bothSameTokEntered.await(10, TimeUnit.SECONDS)).isTrue();
                return new EgressS3StorageService.ObjectMetadata(4096L, "video/webm", "etag-same-tok");
            });

            var sameTokPool = Executors.newFixedThreadPool(2);
            Long sameTokWinnerFileId;
            try {
                var st1 = sameTokPool.submit(() -> controlledService.completeFallbackUpload(
                        projectId, sameTokenMeeting.getId(), userId,
                        new CompleteRecordingUploadRequest(sameTokPresign.uploadToken(), 18L)));
                var st2 = sameTokPool.submit(() -> controlledService.completeFallbackUpload(
                        projectId, sameTokenMeeting.getId(), userId,
                        new CompleteRecordingUploadRequest(sameTokPresign.uploadToken(), 18L)));
                var res1 = st1.get(20, TimeUnit.SECONDS);
                var res2 = st2.get(20, TimeUnit.SECONDS);

                assertThat(res1.completedRecordingFileId()).isNotNull();
                assertThat(res2.completedRecordingFileId()).isEqualTo(res1.completedRecordingFileId());
                sameTokWinnerFileId = res1.completedRecordingFileId();
            } finally {
                sameTokPool.shutdownNow();
            }

            assertThat(fileRepo.findByProjectIdAndStorageKey(projectId, sameTokClaims.objectKey()))
                    .isPresent()
                    .get().extracting(ProjectFileEntity::getId).isEqualTo(sameTokWinnerFileId);
            assertThat(recHistoryRepo.findByMeetingIdAndProjectIdOrderByCreatedAtDesc(sameTokenMeeting.getId(), projectId))
                    .hasSize(1);
            assertThat(uploadOpRepo.findById(sameTokClaims.operationId()).orElseThrow().getStatus())
                    .isEqualTo(StorageUploadOperationStatus.ATTACHED);
            assertThat(cleanupService.findJobsByProject(projectId))
                    .noneMatch(job -> sameTokClaims.objectKey().equals(job.objectKey()));
            // Winning B2 object MUST NOT have been deleted and MUST survive cleanup worker!
            cleanupService.processPendingBatch(20, "worker-after-same-token");
            assertThat(fakeB2Objects).contains(sameTokClaims.objectKey());

            // 7e (R6 PG17 Real Concurrent Blocked-RPC Race): Thread 1 claims STARTING in PG17 and blocks inside
            // startRoomCompositeEgress; Thread 2 transitions the real PG17 meeting to ENDING; RPC returns success ->
            // Thread 1 persists egressId, attempts stop (1st call fails transiently), throws 409; then EgressReconciliationJob
            // stops the persisted egress against PG17 on recovery.
            ProjectMeetingEntity r6Meeting = meetingRepo.saveAndFlush(ProjectMeetingEntity.builder()
                    .projectId(projectId).hostId(userId).title("R6 Real Concurrent RPC PG17")
                    .roomName("tp-pg17-r6-race").status(ProjectMeetingStatus.ACTIVE)
                    .recordingEnabled(true).recordingStatus(MeetingRecordingStatus.IDLE)
                    .startedAt(Instant.now()).createdAt(Instant.now()).updatedAt(Instant.now()).build());

            CountDownLatch rpcEntered = new CountDownLatch(1);
            CountDownLatch releaseRpc = new CountDownLatch(1);
            AtomicInteger stopCalls = new AtomicInteger(0);
            when(controlledEgress.startRoomCompositeEgress(eq("tp-pg17-r6-race"), anyString(), eq(controlledB2)))
                    .thenAnswer(inv -> {
                        rpcEntered.countDown();
                        assertThat(releaseRpc.await(10, TimeUnit.SECONDS)).isTrue();
                        return new EgressSnapshot("EG_R6_CONCURRENT_PG17", "tp-pg17-r6-race", "EGRESS_ACTIVE", "", false, "", 0L, 0L);
                    });
            when(controlledEgress.stopEgress("EG_R6_CONCURRENT_PG17")).thenAnswer(inv -> {
                if (stopCalls.incrementAndGet() == 1) {
                    throw new IOException("Transient LiveKit 503 during initial stop");
                }
                return new EgressSnapshot("EG_R6_CONCURRENT_PG17", "tp-pg17-r6-race", "EGRESS_ENDING", "", false, "", 0L, 0L);
            });
            when(controlledEgress.listEgress(eq(null), eq("EG_R6_CONCURRENT_PG17"))).thenReturn(List.of(
                    new EgressSnapshot("EG_R6_CONCURRENT_PG17", "tp-pg17-r6-race", "EGRESS_ACTIVE", "", false, "", 0L, 0L)
            ));

            var pool = Executors.newSingleThreadExecutor();
            try {
                Future<Throwable> startOutcome = pool.submit(() -> {
                    try {
                        controlledService.startRecording(projectId, r6Meeting.getId(), userId);
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                });

                assertThat(rpcEntered.await(10, TimeUnit.SECONDS)).isTrue();
                // Verify real PG17 row is STARTING while Thread 1 is blocked inside LiveKit RPC (no DB lock held across RPC!).
                assertThat(meetingRepo.findById(r6Meeting.getId()).orElseThrow().getRecordingStatus())
                        .isEqualTo(MeetingRecordingStatus.STARTING);
                // Thread 2 transitions the real PG17 meeting to ENDING while start RPC is in flight.
                txTemplate.executeWithoutResult(t -> {
                    ProjectMeetingEntity locked = meetingRepo.findForUpdate(r6Meeting.getId(), projectId).orElseThrow();
                    locked.setStatus(ProjectMeetingStatus.ENDING);
                    locked.setUpdatedAt(Instant.now());
                    meetingRepo.saveAndFlush(locked);
                });
                releaseRpc.countDown();

                Throwable thrown = startOutcome.get(15, TimeUnit.SECONDS);
                assertThat(thrown).isInstanceOf(BusinessException.class);
                assertThat(((BusinessException) thrown).getStatus()).isEqualTo(409);

                // Egress ID MUST remain persisted in PG17 despite initial stop failure.
                ProjectMeetingEntity afterRace = meetingRepo.findById(r6Meeting.getId()).orElseThrow();
                assertThat(afterRace.getStatus()).isEqualTo(ProjectMeetingStatus.ENDING);
                assertThat(afterRace.getEgressId()).isEqualTo("EG_R6_CONCURRENT_PG17");
                assertThat(stopCalls.get()).isEqualTo(1);

                // Reconciliation recovers after transient stop failure and calls stopEgress a 2nd time!
                EgressReconciliationJob reconJob = new EgressReconciliationJob(
                        meetingRepo, controlledEgress, controlledService);
                reconJob.reconcileInFlightRecordings();
                assertThat(stopCalls.get()).isEqualTo(2);
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
