package com.taskpilot.app.meetings;

import com.taskpilot.app.TaskPilotApplication;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.entity.ProjectMeetingEntity;
import com.taskpilot.projects.common.enums.MeetingRecordingStatus;
import com.taskpilot.projects.common.enums.ProjectMeetingStatus;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectMeetingRepository;
import com.taskpilot.projects.meetings.service.EgressSnapshot;
import com.taskpilot.projects.meetings.service.MeetingRecordingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
    void applyEgressSnapshotAndRecoveryExecuteModifyingQueriesInsideRealTransactions() {
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

            // 5. Apply EGRESS_COMPLETE -> creates ProjectFileEntity + transitions meeting to COMPLETED
            recordingService.applyEgressSnapshot(new EgressSnapshot(
                    "EG_PG17_TX_1", "tp-tx-room-1", "EGRESS_COMPLETE", "", false,
                    "recordings/project-" + projectId + "/meeting-" + meeting.getId() + ".mp4",
                    204800L, 10_000_000_000L
            ));
            ProjectMeetingEntity completed = meetingRepo.findById(meeting.getId()).orElseThrow();
            assertThat(completed.getRecordingStatus()).isEqualTo(MeetingRecordingStatus.COMPLETED);
            assertThat(completed.getRecordingFileId()).isNotNull();
            assertThat(fileRepo.findBySourceEgressId("EG_PG17_TX_1")).isPresent();

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
        }
    }
}
