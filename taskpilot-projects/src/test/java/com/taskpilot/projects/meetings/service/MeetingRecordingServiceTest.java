package com.taskpilot.projects.meetings.service;

import com.taskpilot.infrastructure.config.security.FileStreamTokenService;
import com.taskpilot.infrastructure.config.security.PresignedUploadTokenService;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.storage.EgressS3StorageService;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.entity.ProjectMeetingEntity;
import com.taskpilot.projects.common.enums.FileArchiveStatus;
import com.taskpilot.projects.common.enums.MeetingRecordingStatus;
import com.taskpilot.projects.common.enums.ProjectMeetingStatus;
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
    private PlatformTransactionManager transactionManager;

    private PresignedUploadTokenService presignedUploadTokenService;

    private MeetingRecordingService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(egressS3StorageService.getBucket()).thenReturn("taskpilot-recordings-2026");
        lenient().when(egressS3StorageService.getPresignPutTtl()).thenReturn(Duration.ofMinutes(20));

        presignedUploadTokenService = new PresignedUploadTokenService(
                "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970"
        );

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
    }

    @Test
    void verifiedRoomEventAlsoReconcilesAttendanceThroughTheCloudWebhookEndpoint() {
        when(egressClientAdapter.receiveWebhook("room-body", "signed-header")).thenReturn(
                new LiveKitEgressClientAdapter.ParsedWebhookEvent("room_finished", "tp-proj-10-room", null));

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
        when(egressClientAdapter.startRoomCompositeEgress(eq("tp-proj-10-m-abc"), any(), eq(egressS3StorageService)))
                .thenReturn(new EgressSnapshot("EG_001", "tp-proj-10-m-abc", "EGRESS_ACTIVE", "", false, "", 0L, 0L));
        when(meetingRepository.attachStartedEgress(eq(5L), eq("EG_001"), eq(MeetingRecordingStatus.RECORDING), any()))
                .thenReturn(1);
        when(projectMeetingService.getMeetingById(10L, 5L, 100L))
                .thenReturn(ProjectMeetingResponse.builder().id(5L).egressId("EG_001").recordingStatus(MeetingRecordingStatus.RECORDING).build());

        ProjectMeetingResponse res = service.startRecording(10L, 5L, 100L);

        assertThat(res.egressId()).isEqualTo("EG_001");
        verify(meetingRepository).claimRecordingStart(eq(5L), any());
        verify(meetingRepository).attachStartedEgress(eq(5L), eq("EG_001"), eq(MeetingRecordingStatus.RECORDING), any());
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
                eq(5L), eq("EG_777"), eq(MeetingRecordingStatus.COMPLETED), eq(55L), eq(false), any(), anyCollection()))
                .thenReturn(1);

        service.applyEgressSnapshot(new EgressSnapshot(
                "EG_777", "room-1", "EGRESS_COMPLETE", "", false,
                "recordings/project-10/meeting-5.mp4", 42_000_000L, 60_000_000_000L
        ));

        ArgumentCaptor<ProjectFileEntity> captor = ArgumentCaptor.forClass(ProjectFileEntity.class);
        verify(projectFileRepository).saveAndFlush(captor.capture());
        ProjectFileEntity saved = captor.getValue();
        assertThat(saved.getStorageProvider()).isEqualTo("b2");
        assertThat(saved.getSourceEgressId()).isEqualTo("EG_777");
        assertThat(saved.getArchiveStatus()).isEqualTo(FileArchiveStatus.ARCHIVE_PENDING);
        assertThat(saved.getFileSize()).isEqualTo(42_000_000L);
        verify(meetingRepository).transitionRecordingCompleted(
                eq(5L), eq("EG_777"), eq(MeetingRecordingStatus.COMPLETED), eq(55L), eq(false), any(), anyCollection());
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
                .thenThrow(new DataIntegrityViolationException("duplicate key uq_project_files_source_egress_id"));

        service.applyEgressSnapshot(new EgressSnapshot(
                "EG_RACE", "room-1", "EGRESS_COMPLETE", "", false,
                "recordings/project-10/meeting-5.mp4", 12_000L, 10_000L
        ));

        verify(meetingRepository).transitionRecordingCompleted(
                eq(5L), eq("EG_RACE"), eq(MeetingRecordingStatus.COMPLETED), eq(88L), eq(false), any(), anyCollection());
    }

    @Test
    void applyEgressSnapshot_failedWithBackupStorageUsed_recordsBackupFlag() {
        ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                .id(5L).projectId(10L).hostId(100L).title("Sprint Demo")
                .egressId("EG_FAIL").recordingStatus(MeetingRecordingStatus.PROCESSING).build();
        when(meetingRepository.findByEgressId("EG_FAIL")).thenReturn(Optional.of(meeting));

        service.applyEgressSnapshot(new EgressSnapshot(
                "EG_FAIL", "room-1", "EGRESS_FAILED", "S3 403 Forbidden", true, "", 0L, 0L
        ));

        verify(meetingRepository).transitionRecordingStatus(
                eq(5L),
                eq("EG_FAIL"),
                eq(MeetingRecordingStatus.FAILED),
                org.mockito.ArgumentMatchers.contains("LiveKit Cloud backup storage"),
                eq(true),
                any(),
                anyCollection()
        );
    }

    @Test
    void getPlaybackUrl_dispatchesB2PresignedAndGdriveRangeProxy() {
        ProjectMeetingEntity b2Meeting = ProjectMeetingEntity.builder()
                .id(5L).projectId(10L).recordingFileId(55L).recordingStatus(MeetingRecordingStatus.COMPLETED).build();
        ProjectFileEntity b2File = ProjectFileEntity.builder()
                .id(55L).projectId(10L).originalName("meeting.mp4").contentType("video/mp4")
                .fileSize(1000L).storageProvider("b2").storageKey("recordings/project-10/meeting.mp4").build();

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
                .id(6L).projectId(10L).recordingFileId(66L).recordingStatus(MeetingRecordingStatus.COMPLETED).build();
        ProjectFileEntity gdFile = ProjectFileEntity.builder()
                .id(66L).projectId(10L).originalName("archived.mp4").contentType("video/mp4")
                .fileSize(2000L).storageProvider("gdrive").storageBucket("googledrive").storageKey("drive-file-id").build();

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
                .id(5L).projectId(10L).recordingFileId(55L).recordingStatus(MeetingRecordingStatus.COMPLETED).build();
        ProjectFileEntity b2File = ProjectFileEntity.builder()
                .id(55L).projectId(10L).originalName("meeting.mp4").contentType("video/mp4")
                .fileSize(1000L).storageProvider("b2").storageKey("recordings/project-10/meeting.mp4").build();

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
                .status(ProjectMeetingStatus.ACTIVE).recordingStatus(MeetingRecordingStatus.IDLE).build();
        when(meetingRepository.findByIdAndProjectId(5L, 10L)).thenReturn(Optional.of(meeting));
        when(egressS3StorageService.isConfigured()).thenReturn(true);
        Instant exp = Instant.now().plusSeconds(1200);
        when(egressS3StorageService.generatePresignedPutUrl(any(), eq("video/webm")))
                .thenReturn(new EgressS3StorageService.PresignedUrlResult("https://b2.example/put-url", exp));

        PresignRecordingUploadResponse presigned = service.presignFallbackUpload(
                10L, 5L, 100L,
                new PresignRecordingUploadRequest("video/webm;codecs=vp8,opus", "demo.webm")
        );

        assertThat(presigned.uploadUrl()).isEqualTo("https://b2.example/put-url");
        assertThat(presigned.contentType()).isEqualTo("video/webm");
        assertThat(presigned.uploadToken()).isNotBlank();

        // 1. Oversize file (>10MB test limit) must be deleted from B2 and return 413
        when(egressS3StorageService.headObject(any()))
                .thenReturn(new EgressS3StorageService.ObjectMetadata(25L * 1024L * 1024L, "video/webm", "etag1"));

        assertThatThrownBy(() -> service.completeFallbackUpload(
                10L, 5L, 100L, new CompleteRecordingUploadRequest(presigned.uploadToken(), 60L)))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(413);
        verify(egressS3StorageService).deleteFile(org.mockito.ArgumentMatchers.startsWith("recordings/10/5/"));

        // 2. Valid file size completes, clears egressId via linkFallbackRecording, and is idempotent on replay
        when(projectFileRepository.findByProjectIdAndStorageKey(eq(10L), any()))
                .thenReturn(Optional.empty());
        when(egressS3StorageService.headObject(any()))
                .thenReturn(new EgressS3StorageService.ObjectMetadata(5L * 1024L * 1024L, "video/webm", "etag2"));
        ProjectFileEntity createdFile = ProjectFileEntity.builder().id(701L).projectId(10L).storageProvider("b2").build();
        when(projectFileRepository.saveAndFlush(any(ProjectFileEntity.class))).thenReturn(createdFile);
        when(meetingRepository.linkFallbackRecording(eq(5L), eq(701L), any())).thenReturn(1);
        when(projectMeetingService.getMeetingById(10L, 5L, 100L))
                .thenReturn(ProjectMeetingResponse.builder().id(5L).recordingFileId(701L).recordingStatus(MeetingRecordingStatus.COMPLETED).build());

        ProjectMeetingResponse completed = service.completeFallbackUpload(
                10L, 5L, 100L, new CompleteRecordingUploadRequest(presigned.uploadToken(), 60L));

        assertThat(completed.recordingFileId()).isEqualTo(701L);
        verify(meetingRepository).linkFallbackRecording(eq(5L), eq(701L), any());

        // 3. Replaying the same uploadToken must NOT insert a duplicate ProjectFileEntity
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

        // First call: B2 object definitively 404 -> MUST NOT create an unlinked ProjectFileEntity.
        // Second call: B2 transient network error -> defers cleanly without creating file or flipping FAILED.
        // Third call: B2 object present -> recovers cleanly to COMPLETED.
        when(egressS3StorageService.headObject("recordings/project-10/m-9.mp4"))
                .thenThrow(new EgressS3StorageService.ObjectNotFoundException("404 Not Found", null))
                .thenThrow(new IOException("Connection reset"))
                .thenReturn(new EgressS3StorageService.ObjectMetadata(5000L, "video/mp4", "etag"));

        service.applyEgressSnapshot(new EgressSnapshot(
                "EG_REC", "room-9", "EGRESS_COMPLETE", "", false,
                "recordings/project-10/m-9.mp4", 5000L, 1000L
        ));
        service.applyEgressSnapshot(new EgressSnapshot(
                "EG_REC", "room-9", "EGRESS_COMPLETE", "", false,
                "recordings/project-10/m-9.mp4", 5000L, 1000L
        ));
        verify(projectFileRepository, never()).saveAndFlush(any());

        when(projectFileRepository.findBySourceEgressId("EG_REC")).thenReturn(Optional.empty());
        when(projectFileRepository.saveAndFlush(any(ProjectFileEntity.class))).thenAnswer(inv -> {
            ProjectFileEntity e = inv.getArgument(0);
            e.setId(909L);
            return e;
        });
        when(meetingRepository.transitionRecordingCompleted(
                eq(9L), eq("EG_REC"), eq(MeetingRecordingStatus.COMPLETED), eq(909L), eq(false), any(), anyCollection()))
                .thenReturn(1);

        service.applyEgressSnapshot(new EgressSnapshot(
                "EG_REC", "room-9", "EGRESS_COMPLETE", "", false,
                "recordings/project-10/m-9.mp4", 5000L, 1000L
        ));

        verify(meetingRepository).transitionRecordingCompleted(
                eq(9L), eq("EG_REC"), eq(MeetingRecordingStatus.COMPLETED), eq(909L), eq(false), any(), anyCollection());
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

        // For an idle meeting, uploadToken expiry MUST exceed the 20-minute presigned PUT URL expiry (+60m grace)
        ProjectMeetingEntity idleMeeting = ProjectMeetingEntity.builder()
                .id(16L).projectId(10L).hostId(100L).title("Large Fallback")
                .recordingStatus(MeetingRecordingStatus.IDLE).build();
        when(meetingRepository.findByIdAndProjectId(16L, 10L)).thenReturn(Optional.of(idleMeeting));
        when(egressS3StorageService.isConfigured()).thenReturn(true);
        Instant putExpiry = Instant.now().plus(Duration.ofMinutes(20));
        when(egressS3StorageService.generatePresignedPutUrl(any(), eq("video/webm")))
                .thenReturn(new EgressS3StorageService.PresignedUrlResult("https://b2.example/put", putExpiry));

        PresignRecordingUploadResponse presigned = service.presignFallbackUpload(
                10L, 16L, 100L, new PresignRecordingUploadRequest("video/webm", "large.webm"));
        var claims = presignedUploadTokenService.verifyToken(presigned.uploadToken(), 10L, 16L, 100L).orElseThrow();
        assertThat(claims.expiresAt()).isAfter(putExpiry.plus(Duration.ofMinutes(55)));
    }
}
