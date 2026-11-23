package com.taskpilot.projects.meetings.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.MeetingRecordingEntity;
import com.taskpilot.projects.common.entity.MeetingRecordingEntity.Coverage;
import com.taskpilot.projects.common.entity.MeetingRecordingEntity.Origin;
import com.taskpilot.projects.common.entity.MeetingRecordingEntity.State;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.entity.ProjectMeetingEntity;
import com.taskpilot.projects.common.repository.MeetingRecordingRepository;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectMeetingRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.files.service.ProjectFileService;
import com.taskpilot.projects.files.dto.ProjectFileDownloadResult;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientMeetingRecordingServiceTest {
    private final MeetingRecordingRepository recordings = mock(MeetingRecordingRepository.class);
    private final ProjectMeetingRepository meetings = mock(ProjectMeetingRepository.class);
    private final ProjectFileRepository files = mock(ProjectFileRepository.class);
    private final ProjectFileService download = mock(ProjectFileService.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final ProjectMeetingEntity meeting = ProjectMeetingEntity.builder().id(4L).projectId(2L).hostId(7L).build();
    private final UUID id = UUID.randomUUID();
    private final String checksum = "a".repeat(64);
    private final ClientMeetingRecordingService service = new ClientMeetingRecordingService(recordings, meetings, files,
            mock(ProjectMemberRepository.class), mock(ProjectSecurityService.class), download, new TransactionTemplate(tx));

    @BeforeEach
    void arrange() {
        when(tx.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(meetings.findForUpdate(4L, 2L)).thenReturn(Optional.of(meeting));
    }

    @Test
    void aForeignUploaderCannotAttachALegitimateProjectFile() {
        when(recordings.findById(id)).thenReturn(Optional.of(recording(State.UPLOADING)));
        when(files.findByIdAndProjectId(9L, 2L)).thenReturn(Optional.of(ProjectFileEntity.builder().id(9L).uploaderId(8L).build()));

        var denied = assertThrows(BusinessException.class,
                () -> service.finalizeRecording(2L, 4L, 7L, id, 9L, 1000L, 50, "video/webm", checksum, 4));
        assertEquals(403, denied.getStatus());
        assertNull(meeting.getRecordingFileId());
        verifyNoInteractions(download);
    }

    @Test
    void aRepeatedFinalizationReturnsTheCommittedReceiptButDoesNotAcceptADifferentFile() {
        var saved = recording(State.READY);
        saved.setProjectFileId(9L);
        saved.setDurationMs(1000L);
        saved.setByteLength(50L);
        saved.setMimeType("video/webm");
        saved.setChecksum(checksum);
        when(recordings.findById(id)).thenReturn(Optional.of(saved));

        var result = service.finalizeRecording(2L, 4L, 7L, id, 9L, 1000L, 50, "video/webm", checksum, 3);
        assertEquals(State.READY, result.state());
        assertEquals(9L, result.fileId());
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.finalizeRecording(2L, 4L, 7L, id, 10L, 1000L, 50, "video/webm", checksum, 3)).getStatus());
        verifyNoInteractions(files, download);
    }

    @Test
    void memberHistoryContainsReadyFilesAndOmitsAnotherRecordersPendingClip() {
        var pending = recording(State.UPLOADING);
        var ready = recording(State.READY);
        ready.setProjectFileId(9L);
        when(meetings.findByIdAndProjectId(4L, 2L)).thenReturn(Optional.of(meeting));
        when(recordings.findByMeetingIdAndProjectIdOrderByCreatedAtDesc(4L, 2L)).thenReturn(List.of(pending, ready));

        var result = service.list(2L, 4L, 8L);
        assertEquals(1, result.size());
        assertEquals(9L, result.getFirst().fileId());
    }

    @Test
    void onlyProviderVerifiedBytesProduceAReadyFileThatAnotherMemberCanList() throws Exception {
        byte[] bytes = "fixture video bytes".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String fileName = "meeting_4_rec_" + id + ".webm";
        var file = ProjectFileEntity.builder().id(9L).projectId(2L).uploaderId(7L)
                .originalName(fileName).contentType("video/webm").fileSize((long) bytes.length).build();
        var draft = recording(State.UPLOADING);
        when(recordings.findById(id)).thenReturn(Optional.of(draft));
        when(files.findByIdAndProjectId(9L, 2L)).thenReturn(Optional.of(file));
        when(download.downloadFileWithRange(2L, 9L, 7L, null, null)).thenAnswer(call ->
                new ProjectFileDownloadResult(ProjectFileDownloadResult.Status.FULL_200, fileName, "video/webm",
                        bytes.length, bytes.length, null, null, null, new ByteArrayInputStream(bytes)));
        when(recordings.saveAndFlush(draft)).thenReturn(draft);
        when(meetings.findByIdAndProjectId(4L, 2L)).thenReturn(Optional.of(meeting));
        when(recordings.findByMeetingIdAndProjectIdOrderByCreatedAtDesc(4L, 2L)).thenReturn(List.of(draft));

        assertEquals(422, assertThrows(BusinessException.class, () ->
                service.finalizeRecording(2L, 4L, 7L, id, 9L, 1000L, bytes.length, "video/webm", checksum, 4)).getStatus());
        assertNull(meeting.getRecordingFileId());
        assertTrue(service.list(2L, 4L, 8L).isEmpty());

        var ready = service.finalizeRecording(2L, 4L, 7L, id, 9L, 1000L, bytes.length, "video/webm", digest, 4);
        assertEquals(State.READY, ready.state());
        assertEquals(9L, meeting.getRecordingFileId());
        assertEquals(List.of(ready), service.list(2L, 4L, 8L));
    }

    @Test
    void clientBeginRejectsCloudEgressOriginOrCloudCompositeCoverage() {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.begin(2L, 4L, 7L, id, Origin.CLOUD_EGRESS, Coverage.CLOUD_COMPOSITE)).getStatus());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.begin(2L, 4L, 7L, id, Origin.BROWSER, Coverage.CLOUD_COMPOSITE)).getStatus());
        verify(recordings, never()).saveAndFlush(any());
    }

    private MeetingRecordingEntity recording(State state) {
        return MeetingRecordingEntity.builder().id(id).projectId(2L).meetingId(4L).recorderId(7L)
                .state(state).version(4L).origin(Origin.BROWSER).coverage(Coverage.SCREEN_ONLY).createdAt(Instant.now()).build();
    }
}
