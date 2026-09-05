package com.taskpilot.infrastructure.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

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
        ReflectionTestUtils.setField(storageService, "s3Client", s3Client);
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
}
