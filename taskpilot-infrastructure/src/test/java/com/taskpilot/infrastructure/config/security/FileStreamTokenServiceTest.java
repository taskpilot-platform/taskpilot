package com.taskpilot.infrastructure.config.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FileStreamTokenServiceTest {

    private final FileStreamTokenService service = new FileStreamTokenService(
            "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970"
    );

    @Test
    void issueAndVerifyToken_succeedsForExactProjectAndFile() {
        FileStreamTokenService.IssuedStreamToken issued = service.issueToken("alice@taskpilot.io", 10L, 42L, Duration.ofMinutes(10));

        Optional<FileStreamTokenService.StreamTokenClaims> claims = service.verifyToken(issued.token(), 10L, 42L);
        assertThat(claims).isPresent();
        assertThat(claims.get().userEmail()).isEqualTo("alice@taskpilot.io");
        assertThat(claims.get().projectId()).isEqualTo(10L);
        assertThat(claims.get().fileId()).isEqualTo(42L);
    }

    @Test
    void verifyToken_rejectsDifferentProjectOrFile() {
        FileStreamTokenService.IssuedStreamToken issued = service.issueToken("alice@taskpilot.io", 10L, 42L);

        assertThat(service.verifyToken(issued.token(), 11L, 42L)).isEmpty();
        assertThat(service.verifyToken(issued.token(), 10L, 99L)).isEmpty();
        assertThat(service.verifyToken("not-a-jwt", 10L, 42L)).isEmpty();
    }
}
