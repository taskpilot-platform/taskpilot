package com.taskpilot.infrastructure.config.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileStreamTokenServiceTest {

    private static final String SHARED_SECRET =
            "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";

    private final FileStreamTokenService service = new FileStreamTokenService(SHARED_SECRET);

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

    @Test
    void streamToken_isCryptographicallyRejectedByJwtServiceAndUploadTokenService() {
        JwtTokenBlocklistService blocklist = mock(JwtTokenBlocklistService.class);
        when(blocklist.isRevoked(anyString())).thenReturn(false);
        JwtService jwtService = new JwtService(blocklist);
        ReflectionTestUtils.setField(jwtService, "secretKey", SHARED_SECRET);
        ReflectionTestUtils.setField(jwtService, "jwtExpiration", 3600_000L);

        PresignedUploadTokenService uploadTokenService = new PresignedUploadTokenService(SHARED_SECRET);

        String loginJwt = jwtService.generateToken("alice@taskpilot.io", "USER");
        String streamToken = service.issueToken("alice@taskpilot.io", 10L, 42L).token();
        String uploadToken = uploadTokenService.issueToken(
                10L, 5L, 100L, "recordings/10/5/file.webm", "video/webm", "file.webm", Duration.ofMinutes(10)
        );

        assertThat(jwtService.isTokenValid(loginJwt)).isTrue();
        assertThat(jwtService.isTokenValid(streamToken)).isFalse();
        assertThat(jwtService.isTokenValid(uploadToken)).isFalse();
        assertThat(service.verifyToken(uploadToken, 10L, 42L)).isEmpty();
        assertThat(service.verifyToken(loginJwt, 10L, 42L)).isEmpty();
        assertThat(uploadTokenService.verifyToken(streamToken, 10L, 5L, 100L)).isEmpty();
    }
}
