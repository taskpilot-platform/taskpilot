package com.taskpilot.infrastructure.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

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
        // Construct with dummy values then inject mocked S3Client
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
    @DisplayName("Verify downloadFile extracts key from full public URL and fetches stream")
    void testDownloadFileWithFullUrl() throws IOException {
        String fileUrl = publicUrlPrefix + "/documents/test-doc.pdf";
        ResponseInputStream<GetObjectResponse> mockStream = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                new ByteArrayInputStream("dummy file content".getBytes())
        );

        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(mockStream);

        InputStream result = storageService.downloadFile(fileUrl);
        assertThat(result).isNotNull();

        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(captor.capture());

        GetObjectRequest request = captor.getValue();
        assertThat(request.bucket()).isEqualTo(bucketName);
        assertThat(request.key()).isEqualTo("documents/test-doc.pdf");
    }

    @Test
    @DisplayName("Verify downloadFile handles raw storage key directly")
    void testDownloadFileWithRawKey() throws IOException {
        String key = "documents/my-file.docx";
        ResponseInputStream<GetObjectResponse> mockStream = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                new ByteArrayInputStream("dummy content".getBytes())
        );

        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(mockStream);

        InputStream result = storageService.downloadFile(key);
        assertThat(result).isNotNull();

        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(captor.capture());
        assertThat(captor.getValue().key()).isEqualTo("documents/my-file.docx");
    }

    @Test
    @DisplayName("Verify downloadFile throws IOException when S3 fails")
    void testDownloadFileErrorHandling() {
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(new RuntimeException("S3 connection error"));

        assertThatThrownBy(() -> storageService.downloadFile("invalid-key.pdf"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("S3 connection error");
    }

    @Test
    @DisplayName("Verify deleteFile properly invokes S3 deleteObject")
    void testDeleteFile() {
        String fileUrl = publicUrlPrefix + "/documents/obsolete.pdf";

        storageService.deleteFile(fileUrl);

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(bucketName);
        assertThat(captor.getValue().key()).isEqualTo("documents/obsolete.pdf");
    }
}
