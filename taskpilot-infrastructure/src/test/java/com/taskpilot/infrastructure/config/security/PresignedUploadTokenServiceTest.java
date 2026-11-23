package com.taskpilot.infrastructure.config.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PresignedUploadTokenServiceTest {

    private final PresignedUploadTokenService service = new PresignedUploadTokenService(
            "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970"
    );

    @Test
    void issueAndVerify_succeedsOnlyForExactProjectMeetingUserAndPrefix() {
        String key = "recordings/10/5/abc-123.webm";
        String token = service.issueToken(10L, 5L, 100L, key, "video/webm", "meeting.webm", Duration.ofMinutes(20));

        Optional<PresignedUploadTokenService.UploadTokenClaims> claims = service.verifyToken(token, 10L, 5L, 100L);
        assertThat(claims).isPresent();
        assertThat(claims.get().objectKey()).isEqualTo(key);
        assertThat(claims.get().contentType()).isEqualTo("video/webm");

        assertThat(service.verifyToken(token, 11L, 5L, 100L)).isEmpty();
        assertThat(service.verifyToken(token, 10L, 6L, 100L)).isEmpty();
        assertThat(service.verifyToken(token, 10L, 5L, 101L)).isEmpty();
    }

    @Test
    void verify_rejectsPrefixEscapeEvenIfSigned() {
        String badKey = "recordings/99/5/stolen.webm";
        String token = service.issueToken(10L, 5L, 100L, badKey, "video/webm", "stolen.webm", Duration.ofMinutes(15));

        assertThat(service.verifyToken(token, 10L, 5L, 100L)).isEmpty();
    }
}
