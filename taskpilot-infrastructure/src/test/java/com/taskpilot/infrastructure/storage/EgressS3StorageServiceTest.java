package com.taskpilot.infrastructure.storage;

import org.junit.jupiter.api.Test;

import java.time.Duration;

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
}
