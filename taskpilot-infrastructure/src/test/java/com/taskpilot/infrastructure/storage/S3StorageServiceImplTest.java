package com.taskpilot.infrastructure.storage;

import com.taskpilot.infrastructure.storage.range.ByteRange;
import com.taskpilot.infrastructure.storage.range.StorageDeleteOutcome;
import com.taskpilot.infrastructure.storage.range.StorageDeleteResult;
import com.taskpilot.infrastructure.storage.range.StorageObjectStat;
import com.taskpilot.infrastructure.storage.range.StorageReadRequest;
import com.taskpilot.infrastructure.storage.range.StorageReadResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class S3StorageServiceImplTest {

    @Mock
    private S3Client s3Client;

    private S3StorageServiceImpl storageService;

    private final String bucketName = "test-bucket";
    private final String publicUrlPrefix = "https://example.com/storage/v1/object/public/test-bucket";

    @BeforeEach
    void setUp() {
        storageService = new S3StorageServiceImpl(
                "https://dummy-endpoint.s3.amazonaws.com",
                "dummyAccessKey",
                "dummySecretKey",
                "ap-southeast-1",
                bucketName,
                publicUrlPrefix
        );
        ReflectionTestUtilsHelper.setS3Client(storageService, s3Client);
    }

    @Test
    @DisplayName("Verify uploadFile with explicit bucket 'documents' sets layout projects/{id}/documents/{uuid}-{filename} and returns relative key")
    void testUploadFileToDocumentsBucket() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "architecture.pdf", "application/pdf", "dummy pdf content".getBytes()
        );

        String resultKey = storageService.uploadFile(file, "projects/100/documents", "documents");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));

        PutObjectRequest request = captor.getValue();
        assertThat(request.bucket()).isEqualTo("documents");
        assertThat(request.key()).startsWith("projects/100/documents/");
        assertThat(request.key()).endsWith("-architecture.pdf");
        assertThat(request.contentType()).isEqualTo("application/pdf");
        assertThat(resultKey).isEqualTo(request.key());
    }

    @Test
    @DisplayName("Verify uploadFileAtKey uploads to predetermined key")
    void testUploadFileAtPredeterminedKey() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "spec.pdf", "application/pdf", "spec bytes".getBytes()
        );
        String plannedKey = storageService.planObjectKey("projects/10/files", "spec.pdf");

        String storedKey = storageService.uploadFileAtKey(file, "documents", plannedKey);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().bucket()).isEqualTo("documents");
        assertThat(captor.getValue().key()).isEqualTo(plannedKey);
        assertThat(storedKey).isEqualTo(plannedKey);
    }

    @Test
    @DisplayName("Verify uploadFile with explicit bucket 'avatars' returns public URL")
    void testUploadFileToAvatarsBucket() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", "dummy image".getBytes()
        );

        String resultUrl = storageService.uploadFile(file, "avatars", "avatars");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));

        PutObjectRequest request = captor.getValue();
        assertThat(request.bucket()).isEqualTo("avatars");
        assertThat(request.key()).startsWith("avatars/");
        assertThat(resultUrl).startsWith(publicUrlPrefix);
        assertThat(resultUrl).endsWith("-avatar.png");
    }

    @Test
    @DisplayName("Verify downloadFile with explicit bucket downloads from that bucket")
    void testDownloadFileWithExplicitBucket() throws IOException {
        String key = "projects/100/documents/doc.pdf";
        ResponseInputStream<GetObjectResponse> mockStream = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                new ByteArrayInputStream("dummy file content".getBytes())
        );

        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(mockStream);

        InputStream result = storageService.downloadFile("documents", key);
        assertThat(result).isNotNull();

        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(captor.capture());

        GetObjectRequest request = captor.getValue();
        assertThat(request.bucket()).isEqualTo("documents");
        assertThat(request.key()).isEqualTo(key);
        assertThat(request.range()).isNull();
    }

    @Test
    @DisplayName("Verify readObject passes exact byte range to S3 GetObjectRequest")
    void testReadObjectWithByteRange() throws IOException {
        String key = "projects/100/files/video.mp4";
        var responseBuilder = GetObjectResponse.builder()
                        .contentLength(6L)
                        .contentRange("bytes 2-7/20")
                        .contentType("video/mp4")
                        .eTag("\"etag-123\"");
        responseBuilder.sdkHttpResponse(software.amazon.awssdk.http.SdkHttpResponse.builder().statusCode(206).build());
        ResponseInputStream<GetObjectResponse> mockStream = new ResponseInputStream<>(
                responseBuilder.build(),
                new ByteArrayInputStream("234567".getBytes())
        );
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(mockStream);

        StorageReadResult result = storageService.readObject(
                new StorageReadRequest("documents", key, Optional.of(new ByteRange(2, 7)))
        );

        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(captor.capture());
        assertThat(captor.getValue().range()).isEqualTo("bytes=2-7");
        assertThat(result.totalBytes()).isEqualTo(20L);
        assertThat(result.responseBytes()).isEqualTo(6L);
        assertThat(result.etag()).contains("\"etag-123\"");
        assertThat(result.servedRange()).contains(new ByteRange(2, 7));
    }

    @Test
    @DisplayName("Verify statObject uses S3 HeadObjectRequest")
    void testStatObject() throws IOException {
        Instant modified = Instant.parse("2026-11-16T00:00:00Z");
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(
                HeadObjectResponse.builder()
                        .contentLength(4096L)
                        .contentType("application/pdf")
                        .eTag("\"head-etag\"")
                        .lastModified(modified)
                        .build()
        );

        Optional<StorageObjectStat> stat = storageService.statObject("documents", "projects/1/files/a.pdf");

        assertThat(stat).isPresent();
        assertThat(stat.get().totalBytes()).isEqualTo(4096L);
        assertThat(stat.get().etag()).contains("\"head-etag\"");
        assertThat(stat.get().lastModified()).contains(modified);
    }

    @Test
    @DisplayName("Verify downloadFile throws IOException when S3 fails")
    void testDownloadFileErrorHandling() {
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(new RuntimeException("S3 connection error"));

        assertThatThrownBy(() -> storageService.downloadFile("documents", "invalid-key.pdf"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("S3 connection error");
    }

    @Test
    @DisplayName("Verify deleteFile with explicit bucket properly invokes S3 deleteObject")
    void testDeleteFileWithExplicitBucket() {
        String key = "projects/100/documents/obsolete.pdf";

        storageService.deleteFile("documents", key);

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("documents");
        assertThat(captor.getValue().key()).isEqualTo(key);
    }

    @Test
    @DisplayName("Verify deleteObject treats 404 as success and 403 as auth failure")
    void testDeleteObjectOutcomes() {
        when(s3Client.deleteObject(argThat((DeleteObjectRequest r) -> r != null && "missing".equals(r.key()))))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());
        when(s3Client.deleteObject(argThat((DeleteObjectRequest r) -> r != null && "denied".equals(r.key()))))
                .thenThrow(S3Exception.builder().statusCode(403).message("Access Denied").build());

        StorageDeleteResult missingResult = storageService.deleteObject("documents", "missing");
        StorageDeleteResult deniedResult = storageService.deleteObject("documents", "denied");

        assertThat(missingResult.isSuccess()).isTrue();
        assertThat(missingResult.outcome()).isEqualTo(StorageDeleteOutcome.NOT_FOUND);
        assertThat(deniedResult.isSuccess()).isFalse();
        assertThat(deniedResult.outcome()).isEqualTo(StorageDeleteOutcome.AUTH_FAILURE);
    }

    private static final class ReflectionTestUtilsHelper {
        static void setS3Client(S3StorageServiceImpl target, S3Client s3Client) {
            org.springframework.test.util.ReflectionTestUtils.setField(target, "s3Client", s3Client);
        }
    }
}
