package com.taskpilot.infrastructure.storage;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import com.taskpilot.infrastructure.storage.range.ByteRange;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EgressS3StorageServiceTest {

    @Test
    void initAndValidate_throwsFailFastWhenEnabledAndValidateOnStartupAndMissingKeys() {
        EgressS3StorageService service = new EgressS3StorageService(
                true, true, "", "us-east-005", "taskpilot-recordings-2026", "", "", 60
        );

        assertThatThrownBy(service::initAndValidate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("egress.s3.endpoint")
                .hasMessageContaining("egress.s3.access-key")
                .hasMessageContaining("egress.s3.secret-key");
    }

    @Test
    void initAndValidate_allowsStartupWhenValidateOnStartupFalse() {
        EgressS3StorageService service = new EgressS3StorageService(
                true, false, "", "", "", "", "", 60
        );

        service.initAndValidate();
        assertThat(service.isConfigured()).isFalse();
        assertThat(service.missingConfigurationFields()).hasSize(5);
    }

    @Test
    void generatePresignedGetUrl_producesPathStyleSignedBackblazeUrl() {
        EgressS3StorageService service = new EgressS3StorageService(
                true,
                true,
                "https://s3.us-east-005.backblazeb2.com",
                "us-east-005",
                "taskpilot-recordings-2026",
                "testAccessKey",
                "testSecretKey",
                30
        );
        service.initAndValidate();
        try {
            assertThat(service.isConfigured()).isTrue();
            EgressS3StorageService.PresignedUrlResult result = service.generatePresignedGetUrl(
                    "/recordings/project-1/meeting-2.mp4",
                    Duration.ofMinutes(15)
            );
            assertThat(result.url())
                    .startsWith("https://s3.us-east-005.backblazeb2.com/taskpilot-recordings-2026/recordings/project-1/meeting-2.mp4")
                    .contains("X-Amz-Algorithm=AWS4-HMAC-SHA256")
                    .contains("X-Amz-Signature=");
            assertThat(result.expiresAt()).isNotNull();

            EgressS3StorageService.PresignedUrlResult putResult = service.generatePresignedPutUrl(
                    "recordings/10/5/abc.webm",
                    "video/webm",
                    Duration.ofMinutes(20)
            );
            assertThat(putResult.url())
                    .startsWith("https://s3.us-east-005.backblazeb2.com/taskpilot-recordings-2026/recordings/10/5/abc.webm")
                    .contains("X-Amz-Algorithm=AWS4-HMAC-SHA256")
                    .contains("X-Amz-SignedHeaders=content-type%3Bhost");
        } finally {
            service.close();
        }
    }

    @Test
    void readObjectRejectsIgnoredAndMismatchedProviderRangesAndClosesBodies() throws Exception {
        for (var bad : new Object[][] {
                {200, null, 10L}, {206, "bytes 0-3/10", 4L},
                {206, "bytes 2-5/10", 10L}, {206, "bytes 2-5/*", 4L},
                {206, "bytes 2-5/5", 4L}, {206, "not-a-range", 4L}}) {
            S3Client client = mock(S3Client.class);
            var builder = GetObjectResponse.builder().contentRange((String) bad[1]).contentLength((long) bad[2]);
            builder.sdkHttpResponse(SdkHttpResponse.builder().statusCode((int) bad[0]).build());
            var stream = spy(new ResponseInputStream<GetObjectResponse>(builder.build(), new ByteArrayInputStream(new byte[10])));
            when(client.getObject(any(GetObjectRequest.class))).thenReturn(stream);
            EgressS3StorageService service = configuredService(client);
            assertThatThrownBy(() -> service.readObject("recording", Optional.of(new ByteRange(2, 5))))
                    .isInstanceOf(IOException.class);
            verify(stream).abort();
            verify(stream).close();
        }
    }

    @Test
    void readObjectPreservesValidatedExactRangeAndClosesOnConsumerCompletion() throws Exception {
        S3Client client = mock(S3Client.class);
        var builder = GetObjectResponse.builder().contentRange("bytes 2-5/10").contentLength(4L).eTag("\"tag\"");
        builder.sdkHttpResponse(SdkHttpResponse.builder().statusCode(206).build());
        var stream = spy(new ResponseInputStream<GetObjectResponse>(builder.build(), new ByteArrayInputStream(new byte[] {2, 3, 4, 5})));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(stream);
        var result = configuredService(client).readObject("recording", Optional.of(new ByteRange(2, 5)));
        assertThat(result.totalBytes()).isEqualTo(10L);
        assertThat(result.responseBytes()).isEqualTo(4L);
        assertThat(result.servedRange()).contains(new ByteRange(2, 5));
        try (var body = result.body()) { assertThat(body.readAllBytes()).containsExactly(2, 3, 4, 5); }
        verify(stream).close();
    }

    @Test
    void cleanupRefusesUnavailableStorageOrMismatchedBucket() {
        var unavailable = new EgressS3StorageService(false, false, "", "", "", "", "", 60);
        assertThat(unavailable.deleteObject("recordings", "recording").isSuccess()).isFalse();
        S3Client client = mock(S3Client.class);
        assertThat(configuredService(client).deleteObject("other-bucket", "recording").isSuccess()).isFalse();
        verifyNoInteractions(client);
    }

    private static EgressS3StorageService configuredService(S3Client client) {
        var service = new EgressS3StorageService(true, false, "https://b2.invalid", "us-east-1", "recordings", "test", "test", 60);
        ReflectionTestUtils.setField(service, "s3Client", client);
        ReflectionTestUtils.setField(service, "s3Presigner", mock(S3Presigner.class));
        return service;
    }
}
