package com.taskpilot.projects.meetings.service;

import com.taskpilot.infrastructure.config.security.FileStreamTokenService;
import com.taskpilot.infrastructure.config.security.PresignedUploadTokenService;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.storage.EgressS3StorageService;
import com.taskpilot.projects.common.entity.MeetingRecordingEntity;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.entity.ProjectMeetingEntity;
import com.taskpilot.projects.common.enums.FileArchiveStatus;
import com.taskpilot.projects.common.enums.MeetingRecordingStatus;
import com.taskpilot.projects.common.enums.ProjectMeetingStatus;
import com.taskpilot.projects.common.repository.MeetingRecordingRepository;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectMeetingRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.meetings.dto.CompleteRecordingUploadRequest;
import com.taskpilot.projects.meetings.dto.MeetingRecordingPlaybackResponse;
import com.taskpilot.projects.meetings.dto.PresignRecordingUploadRequest;
import com.taskpilot.projects.meetings.dto.PresignRecordingUploadResponse;
import com.taskpilot.projects.meetings.dto.ProjectMeetingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingRecordingServiceTest {

        @Mock
        private ProjectMeetingRepository meetingRepository;
        @Mock
        private ProjectFileRepository projectFileRepository;
        @Mock
        private ProjectMemberRepository projectMemberRepository;
        @Mock
        private ProjectSecurityService projectSecurityService;
        @Mock
        private ProjectMeetingService projectMeetingService;
        @Mock
        private LiveKitEgressClientAdapter egressClientAdapter;
        @Mock
        private EgressS3StorageService egressS3StorageService;
        @Mock
        private FileStreamTokenService fileStreamTokenService;
        @Mock
        private MeetingRecordingRepository meetingRecordingRepository;
        @Mock
        private PlatformTransactionManager transactionManager;

        private PresignedUploadTokenService presignedUploadTokenService;

        private MeetingRecordingService service;

        @BeforeEach
        void setUp() {
                lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
                lenient().when(egressS3StorageService.getBucket()).thenReturn("taskpilot-recordings-2026");
                lenient().when(egressS3StorageService.getPresignPutTtl()).thenReturn(Duration.ofMinutes(20));

                presignedUploadTokenService = new PresignedUploadTokenService(
                                "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970");

                service = new MeetingRecordingService(
                                meetingRepository,
                                projectFileRepository,
                                projectMemberRepository,
                                projectSecurityService,
                                projectMeetingService,
                                egressClientAdapter,
                                egressS3StorageService,
                                fileStreamTokenService,
                                presignedUploadTokenService,
                                transactionManager,
                                true,
                                10L * 1024L * 1024L // 10 MB cap in test to assert oversize rejection & delete
                );
                service.setMeetingRecordingRepository(meetingRecordingRepository);
        }

        @Test
        void verifiedRoomEventAlsoReconcilesAttendanceThroughTheCloudWebhookEndpoint() {
                when(egressClientAdapter.receiveWebhook("room-body", "signed-header")).thenReturn(
                                new LiveKitEgressClientAdapter.ParsedWebhookEvent("room_finished", "tp-proj-10-room",
                                                null));

                service.handleWebhook("room-body", "signed-header");

                verify(projectMeetingService).reconcileVerifiedRoomEvent("tp-proj-10-room");
        }

        @Test
        void startRecording_rejectsMeetingWhoseProviderTerminationIsPending() throws IOException {
                ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                                .id(5L).projectId(10L).hostId(100L).title("Ending")
                                .roomName("ending-room").status(ProjectMeetingStatus.ENDING).build();
                when(meetingRepository.findByIdAndProjectId(5L, 10L)).thenReturn(Optional.of(meeting));

                assertThatThrownBy(() -> service.startRecording(10L, 5L, 100L))
                                .isInstanceOf(BusinessException.class);
                verify(meetingRepository, never()).claimRecordingStart(any(), any());
                verify(egressClientAdapter, never()).startRoomCompositeEgress(any(), any(), any());
        }

        @Test
        void startRecording_claimsAtomicallyStartsLiveKitEgressAndAttachesEgressId() throws IOException {
                ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                                .id(5L).projectId(10L).hostId(100L).title("Standup")
                                .roomName("tp-proj-10-m-abc").status(ProjectMeetingStatus.ACTIVE)
                                .recordingStatus(MeetingRecordingStatus.IDLE).build();

                when(meetingRepository.findByIdAndProjectId(5L, 10L)).thenReturn(Optional.of(meeting));
                when(egressClientAdapter.isConfigured()).thenReturn(true);
                when(egressS3StorageService.isConfigured()).thenReturn(true);
                when(meetingRepository.claimRecordingStart(eq(5L), any())).thenReturn(1);
                when(egressClientAdapter.startRoomCompositeEgress(eq("tp-proj-10-m-abc"), any(),
                                eq(egressS3StorageService)))
                                .thenReturn(new EgressSnapshot("EG_001", "tp-proj-10-m-abc", "EGRESS_ACTIVE", "", false,
                                                "", 0L, 0L));
                when(meetingRepository.attachStartedEgress(eq(5L), eq("EG_001"), eq(MeetingRecordingStatus.RECORDING),
                                any()))
                                .thenReturn(1);
                when(projectMeetingService.getMeetingById(10L, 5L, 100L))
                                .thenReturn(ProjectMeetingResponse.builder().id(5L).egressId("EG_001")
                                                .recordingStatus(MeetingRecordingStatus.RECORDING).build());

                ProjectMeetingResponse res = service.startRecording(10L, 5L, 100L);

                assertThat(res.egressId()).isEqualTo("EG_001");
                verify(meetingRepository).claimRecordingStart(eq(5L), any());
                verify(meetingRepository).attachStartedEgress(eq(5L), eq("EG_001"),
                                eq(MeetingRecordingStatus.RECORDING), any());
        }

        @Test
        void startRecording_concurrentClaimLost_throws409WithoutCallingLiveKit() throws IOException {
                ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                                .id(5L).projectId(10L).hostId(100L).title("Standup")
                                .roomName("tp-proj-10-m-abc").status(ProjectMeetingStatus.ACTIVE)
                                .recordingStatus(MeetingRecordingStatus.IDLE).build();

                when(meetingRepository.findByIdAndProjectId(5L, 10L)).thenReturn(Optional.of(meeting));
                when(egressClientAdapter.isConfigured()).thenReturn(true);
                when(egressS3StorageService.isConfigured()).thenReturn(true);
                when(meetingRepository.claimRecordingStart(eq(5L), any())).thenReturn(0);

                assertThatThrownBy(() -> service.startRecording(10L, 5L, 100L))
                                .isInstanceOf(BusinessException.class)
                                .extracting("status")
                                .isEqualTo(409);
                verify(egressClientAdapter, never()).startRoomCompositeEgress(any(), any(), any());
        }

        @Test
        void applyEgressSnapshot_complete_createsB2FileAndUsesConditionalUpdate() {
                ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                                .id(5L).projectId(10L).hostId(100L).title("Sprint Demo")
                                .egressId("EG_777").recordingStatus(MeetingRecordingStatus.PROCESSING).build();

                when(meetingRepository.findByEgressId("EG_777")).thenReturn(Optional.of(meeting));
                when(projectFileRepository.findBySourceEgressId("EG_777")).thenReturn(Optional.empty());
                when(projectFileRepository.saveAndFlush(any(ProjectFileEntity.class))).thenAnswer(inv -> {
                        ProjectFileEntity e = inv.getArgument(0);
                        e.setId(55L);
                        return e;
                });
                when(meetingRepository.transitionRecordingCompleted(
                                eq(5L), eq("EG_777"), eq(MeetingRecordingStatus.COMPLETED), eq(55L), eq(false), any(),
                                anyCollection()))
                                .thenReturn(1);

                service.applyEgressSnapshot(new EgressSnapshot(
                                "EG_777", "room-1", "EGRESS_COMPLETE", "", false,
                                "recordings/project-10/meeting-5.mp4", 42_000_000L, 60_000_000_000L));

                ArgumentCaptor<ProjectFileEntity> captor = ArgumentCaptor.forClass(ProjectFileEntity.class);
                verify(projectFileRepository).saveAndFlush(captor.capture());
                ProjectFileEntity saved = captor.getValue();
                assertThat(saved.getStorageProvider()).isEqualTo("b2");
                assertThat(saved.getSourceEgressId()).isEqualTo("EG_777");
                assertThat(saved.getArchiveStatus()).isEqualTo(FileArchiveStatus.ARCHIVE_PENDING);
                assertThat(saved.getFileSize()).isEqualTo(42_000_000L);
                verify(meetingRepository).transitionRecordingCompleted(
                                eq(5L), eq("EG_777"), eq(MeetingRecordingStatus.COMPLETED), eq(55L), eq(false), any(),
                                anyCollection());
        }

        @Test
        void applyEgressSnapshot_concurrentDuplicateInsert_reusesWinnerFile() {
                ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                                .id(5L).projectId(10L).hostId(100L).title("Sprint Demo")
                                .egressId("EG_RACE").recordingStatus(MeetingRecordingStatus.PROCESSING).build();
                ProjectFileEntity winner = ProjectFileEntity.builder()
                                .id(88L).projectId(10L).sourceEgressId("EG_RACE").storageProvider("b2").build();

                when(meetingRepository.findByEgressId("EG_RACE")).thenReturn(Optional.of(meeting));
                when(projectFileRepository.findBySourceEgressId("EG_RACE"))
                                .thenReturn(Optional.empty())
                                .thenReturn(Optional.of(winner));
                when(projectFileRepository.saveAndFlush(any(ProjectFileEntity.class)))
                                .thenThrow(new DataIntegrityViolationException(
                                                "duplicate key uq_project_files_source_egress_id"));

                service.applyEgressSnapshot(new EgressSnapshot(
                                "EG_RACE", "room-1", "EGRESS_COMPLETE", "", false,
                                "recordings/project-10/meeting-5.mp4", 12_000L, 10_000L));

                verify(meetingRepository).transitionRecordingCompleted(
                                eq(5L), eq("EG_RACE"), eq(MeetingRecordingStatus.COMPLETED), eq(88L), eq(false), any(),
                                anyCollection());
        }

        @Test
        void applyEgressSnapshot_failedWithBackupStorageUsed_recordsBackupFlag() {
                ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                                .id(5L).projectId(10L).hostId(100L).title("Sprint Demo")
                                .egressId("EG_FAIL").recordingStatus(MeetingRecordingStatus.PROCESSING).build();
                when(meetingRepository.findByEgressId("EG_FAIL")).thenReturn(Optional.of(meeting));

                service.applyEgressSnapshot(new EgressSnapshot(
                                "EG_FAIL", "room-1", "EGRESS_FAILED", "S3 403 Forbidden", true, "", 0L, 0L));

                verify(meetingRepository).transitionRecordingStatus(
                                eq(5L),
                                eq("EG_FAIL"),
                                eq(MeetingRecordingStatus.FAILED),
                                org.mockito.ArgumentMatchers.contains("LiveKit Cloud backup storage"),
                                eq(true),
                                any(),
                                anyCollection());
        }

        @Test
        void getPlaybackUrl_dispatchesB2PresignedAndGdriveRangeProxy() {
                ProjectMeetingEntity b2Meeting = ProjectMeetingEntity.builder()
                                .id(5L).projectId(10L).recordingFileId(55L)
                                .recordingStatus(MeetingRecordingStatus.COMPLETED).build();
                ProjectFileEntity b2File = ProjectFileEntity.builder()
                                .id(55L).projectId(10L).originalName("meeting.mp4").contentType("video/mp4")
                                .fileSize(1000L).storageProvider("b2").storageKey("recordings/project-10/meeting.mp4")
                                .build();

                when(meetingRepository.findByIdAndProjectId(5L, 10L)).thenReturn(Optional.of(b2Meeting));
                when(projectFileRepository.findByIdAndProjectId(55L, 10L)).thenReturn(Optional.of(b2File));
                when(egressS3StorageService.isConfigured()).thenReturn(true);
                Instant exp = Instant.now().plusSeconds(3600);
                when(fileStreamTokenService.issueToken(100L, 10L, 55L))
                                .thenReturn(new FileStreamTokenService.IssuedStreamToken("signed-b2-stream-jwt", exp));

                MeetingRecordingPlaybackResponse b2Res = service.getPlaybackUrl(10L, 5L, 100L);
                assertThat(b2Res.provider()).isEqualTo("b2");
                assertThat(b2Res.url()).isEqualTo("/api/v1/projects/10/files/55/stream?token=signed-b2-stream-jwt");
                assertThat(b2Res.requiresAuthHeader()).isFalse();
                assertThat(b2Res.expiresAt()).isEqualTo(exp);
                verify(egressS3StorageService, never()).generatePresignedGetUrl(any());
                verify(egressS3StorageService, never()).generatePresignedGetUrl(any(), any());

                ProjectMeetingEntity gdMeeting = ProjectMeetingEntity.builder()
                                .id(6L).projectId(10L).recordingFileId(66L)
                                .recordingStatus(MeetingRecordingStatus.COMPLETED).build();
                ProjectFileEntity gdFile = ProjectFileEntity.builder()
                                .id(66L).projectId(10L).originalName("archived.mp4").contentType("video/mp4")
                                .fileSize(2000L).storageProvider("gdrive").storageBucket("googledrive")
                                .storageKey("drive-file-id").build();

                when(meetingRepository.findByIdAndProjectId(6L, 10L)).thenReturn(Optional.of(gdMeeting));
                when(projectFileRepository.findByIdAndProjectId(66L, 10L)).thenReturn(Optional.of(gdFile));
                when(fileStreamTokenService.issueToken(100L, 10L, 66L))
                                .thenReturn(new FileStreamTokenService.IssuedStreamToken("signed-stream-jwt", exp));

                MeetingRecordingPlaybackResponse gdRes = service.getPlaybackUrl(10L, 6L, 100L);
                assertThat(gdRes.provider()).isEqualTo("gdrive");
                assertThat(gdRes.url()).isEqualTo("/api/v1/projects/10/files/66/stream?token=signed-stream-jwt");
        }

        @Test
        void getPlaybackUrl_b2UnavailableFailsClosedWithoutDirectPresignedUrl() {
                ProjectMeetingEntity b2Meeting = ProjectMeetingEntity.builder()
                                .id(5L).projectId(10L).recordingFileId(55L)
                                .recordingStatus(MeetingRecordingStatus.COMPLETED).build();
                ProjectFileEntity b2File = ProjectFileEntity.builder()
                                .id(55L).projectId(10L).originalName("meeting.mp4").contentType("video/mp4")
                                .fileSize(1000L).storageProvider("b2").storageKey("recordings/project-10/meeting.mp4")
                                .build();

                when(meetingRepository.findByIdAndProjectId(5L, 10L)).thenReturn(Optional.of(b2Meeting));
                when(projectFileRepository.findByIdAndProjectId(55L, 10L)).thenReturn(Optional.of(b2File));
                when(egressS3StorageService.isConfigured()).thenReturn(false);

                assertThatThrownBy(() -> service.getPlaybackUrl(10L, 5L, 100L))
                                .isInstanceOf(BusinessException.class)
                                .extracting("status")
                                .isEqualTo(503);
                verify(egressS3StorageService, never()).generatePresignedGetUrl(any());
                verify(fileStreamTokenService, never()).issueToken(any(Long.class), any(), any());
        }

        @Test
        void presignAndCompleteFallbackUpload_enforcesServerKeyAndDeletesOversizeObject() throws IOException {
                ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                                .id(5L).projectId(10L).hostId(100L).title("Fallback Demo")
                                .status(ProjectMeetingStatus.ACTIVE).recordingStatus(MeetingRecordingStatus.IDLE)
                                .build();
                when(meetingRepository.findByIdAndProjectId(5L, 10L)).thenReturn(Optional.of(meeting));
                when(egressS3StorageService.isConfigured()).thenReturn(true);
                Instant exp = Instant.now().plusSeconds(1200);
                when(egressS3StorageService.generatePresignedPutUrl(any(), eq("video/webm")))
                                .thenReturn(new EgressS3StorageService.PresignedUrlResult("https://b2.example/put-url",
                                                exp));

                PresignRecordingUploadResponse presigned = service.presignFallbackUpload(
                                10L, 5L, 100L,
                                new PresignRecordingUploadRequest("video/webm;codecs=vp8,opus", "demo.webm"));

                assertThat(presigned.uploadUrl()).isEqualTo("https://b2.example/put-url");
                assertThat(presigned.contentType()).isEqualTo("video/webm");
                assertThat(presigned.uploadToken()).isNotBlank();

                // 1. Oversize file (>10MB test limit) must be deleted from B2 and return 413
                when(egressS3StorageService.headObject(any()))
                                .thenReturn(new EgressS3StorageService.ObjectMetadata(25L * 1024L * 1024L, "video/webm",
                                                "etag1"));

                assertThatThrownBy(() -> service.completeFallbackUpload(
                                10L, 5L, 100L, new CompleteRecordingUploadRequest(presigned.uploadToken(), 60L)))
                                .isInstanceOf(BusinessException.class)
                                .extracting("status")
                                .isEqualTo(413);
                verify(egressS3StorageService).deleteFile(org.mockito.ArgumentMatchers.startsWith("recordings/10/5/"));

                // 2. Valid file size completes, clears egressId via linkFallbackRecording, and
                // is idempotent on replay
                when(projectFileRepository.findByProjectIdAndStorageKey(eq(10L), any()))
                                .thenReturn(Optional.empty());
                when(egressS3StorageService.headObject(any()))
                                .thenReturn(new EgressS3StorageService.ObjectMetadata(5L * 1024L * 1024L, "video/webm",
                                                "etag2"));
                ProjectFileEntity createdFile = ProjectFileEntity.builder().id(701L).projectId(10L)
                                .storageProvider("b2").build();
                when(projectFileRepository.saveAndFlush(any(ProjectFileEntity.class))).thenReturn(createdFile);
                when(meetingRepository.linkFallbackRecording(eq(5L), eq(701L), any())).thenReturn(1);
                when(projectMeetingService.getMeetingById(10L, 5L, 100L))
                                .thenReturn(ProjectMeetingResponse.builder().id(5L).recordingFileId(701L)
                                                .recordingStatus(MeetingRecordingStatus.COMPLETED).build());

                ProjectMeetingResponse completed = service.completeFallbackUpload(
                                10L, 5L, 100L, new CompleteRecordingUploadRequest(presigned.uploadToken(), 60L));

                assertThat(completed.recordingFileId()).isEqualTo(701L);
                verify(meetingRepository).linkFallbackRecording(eq(5L), eq(701L), any());

                // 3. Replaying the same uploadToken must NOT insert a duplicate
                // ProjectFileEntity
                when(projectFileRepository.findByProjectIdAndStorageKey(eq(10L), any()))
                                .thenReturn(Optional.of(createdFile));

                ProjectMeetingResponse replayed = service.completeFallbackUpload(
                                10L, 5L, 100L, new CompleteRecordingUploadRequest(presigned.uploadToken(), 60L));

                assertThat(replayed.recordingFileId()).isEqualTo(701L);
                verify(projectFileRepository, times(1)).saveAndFlush(any(ProjectFileEntity.class));
        }

        @Test
        void applyEgressSnapshot_failedMeetingSync_recoversOnlyWhenB2ObjectExists() throws IOException {
                ProjectMeetingEntity failedMeeting = ProjectMeetingEntity.builder()
                                .id(9L).projectId(10L).hostId(100L).title("Recovery Demo")
                                .egressId("EG_REC").recordingStatus(MeetingRecordingStatus.FAILED)
                                .backupStorageUsed(true).build();
                when(meetingRepository.findByEgressId("EG_REC")).thenReturn(Optional.of(failedMeeting));
                when(egressS3StorageService.isConfigured()).thenReturn(true);

                // First call: B2 object definitively 404 -> MUST NOT create an unlinked
                // ProjectFileEntity.
                // Second call: B2 transient network error -> defers cleanly without creating
                // file or flipping FAILED.
                // Third call: B2 object present -> recovers cleanly to COMPLETED.
                when(egressS3StorageService.headObject("recordings/project-10/m-9.mp4"))
                                .thenThrow(new EgressS3StorageService.ObjectNotFoundException("404 Not Found", null))
                                .thenThrow(new IOException("Connection reset"))
                                .thenReturn(new EgressS3StorageService.ObjectMetadata(5000L, "video/mp4", "etag"));

                service.applyEgressSnapshot(new EgressSnapshot(
                                "EG_REC", "room-9", "EGRESS_COMPLETE", "", false,
                                "recordings/project-10/m-9.mp4", 5000L, 1000L));
                service.applyEgressSnapshot(new EgressSnapshot(
                                "EG_REC", "room-9", "EGRESS_COMPLETE", "", false,
                                "recordings/project-10/m-9.mp4", 5000L, 1000L));
                verify(projectFileRepository, never()).saveAndFlush(any());

                when(projectFileRepository.findBySourceEgressId("EG_REC")).thenReturn(Optional.empty());
                when(projectFileRepository.saveAndFlush(any(ProjectFileEntity.class))).thenAnswer(inv -> {
                        ProjectFileEntity e = inv.getArgument(0);
                        e.setId(909L);
                        return e;
                });
                when(meetingRepository.transitionRecordingCompleted(
                                eq(9L), eq("EG_REC"), eq(MeetingRecordingStatus.COMPLETED), eq(909L), eq(false), any(),
                                anyCollection()))
                                .thenReturn(1);

                service.applyEgressSnapshot(new EgressSnapshot(
                                "EG_REC", "room-9", "EGRESS_COMPLETE", "", false,
                                "recordings/project-10/m-9.mp4", 5000L, 1000L));

                verify(meetingRepository).transitionRecordingCompleted(
                                eq(9L), eq("EG_REC"), eq(MeetingRecordingStatus.COMPLETED), eq(909L), eq(false), any(),
                                anyCollection());
        }

        @Test
        void fallbackUpload_rejectedWith409WhenCloudEgressIsActiveAndTokenOutlivesPutUrl() {
                ProjectMeetingEntity activeEgressMeeting = ProjectMeetingEntity.builder()
                                .id(15L).projectId(10L).hostId(100L).title("Active Cloud Rec")
                                .egressId("EG_LIVE").recordingStatus(MeetingRecordingStatus.RECORDING).build();
                when(meetingRepository.findByIdAndProjectId(15L, 10L)).thenReturn(Optional.of(activeEgressMeeting));

                assertThatThrownBy(() -> service.presignFallbackUpload(
                                10L, 15L, 100L, new PresignRecordingUploadRequest("video/webm", "fb.webm")))
                                .isInstanceOf(BusinessException.class)
                                .extracting("status")
                                .isEqualTo(409);

                // For an idle meeting, uploadToken expiry MUST exceed the 20-minute presigned
                // PUT URL expiry (+60m grace)
                ProjectMeetingEntity idleMeeting = ProjectMeetingEntity.builder()
                                .id(16L).projectId(10L).hostId(100L).title("Large Fallback")
                                .recordingStatus(MeetingRecordingStatus.IDLE).build();
                when(meetingRepository.findByIdAndProjectId(16L, 10L)).thenReturn(Optional.of(idleMeeting));
                when(egressS3StorageService.isConfigured()).thenReturn(true);
                Instant putExpiry = Instant.now().plus(Duration.ofMinutes(20));
                when(egressS3StorageService.generatePresignedPutUrl(any(), eq("video/webm")))
                                .thenReturn(new EgressS3StorageService.PresignedUrlResult("https://b2.example/put",
                                                putExpiry));

                PresignRecordingUploadResponse presigned = service.presignFallbackUpload(
                                10L, 16L, 100L, new PresignRecordingUploadRequest("video/webm", "large.webm"));
                var claims = presignedUploadTokenService.verifyToken(presigned.uploadToken(), 10L, 16L, 100L)
                                .orElseThrow();
                assertThat(claims.expiresAt()).isAfter(putExpiry.plus(Duration.ofMinutes(55)));
        }

        @Test
        void multiClipHistory_registersCloudEgressAndFallbackEntriesIdempotentlyAndStreamsOlderClipByFileId()
                        throws IOException {
                ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                                .id(5L).projectId(10L).hostId(100L).title("Multi Clip Sync")
                                .roomName("tp-proj-10-m-5").egressId("EG_CLIP_1")
                                .status(ProjectMeetingStatus.ACTIVE)
                                .recordingStatus(MeetingRecordingStatus.RECORDING).build();
                when(meetingRepository.findByEgressId("EG_CLIP_1")).thenReturn(Optional.of(meeting));
                when(projectFileRepository.findBySourceEgressId("EG_CLIP_1")).thenReturn(Optional.empty());
                ProjectFileEntity cloudFile = ProjectFileEntity.builder()
                                .id(700L).projectId(10L).uploaderId(100L).originalName("cloud.mp4")
                                .contentType("video/mp4").fileSize(4096L).storageProvider("b2")
                                .storageKey("recordings/project-10/cloud.mp4").build();
                when(projectFileRepository.saveAndFlush(any(ProjectFileEntity.class))).thenReturn(cloudFile);
                when(meetingRepository.transitionRecordingCompleted(eq(5L), eq("EG_CLIP_1"),
                                eq(MeetingRecordingStatus.COMPLETED), eq(700L), eq(false), any(), anyCollection()))
                                .thenReturn(1);

                service.applyEgressSnapshot(new EgressSnapshot(
                                "EG_CLIP_1", "tp-proj-10-m-5", "EGRESS_COMPLETE", "", false,
                                "recordings/project-10/cloud.mp4", 4096L, 45_000_000_000L));

                ArgumentCaptor<MeetingRecordingEntity> savedCap = ArgumentCaptor.forClass(MeetingRecordingEntity.class);
                verify(meetingRecordingRepository, times(1)).saveAndFlush(savedCap.capture());
                MeetingRecordingEntity cloudEntry = savedCap.getValue();
                assertThat(cloudEntry.getOrigin()).isEqualTo(MeetingRecordingEntity.Origin.CLOUD_EGRESS);
                assertThat(cloudEntry.getCoverage()).isEqualTo(MeetingRecordingEntity.Coverage.CLOUD_COMPOSITE);
                assertThat(cloudEntry.getState()).isEqualTo(MeetingRecordingEntity.State.READY);
                assertThat(cloudEntry.getProjectFileId()).isEqualTo(700L);
                assertThat(cloudEntry.getDurationMs()).isEqualTo(45_000L);

                // Older cloud clip (700) remains playable via explicit fileId even after
                // meeting.recordingFileId moves to 701!
                ProjectMeetingEntity updatedMeeting = ProjectMeetingEntity.builder()
                                .id(5L).projectId(10L).hostId(100L).recordingFileId(701L)
                                .recordingStatus(MeetingRecordingStatus.COMPLETED).build();
                when(meetingRepository.findByIdAndProjectId(5L, 10L)).thenReturn(Optional.of(updatedMeeting));
                when(meetingRecordingRepository.findByProjectFileId(700L)).thenReturn(Optional.of(cloudEntry));
                when(projectFileRepository.findByIdAndProjectId(700L, 10L)).thenReturn(Optional.of(cloudFile));
                when(egressS3StorageService.isConfigured()).thenReturn(true);
                Instant exp = Instant.now().plusSeconds(1800);
                when(fileStreamTokenService.issueToken(200L, 10L, 700L))
                                .thenReturn(new FileStreamTokenService.IssuedStreamToken("tok-older-700", exp));

                MeetingRecordingPlaybackResponse olderPlayback = service.getPlaybackUrl(10L, 5L, 200L, 700L);
                assertThat(olderPlayback.recordingFileId()).isEqualTo(700L);
                assertThat(olderPlayback.url()).isEqualTo("/api/v1/projects/10/files/700/stream?token=tok-older-700");

                // Foreign fileId not linked to meeting 5 must be rejected with 404.
                when(meetingRecordingRepository.findByProjectFileId(999L)).thenReturn(Optional.empty());
                assertThatThrownBy(() -> service.getPlaybackUrl(10L, 5L, 200L, 999L))
                                .isInstanceOf(BusinessException.class)
                                .extracting("status")
                                .isEqualTo(404);
        }

        @Test
        void presignAndCompleteFallbackUpload_reservesDurableB2OperationAttachesOrCompensatesDurably()
                        throws IOException {
                var uploadPort = org.mockito.Mockito
                                .mock(com.taskpilot.contracts.storage.StorageUploadOperationPort.class);
                service.setStorageUploadOperationPort(uploadPort);

                ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                                .id(20L).projectId(10L).hostId(100L).title("Durable B2 Fallback")
                                .status(ProjectMeetingStatus.ACTIVE).recordingStatus(MeetingRecordingStatus.IDLE)
                                .build();
                when(meetingRepository.findByIdAndProjectId(20L, 10L)).thenReturn(Optional.of(meeting));
                when(egressS3StorageService.isConfigured()).thenReturn(true);
                Instant putExpiry = Instant.now().plus(Duration.ofMinutes(20));
                when(egressS3StorageService.generatePresignedPutUrl(any(), eq("video/webm")))
                                .thenReturn(new EgressS3StorageService.PresignedUrlResult(
                                                "https://b2.example/put-durable", putExpiry));

                java.util.UUID opId = java.util.UUID.randomUUID();
                when(uploadPort.reserve(any())).thenAnswer(inv -> {
                        com.taskpilot.contracts.storage.StorageUploadReservationCommand cmd = inv.getArgument(0);
                        return new com.taskpilot.contracts.storage.StorageUploadOperationDto(
                                        opId, cmd.ownerUserId(), cmd.projectId(), cmd.targetDomain(), cmd.provider(),
                                        cmd.bucket(), cmd.plannedObjectKey(), null,
                                        com.taskpilot.contracts.storage.StorageUploadOperationStatus.RESERVED,
                                        null, Instant.now().plus(cmd.leaseDuration() != null ? cmd.leaseDuration()
                                                        : Duration.ofMinutes(10)));
                });

                PresignRecordingUploadResponse presigned = service.presignFallbackUpload(
                                10L, 20L, 100L, new PresignRecordingUploadRequest("video/webm", "durable.webm"));

                var reserveCap = ArgumentCaptor
                                .forClass(com.taskpilot.contracts.storage.StorageUploadReservationCommand.class);
                verify(uploadPort).reserve(reserveCap.capture());
                assertThat(reserveCap.getValue().provider())
                                .isEqualTo(com.taskpilot.contracts.storage.StorageProviderKind.B2);
                assertThat(reserveCap.getValue().bucket()).isEqualTo("taskpilot-recordings-2026");
                assertThat(reserveCap.getValue().plannedObjectKey()).startsWith("recordings/10/20/");
                // Per-operation lease MUST exceed uploadTokenTtl (20m PUT + 60m grace = 80m) so
                // late valid PUTs are not prematurely deleted.
                assertThat(reserveCap.getValue().leaseDuration()).isGreaterThan(Duration.ofMinutes(80));

                var claims = presignedUploadTokenService.verifyToken(presigned.uploadToken(), 10L, 20L, 100L)
                                .orElseThrow();
                assertThat(claims.operationId()).isEqualTo(opId);

                // Oversized upload must enqueue durable compensation rather than relying on
                // best-effort delete.
                when(egressS3StorageService.headObject(claims.objectKey()))
                                .thenReturn(new EgressS3StorageService.ObjectMetadata(25L * 1024L * 1024L, "video/webm",
                                                "etag-big"));
                assertThatThrownBy(() -> service.completeFallbackUpload(
                                10L, 20L, 100L, new CompleteRecordingUploadRequest(presigned.uploadToken(), 30L)))
                                .isInstanceOf(BusinessException.class)
                                .extracting("status")
                                .isEqualTo(413);
                ArgumentCaptor<Instant> oversizedNotBefore = ArgumentCaptor.forClass(Instant.class);
                verify(uploadPort).compensateFailedUpload(
                                eq(opId),
                                eq(new com.taskpilot.contracts.storage.StorageObjectLocator(
                                                com.taskpilot.contracts.storage.StorageProviderKind.B2,
                                                "taskpilot-recordings-2026",
                                                claims.objectKey())),
                                eq("B2_FALLBACK_OVERSIZED"),
                                any(),
                                oversizedNotBefore.capture());
                assertThat(oversizedNotBefore.getValue()).isAfter(putExpiry);

                // Valid upload marks provider-written and attaches inside the same transaction
                // as file creation.
                when(projectFileRepository.findByProjectIdAndStorageKey(10L, claims.objectKey()))
                                .thenReturn(Optional.empty());
                when(egressS3StorageService.headObject(claims.objectKey()))
                                .thenReturn(new EgressS3StorageService.ObjectMetadata(2L * 1024L * 1024L, "video/webm",
                                                "etag-ok"));
                ProjectFileEntity saved = ProjectFileEntity.builder().id(808L).projectId(10L).storageProvider("b2")
                                .build();
                when(projectFileRepository.saveAndFlush(any(ProjectFileEntity.class))).thenReturn(saved);
                when(meetingRepository.linkFallbackRecording(eq(20L), eq(808L), any())).thenReturn(1);
                when(projectMeetingService.getMeetingById(10L, 20L, 100L))
                                .thenReturn(ProjectMeetingResponse.builder().id(20L).recordingFileId(808L)
                                                .recordingStatus(MeetingRecordingStatus.COMPLETED).build());

                service.completeFallbackUpload(10L, 20L, 100L,
                                new CompleteRecordingUploadRequest(presigned.uploadToken(), 30L));
                verify(uploadPort).markProviderWritten(opId, claims.objectKey());
                verify(uploadPort).markAttachedInCurrentTransaction(opId, 808L);
        }

        @Test
        void startRecording_whenMeetingEndsDuringBlockedRpc_persistsEgressIdRequestsStopAndRejectsWith409()
                        throws IOException {
                ProjectMeetingEntity activeMeeting = ProjectMeetingEntity.builder()
                                .id(31L).projectId(10L).hostId(100L).title("Race Meeting")
                                .roomName("tp-proj-10-m-31").status(ProjectMeetingStatus.ACTIVE)
                                .recordingStatus(MeetingRecordingStatus.IDLE).build();
                ProjectMeetingEntity endingAfterAttach = ProjectMeetingEntity.builder()
                                .id(31L).projectId(10L).hostId(100L).title("Race Meeting")
                                .roomName("tp-proj-10-m-31").status(ProjectMeetingStatus.ENDING)
                                .egressId("EG_RACE_END").recordingStatus(MeetingRecordingStatus.RECORDING).build();

                when(meetingRepository.findByIdAndProjectId(31L, 10L))
                                .thenReturn(Optional.of(activeMeeting))
                                .thenReturn(Optional.of(endingAfterAttach));
                when(egressClientAdapter.isConfigured()).thenReturn(true);
                when(egressS3StorageService.isConfigured()).thenReturn(true);
                when(meetingRepository.claimRecordingStart(eq(31L), any())).thenReturn(1);
                when(egressClientAdapter.startRoomCompositeEgress(eq("tp-proj-10-m-31"), any(),
                                eq(egressS3StorageService)))
                                .thenReturn(new EgressSnapshot("EG_RACE_END", "tp-proj-10-m-31", "EGRESS_ACTIVE", "",
                                                false, "", 0L, 0L));
                when(meetingRepository.attachStartedEgress(eq(31L), eq("EG_RACE_END"),
                                eq(MeetingRecordingStatus.RECORDING), any()))
                                .thenReturn(1);
                when(egressClientAdapter.stopEgress("EG_RACE_END"))
                                .thenReturn(new EgressSnapshot("EG_RACE_END", "tp-proj-10-m-31", "EGRESS_ENDING", "",
                                                false, "", 0L, 0L));

                assertThatThrownBy(() -> service.startRecording(10L, 31L, 100L))
                                .isInstanceOf(BusinessException.class)
                                .extracting("status")
                                .isEqualTo(409);

                // Egress ID MUST be durably attached before stopping so reconciliation can
                // recover if stop fails.
                verify(meetingRepository).attachStartedEgress(eq(31L), eq("EG_RACE_END"),
                                eq(MeetingRecordingStatus.RECORDING), any());
                verify(egressClientAdapter).stopEgress("EG_RACE_END");
        }

        @Test
        void startRecording_whenMeetingEndsAndInitialStopFails_keepsPersistedEgressIdAndStillRejectsWith409()
                        throws IOException {
                ProjectMeetingEntity activeMeeting = ProjectMeetingEntity.builder()
                                .id(32L).projectId(10L).hostId(100L).title("Race Stop Failure")
                                .roomName("tp-proj-10-m-32").status(ProjectMeetingStatus.ACTIVE)
                                .recordingStatus(MeetingRecordingStatus.IDLE).build();
                ProjectMeetingEntity endedAfterAttach = ProjectMeetingEntity.builder()
                                .id(32L).projectId(10L).hostId(100L).title("Race Stop Failure")
                                .roomName("tp-proj-10-m-32").status(ProjectMeetingStatus.ENDED)
                                .egressId("EG_RACE_STOP_FAIL").recordingStatus(MeetingRecordingStatus.RECORDING)
                                .build();

                when(meetingRepository.findByIdAndProjectId(32L, 10L))
                                .thenReturn(Optional.of(activeMeeting))
                                .thenReturn(Optional.of(endedAfterAttach));
                when(egressClientAdapter.isConfigured()).thenReturn(true);
                when(egressS3StorageService.isConfigured()).thenReturn(true);
                when(meetingRepository.claimRecordingStart(eq(32L), any())).thenReturn(1);
                when(egressClientAdapter.startRoomCompositeEgress(eq("tp-proj-10-m-32"), any(),
                                eq(egressS3StorageService)))
                                .thenReturn(new EgressSnapshot("EG_RACE_STOP_FAIL", "tp-proj-10-m-32", "EGRESS_ACTIVE",
                                                "", false, "", 0L, 0L));
                when(meetingRepository.attachStartedEgress(eq(32L), eq("EG_RACE_STOP_FAIL"),
                                eq(MeetingRecordingStatus.RECORDING), any()))
                                .thenReturn(1);
                when(egressClientAdapter.stopEgress("EG_RACE_STOP_FAIL"))
                                .thenThrow(new IOException("LiveKit 503 transient failure"));

                assertThatThrownBy(() -> service.startRecording(10L, 32L, 100L))
                                .isInstanceOf(BusinessException.class)
                                .extracting("status")
                                .isEqualTo(409);

                verify(meetingRepository).attachStartedEgress(eq(32L), eq("EG_RACE_STOP_FAIL"),
                                eq(MeetingRecordingStatus.RECORDING), any());
                verify(egressClientAdapter).stopEgress("EG_RACE_STOP_FAIL");
        }

        @Test
        void f1_completeFallbackUpload_whenMeetingLinkFails_rollsBackFileAndAttachmentInSameTransaction()
                        throws IOException {
                var uploadPort = org.mockito.Mockito
                                .mock(com.taskpilot.contracts.storage.StorageUploadOperationPort.class);
                service.setStorageUploadOperationPort(uploadPort);

                ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                                .id(41L).projectId(10L).hostId(100L).title("Atomic Fallback Link")
                                .status(ProjectMeetingStatus.ACTIVE).recordingStatus(MeetingRecordingStatus.IDLE)
                                .build();
                when(meetingRepository.findByIdAndProjectId(41L, 10L)).thenReturn(Optional.of(meeting));
                when(egressS3StorageService.isConfigured()).thenReturn(true);

                java.util.UUID opId = java.util.UUID.randomUUID();
                String key = "recordings/10/41/atomic.webm";
                String token = presignedUploadTokenService.issueToken(
                                10L, 41L, 100L, key, "video/webm", "atomic.webm", Duration.ofMinutes(20), opId);

                when(projectFileRepository.findByProjectIdAndStorageKey(10L, key)).thenReturn(Optional.empty());
                when(egressS3StorageService.headObject(key))
                                .thenReturn(new EgressS3StorageService.ObjectMetadata(1024L, "video/webm",
                                                "etag-atomic"));
                when(projectFileRepository.saveAndFlush(any(ProjectFileEntity.class)))
                                .thenReturn(ProjectFileEntity.builder().id(901L).projectId(10L).storageKey(key)
                                                .storageProvider("b2").fileSize(1024L).contentType("video/webm")
                                                .build());
                // Cloud recording wins claim right before linkFallbackRecording -> returns 0
                when(meetingRepository.linkFallbackRecording(eq(41L), eq(901L), any())).thenReturn(0);

                assertThatThrownBy(() -> service.completeFallbackUpload(10L, 41L, 100L,
                                new CompleteRecordingUploadRequest(token, 12L)))
                                .isInstanceOf(BusinessException.class)
                                .extracting("status")
                                .isEqualTo(409);

                // File + operation attachment MUST NOT commit before linkFallbackRecording
                // succeeds.
                verify(transactionManager, never()).commit(any());
                verify(transactionManager, times(1)).rollback(any());
                verify(meetingRecordingRepository, never()).saveAndFlush(any());
        }

        @Test
        void f2_applyEgressCompleted_commitsHistoryAtomicallyAndRepairsMissingHistoryOnReplay() {
                ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                                .id(42L).projectId(10L).hostId(100L).title("Atomic Cloud History")
                                .egressId("EG_F2_ATOMIC").status(ProjectMeetingStatus.ACTIVE)
                                .recordingStatus(MeetingRecordingStatus.RECORDING).build();
                ProjectFileEntity file = ProjectFileEntity.builder()
                                .id(902L).projectId(10L).sourceEgressId("EG_F2_ATOMIC")
                                .storageKey("recordings/project-10/m-42.mp4").storageProvider("b2")
                                .fileSize(4096L).contentType("video/mp4").build();

                when(meetingRepository.findByEgressId("EG_F2_ATOMIC")).thenReturn(Optional.of(meeting));
                when(projectFileRepository.findBySourceEgressId("EG_F2_ATOMIC")).thenReturn(Optional.of(file));
                when(meetingRepository.transitionRecordingCompleted(
                                eq(42L), eq("EG_F2_ATOMIC"), eq(MeetingRecordingStatus.COMPLETED), eq(902L), eq(false),
                                any(), anyCollection()))
                                .thenReturn(1);
                when(meetingRecordingRepository.findById(any())).thenReturn(Optional.empty());
                when(meetingRecordingRepository.findByProjectFileId(902L)).thenReturn(Optional.empty());
                when(meetingRecordingRepository.saveAndFlush(any(MeetingRecordingEntity.class)))
                                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException(
                                                "Simulated history write failure"));

                EgressSnapshot snap = new EgressSnapshot(
                                "EG_F2_ATOMIC", "tp-room-42", "EGRESS_COMPLETE", "", false,
                                "recordings/project-10/m-42.mp4", 4096L, 5_000_000_000L);

                assertThatThrownBy(() -> service.applyEgressSnapshot(snap))
                                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
                // Terminal transition MUST roll back together with the failed history write.
                verify(transactionManager, never()).commit(any());
                verify(transactionManager, times(1)).rollback(any());

                // Defense-in-depth: even if meeting row is already COMPLETED with missing
                // history, replay MUST repair it.
                org.mockito.Mockito.reset(meetingRecordingRepository);
                meeting.setRecordingStatus(MeetingRecordingStatus.COMPLETED);
                meeting.setRecordingFileId(902L);
                when(meetingRecordingRepository.findById(any())).thenReturn(Optional.empty());
                when(meetingRecordingRepository.findByProjectFileId(902L)).thenReturn(Optional.empty());
                when(meetingRecordingRepository.saveAndFlush(any(MeetingRecordingEntity.class)))
                                .thenAnswer(inv -> inv.getArgument(0));

                service.applyEgressSnapshot(snap);
                verify(meetingRecordingRepository, times(1)).saveAndFlush(any(MeetingRecordingEntity.class));
        }

        @Test
        void f3_rejectedFallbackUpload_schedulesDurableCleanupAfterPutExpiryAndDeletesImmediatelyBestEffort()
                        throws IOException {
                var uploadPort = org.mockito.Mockito
                                .mock(com.taskpilot.contracts.storage.StorageUploadOperationPort.class);
                service.setStorageUploadOperationPort(uploadPort);

                ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                                .id(43L).projectId(10L).hostId(100L).title("Post-PUT Deferred Cleanup")
                                .status(ProjectMeetingStatus.ACTIVE).recordingStatus(MeetingRecordingStatus.IDLE)
                                .build();
                when(meetingRepository.findByIdAndProjectId(43L, 10L)).thenReturn(Optional.of(meeting));
                when(egressS3StorageService.isConfigured()).thenReturn(true);

                Instant putExpiry = Instant.now().plus(Duration.ofMinutes(15));
                when(egressS3StorageService.generatePresignedPutUrl(any(), eq("video/webm")))
                                .thenReturn(new EgressS3StorageService.PresignedUrlResult("https://b2.example/put-f3",
                                                putExpiry));
                java.util.UUID opId = java.util.UUID.randomUUID();
                when(uploadPort.reserve(any())).thenAnswer(inv -> {
                        com.taskpilot.contracts.storage.StorageUploadReservationCommand cmd = inv.getArgument(0);
                        return new com.taskpilot.contracts.storage.StorageUploadOperationDto(
                                        opId, cmd.ownerUserId(), cmd.projectId(), cmd.targetDomain(), cmd.provider(),
                                        cmd.bucket(), cmd.plannedObjectKey(), null,
                                        com.taskpilot.contracts.storage.StorageUploadOperationStatus.RESERVED,
                                        null, Instant.now().plus(Duration.ofMinutes(95)));
                });

                PresignRecordingUploadResponse presigned = service.presignFallbackUpload(
                                10L, 43L, 100L, new PresignRecordingUploadRequest("video/webm", "f3.webm"));
                var claims = presignedUploadTokenService.verifyToken(presigned.uploadToken(), 10L, 43L, 100L)
                                .orElseThrow();
                assertThat(claims.putExpiresAt()).isNotNull();

                when(projectFileRepository.findByProjectIdAndStorageKey(10L, claims.objectKey()))
                                .thenReturn(Optional.empty());
                when(egressS3StorageService.headObject(claims.objectKey()))
                                .thenReturn(new EgressS3StorageService.ObjectMetadata(0L, "video/webm", "etag-empty"));

                assertThatThrownBy(() -> service.completeFallbackUpload(
                                10L, 43L, 100L, new CompleteRecordingUploadRequest(presigned.uploadToken(), 5L)))
                                .isInstanceOf(BusinessException.class)
                                .extracting("status")
                                .isEqualTo(400);

                ArgumentCaptor<Instant> notBeforeCap = ArgumentCaptor.forClass(Instant.class);
                verify(uploadPort).compensateFailedUpload(
                                eq(opId),
                                eq(new com.taskpilot.contracts.storage.StorageObjectLocator(
                                                com.taskpilot.contracts.storage.StorageProviderKind.B2,
                                                "taskpilot-recordings-2026",
                                                claims.objectKey())),
                                eq("B2_FALLBACK_EMPTY"),
                                any(),
                                notBeforeCap.capture());
                // Durable cleanup job MUST NOT become eligible to mark SUCCEEDED until strictly
                // AFTER presigned PUT expires.
                assertThat(notBeforeCap.getValue()).isAfter(putExpiry);
                verify(egressS3StorageService).deleteFile(claims.objectKey());
        }

        @Test
        void f4_logicalClientRecordingId_reusesExistingClipOnRetryPresignAndCompensatesConcurrentDuplicate()
                        throws IOException {
                var uploadPort = org.mockito.Mockito
                                .mock(com.taskpilot.contracts.storage.StorageUploadOperationPort.class);
                service.setStorageUploadOperationPort(uploadPort);

                ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                                .id(44L).projectId(10L).hostId(100L).title("Idempotent Logical Clip")
                                .status(ProjectMeetingStatus.ACTIVE).recordingStatus(MeetingRecordingStatus.IDLE)
                                .build();
                when(meetingRepository.findByIdAndProjectId(44L, 10L)).thenReturn(Optional.of(meeting));
                when(egressS3StorageService.isConfigured()).thenReturn(true);

                String clientClipId = "rec-logical-uuid-1";
                java.util.UUID expectedClipUuid = java.util.UUID.nameUUIDFromBytes(
                                ("meeting-recording:10:44:client:100:" + clientClipId)
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

                // Case 1: logical clip already committed server-side (e.g. client lost complete
                // response & restarted) -> presign returns alreadyCompleted without
                // reserving/issuing new PUT URL.
                MeetingRecordingEntity committedClip = MeetingRecordingEntity.builder()
                                .id(expectedClipUuid).meetingId(44L).projectId(10L).recorderId(100L)
                                .projectFileId(944L).origin(MeetingRecordingEntity.Origin.BROWSER)
                                .coverage(MeetingRecordingEntity.Coverage.SCREEN_ONLY)
                                .state(MeetingRecordingEntity.State.READY).build();
                when(meetingRecordingRepository.findById(expectedClipUuid)).thenReturn(Optional.of(committedClip));

                PresignRecordingUploadResponse retryPresign = service.presignFallbackUpload(
                                10L, 44L, 100L,
                                new PresignRecordingUploadRequest("video/webm", "clip1.webm", clientClipId));

                assertThat(retryPresign.alreadyCompleted()).isTrue();
                assertThat(retryPresign.existingFileId()).isEqualTo(944L);
                assertThat(retryPresign.uploadUrl()).isNull();
                verify(uploadPort, never()).reserve(any());
                verify(egressS3StorageService, never()).generatePresignedPutUrl(any(), any());

                // Case 2: concurrent duplicate retry completed after winner -> loser
                // compensates duplicate B2 object durably and reuses winning file.
                java.util.UUID loserOpId = java.util.UUID.randomUUID();
                String loserKey = "recordings/10/44/loser-key.webm";
                Instant putExpiry = Instant.now().plus(Duration.ofMinutes(15));
                String loserToken = presignedUploadTokenService.issueToken(
                                10L, 44L, 100L, loserKey, "video/webm", "clip1.webm",
                                Duration.ofMinutes(60), loserOpId, putExpiry, clientClipId);

                ProjectFileEntity winnerFile = ProjectFileEntity.builder()
                                .id(944L).projectId(10L).storageKey("recordings/10/44/winner-key.webm")
                                .storageProvider("b2").fileSize(2048L).contentType("video/webm").build();
                when(projectFileRepository.findByProjectIdAndStorageKey(10L, loserKey)).thenReturn(Optional.empty());
                when(projectFileRepository.findById(944L)).thenReturn(Optional.of(winnerFile));
                when(projectMeetingService.getMeetingById(10L, 44L, 100L))
                                .thenReturn(ProjectMeetingResponse.builder().id(44L).recordingFileId(944L)
                                                .recordingStatus(MeetingRecordingStatus.COMPLETED).build());

                ProjectMeetingResponse res = service.completeFallbackUpload(
                                10L, 44L, 100L,
                                new CompleteRecordingUploadRequest(loserToken, 10L,
                                                MeetingRecordingEntity.Origin.BROWSER,
                                                MeetingRecordingEntity.Coverage.SCREEN_ONLY, clientClipId));

                assertThat(res.recordingFileId()).isEqualTo(944L);
                verify(projectFileRepository, never()).saveAndFlush(any());
                verify(uploadPort).compensateFailedUpload(
                                eq(loserOpId),
                                eq(new com.taskpilot.contracts.storage.StorageObjectLocator(
                                                com.taskpilot.contracts.storage.StorageProviderKind.B2,
                                                "taskpilot-recordings-2026",
                                                loserKey)),
                                eq("B2_FALLBACK_DUPLICATE_CLIP"),
                                any(),
                                any(Instant.class));
        }

        @Test
        void f4a_concurrentComplete_whenWinnerCommitsAfterPrecheck_rollsBackLoserAndCompensates() throws IOException {
                var uploadPort = org.mockito.Mockito
                                .mock(com.taskpilot.contracts.storage.StorageUploadOperationPort.class);
                service.setStorageUploadOperationPort(uploadPort);

                ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                                .id(45L).projectId(10L).hostId(100L).title("Concurrent F4-A")
                                .status(ProjectMeetingStatus.ACTIVE).recordingStatus(MeetingRecordingStatus.COMPLETED)
                                .recordingFileId(950L).build();
                when(meetingRepository.findByIdAndProjectId(45L, 10L)).thenReturn(Optional.of(meeting));
                when(meetingRepository.findForUpdate(45L, 10L)).thenReturn(Optional.of(meeting));
                when(egressS3StorageService.isConfigured()).thenReturn(true);

                String clientClipId = "clip-concurrent-race";
                java.util.UUID clipUuid = java.util.UUID.nameUUIDFromBytes(
                                ("meeting-recording:10:45:client:100:" + clientClipId)
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                java.util.UUID loserOpId = java.util.UUID.randomUUID();
                String loserKey = "recordings/10/45/loser-concurrent.webm";
                String loserToken = presignedUploadTokenService.issueToken(
                                10L, 45L, 100L, loserKey, "video/webm", "race.webm",
                                Duration.ofMinutes(60), loserOpId, Instant.now().plus(Duration.ofMinutes(15)),
                                clientClipId);

                MeetingRecordingEntity winnerClip = MeetingRecordingEntity.builder()
                                .id(clipUuid).meetingId(45L).projectId(10L).recorderId(100L)
                                .projectFileId(950L).origin(MeetingRecordingEntity.Origin.BROWSER)
                                .coverage(MeetingRecordingEntity.Coverage.SCREEN_ONLY)
                                .state(MeetingRecordingEntity.State.READY).build();
                ProjectFileEntity winnerFile = ProjectFileEntity.builder()
                                .id(950L).projectId(10L).storageKey("recordings/10/45/winner.webm")
                                .storageProvider("b2").fileSize(2048L).contentType("video/webm").build();

                when(projectFileRepository.findByProjectIdAndStorageKey(10L, loserKey)).thenReturn(Optional.empty());
                // Precheck misses (winner hasn't committed yet); inside serialized tx, winner
                // HAS committed!
                when(meetingRecordingRepository.findById(clipUuid))
                                .thenReturn(Optional.empty())
                                .thenReturn(Optional.of(winnerClip));
                when(egressS3StorageService.headObject(loserKey))
                                .thenReturn(new EgressS3StorageService.ObjectMetadata(2048L, "video/webm",
                                                "etag-loser"));
                when(projectFileRepository.findById(950L)).thenReturn(Optional.of(winnerFile));
                when(projectMeetingService.getMeetingById(10L, 45L, 100L))
                                .thenReturn(ProjectMeetingResponse.builder().id(45L).recordingFileId(950L)
                                                .recordingStatus(MeetingRecordingStatus.COMPLETED).build());

                ProjectMeetingResponse res = service.completeFallbackUpload(
                                10L, 45L, 100L,
                                new CompleteRecordingUploadRequest(loserToken, 10L,
                                                MeetingRecordingEntity.Origin.BROWSER,
                                                MeetingRecordingEntity.Coverage.SCREEN_ONLY, clientClipId));

                assertThat(res.completedRecordingFileId()).isEqualTo(950L);
                assertThat(res.recordingFileId()).isEqualTo(950L);
                // Loser MUST roll back and MUST NOT commit a second file/attachment or
                // overwrite latest pointer.
                verify(transactionManager, never()).commit(any());
                verify(transactionManager, times(1)).rollback(any());
                verify(projectFileRepository, never()).saveAndFlush(any());
                verify(uploadPort, never()).markAttachedInCurrentTransaction(eq(loserOpId), any(Long.class));
                verify(uploadPort).compensateFailedUpload(
                                eq(loserOpId),
                                eq(new com.taskpilot.contracts.storage.StorageObjectLocator(
                                                com.taskpilot.contracts.storage.StorageProviderKind.B2,
                                                "taskpilot-recordings-2026",
                                                loserKey)),
                                eq("B2_FALLBACK_DUPLICATE_CLIP"),
                                any(),
                                any(Instant.class));
        }

        @Test
        void f4b_replayOldClipAAfterNewerClipB_preservesLatestAtBAndReturnsReceiptForA() throws IOException {
                ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                                .id(46L).projectId(10L).hostId(100L).title("A->B->Retry A")
                                .status(ProjectMeetingStatus.ACTIVE).recordingStatus(MeetingRecordingStatus.COMPLETED)
                                .recordingFileId(55L).build();
                when(meetingRepository.findByIdAndProjectId(46L, 10L)).thenReturn(Optional.of(meeting));
                when(egressS3StorageService.isConfigured()).thenReturn(true);

                String keyA = "recordings/10/46/clip-a.webm";
                String clipIdA = "clip-A";
                String tokenA = presignedUploadTokenService.issueToken(
                                10L, 46L, 100L, keyA, "video/webm", "clip-a.webm",
                                Duration.ofMinutes(60), java.util.UUID.randomUUID(),
                                Instant.now().plus(Duration.ofMinutes(15)), clipIdA);

                ProjectFileEntity fileA = ProjectFileEntity.builder()
                                .id(44L).projectId(10L).storageKey(keyA).storageProvider("b2").fileSize(1024L)
                                .contentType("video/webm").build();
                MeetingRecordingEntity historyA = MeetingRecordingEntity.builder()
                                .id(java.util.UUID.nameUUIDFromBytes(("meeting-recording:10:46:client:100:" + clipIdA)
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                                .meetingId(46L).projectId(10L).recorderId(100L).projectFileId(44L)
                                .origin(MeetingRecordingEntity.Origin.BROWSER)
                                .coverage(MeetingRecordingEntity.Coverage.SCREEN_ONLY)
                                .state(MeetingRecordingEntity.State.READY).build();

                when(projectFileRepository.findByProjectIdAndStorageKey(10L, keyA)).thenReturn(Optional.of(fileA));
                when(meetingRecordingRepository.findByProjectFileId(44L)).thenReturn(Optional.of(historyA));
                when(projectMeetingService.getMeetingById(10L, 46L, 100L))
                                .thenReturn(ProjectMeetingResponse.builder().id(46L).recordingFileId(55L)
                                                .recordingStatus(MeetingRecordingStatus.COMPLETED).build());

                ProjectMeetingResponse replay = service.completeFallbackUpload(
                                10L, 46L, 100L,
                                new CompleteRecordingUploadRequest(tokenA, 12L, MeetingRecordingEntity.Origin.BROWSER,
                                                MeetingRecordingEntity.Coverage.SCREEN_ONLY, clipIdA));

                // Receipt MUST identify clip A (44) while meeting latest pointer stays on clip
                // B (55) without rewinding!
                assertThat(replay.completedRecordingFileId()).isEqualTo(44L);
                assertThat(replay.recordingFileId()).isEqualTo(55L);
                verify(meetingRepository, never()).linkFallbackRecording(eq(46L), eq(44L), any());
        }

    @Test
    void f4p1_sameTokenConcurrentComplete_neverDeletesOrCompensatesWinnerKey() throws IOException {
        var uploadPort = org.mockito.Mockito.mock(com.taskpilot.contracts.storage.StorageUploadOperationPort.class);
        service.setStorageUploadOperationPort(uploadPort);

        ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                .id(47L).projectId(10L).hostId(100L).title("Same-Token Concurrent Complete")
                .status(ProjectMeetingStatus.ACTIVE).recordingStatus(MeetingRecordingStatus.IDLE).build();
        when(meetingRepository.findByIdAndProjectId(47L, 10L)).thenReturn(Optional.of(meeting));
        when(meetingRepository.findForUpdate(47L, 10L)).thenReturn(Optional.of(meeting));
        when(egressS3StorageService.isConfigured()).thenReturn(true);

        String clientClipId = "clip-same-token-race";
        java.util.UUID clipUuid = java.util.UUID.nameUUIDFromBytes(
                ("meeting-recording:10:47:client:100:" + clientClipId).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        java.util.UUID sharedOpId = java.util.UUID.randomUUID();
        String sharedKey = "recordings/10/47/shared-winner-key.webm";
        String sharedToken = presignedUploadTokenService.issueToken(
                10L, 47L, 100L, sharedKey, "video/webm", "shared.webm",
                Duration.ofMinutes(60), sharedOpId, Instant.now().plus(Duration.ofMinutes(15)), clientClipId);

        ProjectFileEntity winnerFile = ProjectFileEntity.builder()
                .id(947L).projectId(10L).storageKey(sharedKey).storageProvider("b2")
                .fileSize(2048L).contentType("video/webm").build();
        MeetingRecordingEntity winnerClip = MeetingRecordingEntity.builder()
                .id(clipUuid).meetingId(47L).projectId(10L).recorderId(100L)
                .projectFileId(947L).origin(MeetingRecordingEntity.Origin.BROWSER)
                .coverage(MeetingRecordingEntity.Coverage.SCREEN_ONLY)
                .state(MeetingRecordingEntity.State.READY).build();

        // Precheck misses before winner commits; after headObject & markProviderWritten, winner commits SAME key!
        when(projectFileRepository.findByProjectIdAndStorageKey(10L, sharedKey))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnerFile));
        when(meetingRecordingRepository.findById(clipUuid))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnerClip));
        when(egressS3StorageService.headObject(sharedKey))
                .thenReturn(new EgressS3StorageService.ObjectMetadata(2048L, "video/webm", "etag-shared"));
        lenient().when(projectFileRepository.findById(947L)).thenReturn(Optional.of(winnerFile));
        when(projectMeetingService.getMeetingById(10L, 47L, 100L))
                .thenReturn(ProjectMeetingResponse.builder().id(47L).recordingFileId(947L).recordingStatus(MeetingRecordingStatus.COMPLETED).build());

        ProjectMeetingResponse res = service.completeFallbackUpload(
                10L, 47L, 100L,
                new CompleteRecordingUploadRequest(sharedToken, 10L, MeetingRecordingEntity.Origin.BROWSER, MeetingRecordingEntity.Coverage.SCREEN_ONLY, clientClipId));

        assertThat(res.completedRecordingFileId()).isEqualTo(947L);
        // MUST NEVER delete or enqueue cleanup for the winning B2 object key!
        verify(egressS3StorageService, never()).deleteFile(any());
        verify(uploadPort, never()).compensateFailedUpload(any(), any(), any(), any());
        verify(uploadPort, never()).compensateFailedUpload(any(), any(), any(), any(), any());
    }
}
