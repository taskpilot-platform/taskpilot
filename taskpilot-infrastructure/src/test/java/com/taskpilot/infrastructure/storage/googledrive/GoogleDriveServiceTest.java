package com.taskpilot.infrastructure.storage.googledrive;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.util.DateTime;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.taskpilot.infrastructure.storage.range.ByteRange;
import com.taskpilot.infrastructure.storage.range.StorageDeleteOutcome;
import com.taskpilot.infrastructure.storage.range.StorageDeleteResult;
import com.taskpilot.infrastructure.storage.range.StorageObjectStat;
import com.taskpilot.infrastructure.storage.range.StorageReadResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.http.AbstractInputStreamContent;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

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
    void uploadFile_neverCreatesPublicPermissionOrExposesLinksAndSetsOperationId() throws IOException {
        Drive.Files files = mock(Drive.Files.class);
        Drive.Files.Create createReq = mock(Drive.Files.Create.class);
        when(drive.files()).thenReturn(files);
        when(files.create(any(File.class), any(InputStreamContent.class))).thenReturn(createReq);
        when(createReq.setFields(anyString())).thenReturn(createReq);

        File returned = new File()
                .setId("drive-file-99")
                .setName("recording.webm")
                .setMimeType("video/webm")
                .setSize(1234L)
                .setMd5Checksum("abc123md5")
                .setModifiedTime(new DateTime(1700000000000L));
        when(createReq.execute()).thenReturn(returned);

        UUID operationId = UUID.randomUUID();
        GoogleDriveService service = new GoogleDriveService(drive, "folder-xyz");
        MockMultipartFile file = new MockMultipartFile("file", "recording.webm", "video/webm", "video-bytes".getBytes());

        GoogleDriveFileDto dto = service.uploadFile(file, null, operationId);

        ArgumentCaptor<File> metaCaptor = ArgumentCaptor.forClass(File.class);
        verify(files).create(metaCaptor.capture(), any(InputStreamContent.class));
        assertThat(metaCaptor.getValue().getParents()).containsExactly("folder-xyz");
        assertThat(metaCaptor.getValue().getAppProperties()).containsEntry("taskpilotOperationId", operationId.toString());

        ArgumentCaptor<String> fieldsCaptor = ArgumentCaptor.forClass(String.class);
        verify(createReq).setFields(fieldsCaptor.capture());
        assertThat(fieldsCaptor.getValue()).doesNotContain("webViewLink").doesNotContain("webContentLink");

        verify(drive, never()).permissions();
        assertThat(dto.getFileId()).isEqualTo("drive-file-99");
        assertThat(dto.getWebViewLink()).isNull();
        assertThat(dto.getWebContentLink()).isNull();
    }

    @Test
    void findFilesByOperationId_queriesAppProperties() throws IOException {
        Drive.Files files = mock(Drive.Files.class);
        Drive.Files.List listReq = mock(Drive.Files.List.class);
        when(drive.files()).thenReturn(files);
        when(files.list()).thenReturn(listReq);
        when(listReq.setQ(anyString())).thenReturn(listReq);
        when(listReq.setPageSize(anyInt())).thenReturn(listReq);
        when(listReq.setFields(anyString())).thenReturn(listReq);

        UUID opId = UUID.randomUUID();
        when(listReq.execute()).thenReturn(new FileList().setFiles(List.of(
                new File().setId("orphan-drive-1").setName("clip.webm").setSize(42L)
        )));

        GoogleDriveService service = new GoogleDriveService(drive, "folder-xyz");
        List<GoogleDriveFileDto> found = service.findFilesByOperationId(opId);

        ArgumentCaptor<String> qCaptor = ArgumentCaptor.forClass(String.class);
        verify(listReq).setQ(qCaptor.capture());
        assertThat(qCaptor.getValue()).contains("taskpilotOperationId").contains(opId.toString());
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getFileId()).isEqualTo("orphan-drive-1");
    }

    @Test
    void readObject_passesExactRangeAndDisconnectsOnClose() throws IOException {
        Drive.Files files = mock(Drive.Files.class);
        Drive.Files.Get getReq = mock(Drive.Files.Get.class);
        HttpHeaders reqHeaders = new HttpHeaders();
        HttpResponse httpResponse = mock(HttpResponse.class);
        HttpHeaders respHeaders = new HttpHeaders()
                .setContentRange("bytes 2-5/10")
                .setContentLength(4L)
                .setETag("\"drive-etag\"");

        when(drive.files()).thenReturn(files);
        when(files.get("drive-1")).thenReturn(getReq);
        when(getReq.getRequestHeaders()).thenReturn(reqHeaders);
        when(getReq.executeMedia()).thenReturn(httpResponse);
        when(httpResponse.getStatusCode()).thenReturn(206);
        when(httpResponse.getHeaders()).thenReturn(respHeaders);
        when(httpResponse.getContentType()).thenReturn("video/webm");
        when(httpResponse.getContent()).thenReturn(new ByteArrayInputStream("2345".getBytes(StandardCharsets.UTF_8)));

        GoogleDriveService service = new GoogleDriveService(drive, "folder-xyz");
        StorageReadResult result = service.readObject("drive-1", Optional.of(new ByteRange(2, 5)));

        assertThat(reqHeaders.getRange()).isEqualTo("bytes=2-5");
        assertThat(result.totalBytes()).isEqualTo(10L);
        assertThat(result.responseBytes()).isEqualTo(4L);
        assertThat(result.etag()).contains("\"drive-etag\"");
        assertThat(new String(result.body().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("2345");

        result.body().close();
        verify(httpResponse).disconnect();
    }

    @Test
    void statObject_returnsMd5EtagAndModifiedTime() throws IOException {
        Drive.Files files = mock(Drive.Files.class);
        Drive.Files.Get getReq = mock(Drive.Files.Get.class);
        when(drive.files()).thenReturn(files);
        when(files.get("drive-1")).thenReturn(getReq);
        when(getReq.setFields(anyString())).thenReturn(getReq);
        when(getReq.execute()).thenReturn(new File()
                .setId("drive-1")
                .setSize(100L)
                .setMimeType("video/mp4")
                .setMd5Checksum("deadbeef")
                .setModifiedTime(new DateTime(1700000000000L)));

        GoogleDriveService service = new GoogleDriveService(drive, "folder-xyz");
        Optional<StorageObjectStat> stat = service.statObject("drive-1");

        assertThat(stat).isPresent();
        assertThat(stat.get().totalBytes()).isEqualTo(100L);
        assertThat(stat.get().etag()).contains("\"deadbeef\"");
        assertThat(stat.get().lastModified()).isPresent();
    }

    @Test
    void deleteObject_handles404AsIdempotentSuccessAnd403AsAuthFailure() throws IOException {
        Drive.Files files = mock(Drive.Files.class);
        Drive.Files.Delete del404 = mock(Drive.Files.Delete.class);
        Drive.Files.Delete del403 = mock(Drive.Files.Delete.class);
        when(drive.files()).thenReturn(files);
        when(files.delete("missing")).thenReturn(del404);
        when(files.delete("forbidden")).thenReturn(del403);

        HttpResponseException.Builder b404 = new HttpResponseException.Builder(404, "Not Found", new HttpHeaders());
        when(del404.execute()).thenThrow(new GoogleJsonResponseException(b404, new GoogleJsonError()));

        HttpResponseException.Builder b403 = new HttpResponseException.Builder(403, "Forbidden", new HttpHeaders());
        when(del403.execute()).thenThrow(new GoogleJsonResponseException(b403, new GoogleJsonError()));

        GoogleDriveService service = new GoogleDriveService(drive, "folder-xyz");
        StorageDeleteResult r404 = service.deleteObject("missing");
        StorageDeleteResult r403 = service.deleteObject("forbidden");

        assertThat(r404.isSuccess()).isTrue();
        assertThat(r404.outcome()).isEqualTo(StorageDeleteOutcome.NOT_FOUND);
        assertThat(r403.isSuccess()).isFalse();
        assertThat(r403.outcome()).isEqualTo(StorageDeleteOutcome.AUTH_FAILURE);
    }

    @Test
    void uploadPrivateStreamWithMd5_computesExactMd5AndNeverCreatesPublicPermission() throws Exception {
        byte[] payload = "livekit-egress-mp4-stream-bytes".getBytes(StandardCharsets.UTF_8);
        String expectedMd5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(payload));

        Drive.Files filesApi = mock(Drive.Files.class);
        Drive.Files.Create createReq = mock(Drive.Files.Create.class);
        MediaHttpUploader uploader = mock(MediaHttpUploader.class);
        when(drive.files()).thenReturn(filesApi);
        when(filesApi.create(any(File.class), any(AbstractInputStreamContent.class))).thenAnswer(inv -> {
            AbstractInputStreamContent content = inv.getArgument(1);
            // Simulate MediaHttpUploader draining the stream chunk-by-chunk
            content.getInputStream().readAllBytes();
            return createReq;
        });
        when(createReq.setFields(anyString())).thenReturn(createReq);
        when(createReq.getMediaHttpUploader()).thenReturn(uploader);
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
        // Resumable streaming configuration invariant: never buffer entire file via direct upload
        verify(uploader).setDirectUploadEnabled(false);
        verify(uploader).setChunkSize(4 * 1024 * 1024);
        // Privacy invariant: MUST NOT call drive.permissions() to create public link
        verify(drive, never()).permissions();
    }

    @Test
    void normalizeChunkSize_alignsTo256KbMultiplesAndRequiresClientSecret() {
        assertThat(GoogleDriveService.normalizeChunkSize(100)).isEqualTo(MediaHttpUploader.MINIMUM_CHUNK_SIZE);
        assertThat(GoogleDriveService.normalizeChunkSize(300 * 1024)).isEqualTo(MediaHttpUploader.MINIMUM_CHUNK_SIZE);
        assertThat(GoogleDriveService.normalizeChunkSize(0)).isEqualTo(4 * 1024 * 1024);

        GoogleDriveService missingSecret = new GoogleDriveService(drive, "folder-123", "cid", "", "rtoken");
        assertThat(missingSecret.isConfigured()).isFalse();
        GoogleDriveService complete = new GoogleDriveService(drive, "folder-123", "cid", "csecret", "rtoken");
        assertThat(complete.isConfigured()).isTrue();
    }
}
