package com.taskpilot.projects.files.service;

import com.taskpilot.infrastructure.storage.EgressS3StorageService;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveFileDto;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveService;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.enums.FileArchiveStatus;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordingArchiveWorkerTest {

    @Mock
    private ProjectFileRepository projectFileRepository;
    @Mock
    private ProjectMemberRepository projectMemberRepository;
    @Mock
    private ProjectSecurityService projectSecurityService;
    @Mock
    private EgressS3StorageService egressS3StorageService;
    @Mock
    private GoogleDriveService googleDriveService;
    @Mock
    private PlatformTransactionManager transactionManager;

    private RecordingArchiveWorker worker;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        worker = new RecordingArchiveWorker(
                projectFileRepository,
                projectMemberRepository,
                projectSecurityService,
                egressS3StorageService,
                googleDriveService,
                transactionManager,
                true,
                3L,
                5,
                4 * 1024 * 1024,
                30L
        );
    }

    @Test
    void archiveSingleFile_verifiesMd5AndSizeCommitsDbBeforeDeletingFromB2() throws Exception {
        ProjectFileEntity file = ProjectFileEntity.builder()
                .id(77L).projectId(10L).originalName("meeting-5.mp4").contentType("video/mp4")
                .storageProvider("b2").storageKey("recordings/project-10/meeting-5.mp4")
                .archiveStatus(FileArchiveStatus.ARCHIVE_PENDING).build();

        byte[] videoBytes = "verified-video-payload".getBytes(StandardCharsets.UTF_8);
        String md5 = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4";

        when(projectFileRepository.claimForArchiving(eq(77L), anyCollection(), any())).thenReturn(1);
        when(egressS3StorageService.headObject("recordings/project-10/meeting-5.mp4"))
                .thenReturn(new EgressS3StorageService.ObjectMetadata(videoBytes.length, "video/mp4", "multipart-etag-1"));
        when(egressS3StorageService.downloadFile("recordings/project-10/meeting-5.mp4"))
                .thenReturn(new ByteArrayInputStream(videoBytes));
        when(googleDriveService.uploadPrivateStreamWithMd5(
                any(), eq("meeting-5.mp4"), eq("video/mp4"), eq((long) videoBytes.length), isNull(), anyInt()))
                .thenReturn(new GoogleDriveService.StreamedUploadResult(
                        GoogleDriveFileDto.builder()
                                .fileId("drive-id-77")
                                .name("meeting-5.mp4")
                                .size((long) videoBytes.length)
                                .md5Checksum(md5)
                                .build(),
                        md5,
                        videoBytes.length
                ));
        when(projectFileRepository.completeArchiveToGoogleDrive(
                eq(77L), eq("drive-id-77"), eq((long) videoBytes.length), eq(md5), any()))
                .thenReturn(1);

        boolean ok = worker.archiveSingleFile(file, List.of(FileArchiveStatus.ARCHIVE_PENDING));

        assertThat(ok).isTrue();
        // Verify strict ordering: DB commits ARCHIVED pointing to Drive BEFORE deleting from B2
        InOrder order = inOrder(projectFileRepository, egressS3StorageService);
        order.verify(projectFileRepository).completeArchiveToGoogleDrive(
                eq(77L), eq("drive-id-77"), eq((long) videoBytes.length), eq(md5), any());
        order.verify(egressS3StorageService).deleteFileOrThrow("recordings/project-10/meeting-5.mp4");
    }

    @Test
    void archiveSingleFile_md5Mismatch_deletesDriveOrphanKeepsB2AndMarksFailed() throws Exception {
        ProjectFileEntity file = ProjectFileEntity.builder()
                .id(78L).projectId(10L).originalName("corrupt.mp4").contentType("video/mp4")
                .storageProvider("b2").storageKey("recordings/project-10/corrupt.mp4")
                .archiveStatus(FileArchiveStatus.ARCHIVE_PENDING).build();

        when(projectFileRepository.claimForArchiving(eq(78L), anyCollection(), any())).thenReturn(1);
        when(egressS3StorageService.headObject("recordings/project-10/corrupt.mp4"))
                .thenReturn(new EgressS3StorageService.ObjectMetadata(100L, "video/mp4", "etag"));
        when(egressS3StorageService.downloadFile("recordings/project-10/corrupt.mp4"))
                .thenReturn(new ByteArrayInputStream(new byte[100]));
        when(googleDriveService.uploadPrivateStreamWithMd5(any(), any(), any(), eq(100L), isNull(), anyInt()))
                .thenReturn(new GoogleDriveService.StreamedUploadResult(
                        GoogleDriveFileDto.builder()
                                .fileId("drive-corrupt-78")
                                .size(100L)
                                .md5Checksum("00000000000000000000000000000000")
                                .build(),
                        "ffffffffffffffffffffffffffffffff",
                        100L
                ));

        boolean ok = worker.archiveSingleFile(file, List.of(FileArchiveStatus.ARCHIVE_PENDING));

        assertThat(ok).isFalse();
        verify(googleDriveService).deleteFile("drive-corrupt-78");
        verify(egressS3StorageService, never()).deleteFileOrThrow(any());
        verify(egressS3StorageService, never()).deleteFile(any());
        verify(projectFileRepository).markArchiveFailed(eq(78L), contains("Integrity verification failed"), any());
    }

    @Test
    void archiveSingleFile_skipsWhenAnotherWorkerAlreadyClaimedRow() throws Exception {
        ProjectFileEntity file = ProjectFileEntity.builder()
                .id(79L).projectId(10L).storageProvider("b2").storageKey("recordings/k.mp4").build();
        when(projectFileRepository.claimForArchiving(eq(79L), anyCollection(), any())).thenReturn(0);

        boolean ok = worker.archiveSingleFile(file, List.of(FileArchiveStatus.ARCHIVE_PENDING));

        assertThat(ok).isFalse();
        verify(egressS3StorageService, never()).headObject(any());
        verify(googleDriveService, never()).uploadPrivateStreamWithMd5(any(), any(), any(), anyLong(), any(), anyInt());
    }

    @Test
    void processEligibleArchives_respectsZeroAfterDaysAndRecoversStaleLocks() {
        RecordingArchiveWorker immediateWorker = new RecordingArchiveWorker(
                projectFileRepository, projectMemberRepository, projectSecurityService,
                egressS3StorageService, googleDriveService, transactionManager,
                true, 0L, 5, 4 * 1024 * 1024, 30L
        );
        when(egressS3StorageService.isConfigured()).thenReturn(true);
        when(googleDriveService.isConfigured()).thenReturn(true);
        when(projectFileRepository.resetStaleArchiving(any(), any())).thenReturn(1);
        when(projectFileRepository.findEligibleForArchive(anyCollection(), eq(5), any(Instant.class), any(Instant.class), any()))
                .thenReturn(List.of());

        int count = immediateWorker.processEligibleArchives(10);

        assertThat(count).isZero();
        verify(projectFileRepository).resetStaleArchiving(any(), any());
        verify(projectFileRepository).findEligibleForArchive(anyCollection(), eq(5), any(Instant.class), any(Instant.class), any());
    }

    @Test
    void archiveSingleFile_b2DeleteFailsAfterDbCommit_keepsCommittedDriveFileAndReturnsTrue() throws Exception {
        ProjectFileEntity file = ProjectFileEntity.builder()
                .id(80L).projectId(10L).originalName("meeting-80.mp4").contentType("video/mp4")
                .storageProvider("b2").storageKey("recordings/project-10/meeting-80.mp4")
                .archiveStatus(FileArchiveStatus.ARCHIVE_PENDING).build();

        byte[] payload = "safe-video-bytes".getBytes(StandardCharsets.UTF_8);
        String md5 = "1234567890abcdef1234567890abcdef";

        when(projectFileRepository.claimForArchiving(eq(80L), anyCollection(), any())).thenReturn(1);
        when(egressS3StorageService.headObject("recordings/project-10/meeting-80.mp4"))
                .thenReturn(new EgressS3StorageService.ObjectMetadata(payload.length, "video/mp4", "etag"));
        when(egressS3StorageService.downloadFile("recordings/project-10/meeting-80.mp4"))
                .thenReturn(new ByteArrayInputStream(payload));
        when(googleDriveService.uploadPrivateStreamWithMd5(any(), any(), any(), eq((long) payload.length), isNull(), anyInt()))
                .thenReturn(new GoogleDriveService.StreamedUploadResult(
                        GoogleDriveFileDto.builder()
                                .fileId("drive-live-80")
                                .size((long) payload.length)
                                .md5Checksum(md5)
                                .build(),
                        md5,
                        payload.length
                ));
        when(projectFileRepository.completeArchiveToGoogleDrive(
                eq(80L), eq("drive-live-80"), eq((long) payload.length), eq(md5), any()))
                .thenReturn(1);
        doThrow(new java.io.IOException("Transient B2 network error"))
                .when(egressS3StorageService).deleteFileOrThrow("recordings/project-10/meeting-80.mp4");

        boolean ok = worker.archiveSingleFile(file, List.of(FileArchiveStatus.ARCHIVE_PENDING));

        assertThat(ok).isTrue();
        verify(googleDriveService, never()).deleteFile("drive-live-80");
        verify(projectFileRepository, never()).markArchiveFailed(anyLong(), any(), any());
    }

    @Test
    void isRetryBackoffElapsed_appliesExponentialCooldownForArchiveFailed() {
        Instant now = Instant.now();
        // retryCount=3 -> 15 * 2^(3-1) = 60 minutes backoff
        ProjectFileEntity tooSoon = ProjectFileEntity.builder()
                .archiveStatus(FileArchiveStatus.ARCHIVE_FAILED)
                .archiveRetryCount(3)
                .updatedAt(now.minusSeconds(30 * 60))
                .build();
        ProjectFileEntity ready = ProjectFileEntity.builder()
                .archiveStatus(FileArchiveStatus.ARCHIVE_FAILED)
                .archiveRetryCount(3)
                .updatedAt(now.minusSeconds(65 * 60))
                .build();

        assertThat(worker.isRetryBackoffElapsed(tooSoon, now)).isFalse();
        assertThat(worker.isRetryBackoffElapsed(ready, now)).isTrue();
    }
}
