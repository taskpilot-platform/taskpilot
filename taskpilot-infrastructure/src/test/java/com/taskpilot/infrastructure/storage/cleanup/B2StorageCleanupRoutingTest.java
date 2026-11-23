package com.taskpilot.infrastructure.storage.cleanup;

import com.taskpilot.contracts.storage.StorageCleanupJobSummary;
import com.taskpilot.contracts.storage.StorageObjectLocator;
import com.taskpilot.contracts.storage.StorageProviderKind;
import com.taskpilot.infrastructure.storage.EgressS3StorageService;
import com.taskpilot.infrastructure.storage.StorageService;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveService;
import com.taskpilot.infrastructure.storage.range.StorageDeleteResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class B2StorageCleanupRoutingTest {
    @Test
    void b2DeletionRoutesOnlyToB2AndUnavailableProviderRemainsRetryable() {
        StorageService s3 = mock(StorageService.class);
        GoogleDriveService drive = mock(GoogleDriveService.class);
        var service = new StorageCleanupService(mock(StorageCleanupJobRepository.class), mock(JdbcTemplate.class),
                s3, drive, null, 6, 30L, 120L);
        var job = mock(StorageCleanupJobSummary.class);
        when(job.provider()).thenReturn(StorageProviderKind.B2);
        assertThat(service.executeProviderDelete(job).isSuccess()).isFalse();
        EgressS3StorageService b2 = mock(EgressS3StorageService.class);
        service.setEgressS3StorageService(b2);
        when(b2.isConfigured()).thenReturn(true);
        when(job.bucket()).thenReturn("recordings");
        when(job.objectKey()).thenReturn("meeting.mp4");
        when(b2.deleteObject("recordings", "meeting.mp4")).thenReturn(StorageDeleteResult.deleted());
        assertThat(service.executeProviderDelete(job).isSuccess()).isTrue();
        verify(b2).deleteObject("recordings", "meeting.mp4");
        verifyNoInteractions(s3, drive);
    }

    @Test
    void locatorsHonorB2AndPreserveLegacyDriveBuckets() {
        assertThat(StorageObjectLocator.of("b2", "recordings", "meeting").provider()).isEqualTo(StorageProviderKind.B2);
        assertThat(StorageObjectLocator.of("s3", "googledrive", "drive-id").provider()).isEqualTo(StorageProviderKind.GOOGLE_DRIVE);
        assertThat(StorageObjectLocator.of("gdrive", "googledrive", "drive-id").provider()).isEqualTo(StorageProviderKind.GOOGLE_DRIVE);
        assertThat(StorageObjectLocator.of(null, "documents", "file").provider()).isEqualTo(StorageProviderKind.S3);
    }
}
