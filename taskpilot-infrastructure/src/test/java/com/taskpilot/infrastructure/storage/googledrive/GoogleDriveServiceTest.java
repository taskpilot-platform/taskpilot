package com.taskpilot.infrastructure.storage.googledrive;

import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleDriveServiceTest {

    @Mock
    private Drive drive;

    @Test
    void isConfigured_returnsTrueWhenFolderIdProvided() {
        GoogleDriveService service = new GoogleDriveService(drive, "test-folder-123");
        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    void isConfigured_returnsFalseWhenFolderIdBlankOrNull() {
        GoogleDriveService service1 = new GoogleDriveService(drive, "");
        assertThat(service1.isConfigured()).isFalse();

        GoogleDriveService service2 = new GoogleDriveService(drive, null);
        assertThat(service2.isConfigured()).isFalse();
    }

    @Test
    void uploadFile_throwsIllegalArgumentExceptionWhenFileNullOrEmpty() {
        GoogleDriveService service = new GoogleDriveService(drive, "test-folder");

        assertThatThrownBy(() -> service.uploadFile(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot upload empty file");

        MockMultipartFile emptyFile = new MockMultipartFile("file", "test.txt", "text/plain", new byte[0]);
        assertThatThrownBy(() -> service.uploadFile(emptyFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot upload empty file");
    }

    @Test
    void uploadPrivateStreamWithMd5_computesExactMd5AndNeverCreatesPublicPermission() throws Exception {
        byte[] payload = "livekit-egress-mp4-stream-bytes".getBytes(StandardCharsets.UTF_8);
        String expectedMd5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(payload));

        Drive.Files filesApi = mock(Drive.Files.class);
        Drive.Files.Create createReq = mock(Drive.Files.Create.class);
        when(drive.files()).thenReturn(filesApi);
        when(filesApi.create(any(File.class), any(AbstractInputStreamContent.class))).thenAnswer(inv -> {
            AbstractInputStreamContent content = inv.getArgument(1);
            // Simulate MediaHttpUploader draining the stream chunk-by-chunk
            content.getInputStream().readAllBytes();
            return createReq;
        });
        when(createReq.setFields(anyString())).thenReturn(createReq);
        when(createReq.execute()).thenReturn(new File()
                .setId("drive-private-99")
                .setName("meeting.mp4")
                .setMimeType("video/mp4")
                .setSize((long) payload.length)
                .setMd5Checksum(expectedMd5));

        GoogleDriveService service = new GoogleDriveService(drive, "folder-123");
        GoogleDriveService.StreamedUploadResult result = service.uploadPrivateStreamWithMd5(
                new ByteArrayInputStream(payload),
                "meeting.mp4",
                "video/mp4",
                payload.length,
                null,
                4 * 1024 * 1024
        );

        assertThat(result.computedMd5Hex()).isEqualTo(expectedMd5);
        assertThat(result.streamedBytes()).isEqualTo(payload.length);
        assertThat(result.driveFile().getFileId()).isEqualTo("drive-private-99");
        assertThat(result.driveFile().getMd5Checksum()).isEqualTo(expectedMd5);
        // Privacy invariant: MUST NOT call drive.permissions() to create public link
        verify(drive, never()).permissions();
    }
}
