package com.taskpilot.infrastructure.config.security;

import com.taskpilot.contracts.user.dto.AccountAuthStateDto;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileStreamTokenServiceTest {

    private static final String SHARED_SECRET =
            "synthetic-stream-fixture-C7r9V2m5Q8x4N6p1H3k0W7s9";
    private static final long USER_ID = 100L;

    private final AccountAuthStatePort accounts = mock(AccountAuthStatePort.class);
    private final FileStreamTokenService service = new FileStreamTokenService(SHARED_SECRET, accounts);

    @BeforeEach
    void activeVerifiedAccount() {
        when(accounts.findById(USER_ID)).thenReturn(Optional.of(account("ACTIVE", true, 7L)));
    }

    @Test
    void issueAndVerifyToken_succeedsForExactProjectAndFile() throws Exception {
        FileStreamTokenService.IssuedStreamToken issued = service.issueToken(USER_ID, 10L, 42L, Duration.ofMinutes(10));

        Optional<FileStreamTokenService.StreamTokenClaims> claims = service.verifyToken(issued.token(), 10L, 42L);
        assertThat(claims).isPresent();
        assertThat(claims.orElseThrow().userId()).isEqualTo(USER_ID);
        assertThat(claims.orElseThrow().projectId()).isEqualTo(10L);
        assertThat(claims.orElseThrow().fileId()).isEqualTo(42L);
        assertThat(claims.orElseThrow().expiresAt()).isAfter(Instant.now());

        Claims signedClaims = Jwts.parser().verifyWith(streamSigningKey()).build()
                .parseSignedClaims(issued.token()).getPayload();
        assertThat(signedClaims.getSubject()).isEqualTo("100");
        assertThat(signedClaims.get("token_version", Number.class).longValue()).isEqualTo(7L);
    }

    @Test
    void verifyToken_rejectsDifferentProjectOrFileAndMalformedInput() {
        String token = service.issueToken(USER_ID, 10L, 42L).token();

        assertThat(service.verifyToken(token, 11L, 42L)).isEmpty();
        assertThat(service.verifyToken(token, 10L, 99L)).isEmpty();
        assertThat(service.verifyToken("not-a-jwt", 10L, 42L)).isEmpty();
        assertThat(service.verifyToken(null, 10L, 42L)).isEmpty();
        assertThat(service.verifyToken(token, null, 42L)).isEmpty();
        assertThat(service.verifyToken(token, 10L, null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEACTIVATED", "PENDING_VERIFICATION", "DELETED"})
    void issueToken_rejectsInactiveAccount(String lifecycle) {
        when(accounts.findById(USER_ID)).thenReturn(Optional.of(account(lifecycle, true, 7L)));

        assertThatThrownBy(() -> service.issueToken(USER_ID, 10L, 42L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issueToken_rejectsUnverifiedOrMissingAccount() {
        when(accounts.findById(USER_ID)).thenReturn(Optional.of(account("ACTIVE", false, 7L)));
        assertThatThrownBy(() -> service.issueToken(USER_ID, 10L, 42L))
                .isInstanceOf(IllegalArgumentException.class);

        when(accounts.findById(USER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.issueToken(USER_ID, 10L, 42L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issueToken_rejectsInvalidScopeAndUserId() {
        assertThatThrownBy(() -> service.issueToken(0L, 10L, 42L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.issueToken(USER_ID, null, 42L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.issueToken(USER_ID, 10L, -1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEACTIVATED", "PENDING_VERIFICATION", "DELETED"})
    void verifyToken_rejectsAccountDeactivatedAfterIssue(String lifecycle) {
        String token = service.issueToken(USER_ID, 10L, 42L).token();
        when(accounts.findById(USER_ID)).thenReturn(Optional.of(account(lifecycle, true, 7L)));

        assertThat(service.verifyToken(token, 10L, 42L)).isEmpty();
    }

    @Test
    void verifyToken_rejectsVerificationRevokedAfterIssue() {
        String token = service.issueToken(USER_ID, 10L, 42L).token();
        when(accounts.findById(USER_ID)).thenReturn(Optional.of(account("ACTIVE", false, 7L)));

        assertThat(service.verifyToken(token, 10L, 42L)).isEmpty();
    }

    @Test
    void verifyToken_rejectsTokenVersionChangedAfterIssue() {
        String token = service.issueToken(USER_ID, 10L, 42L).token();
        when(accounts.findById(USER_ID)).thenReturn(Optional.of(account("ACTIVE", true, 8L)));

        assertThat(service.verifyToken(token, 10L, 42L)).isEmpty();
    }

    @Test
    void verifyToken_rejectsMissingAccountAndAccountLookupFailure() {
        String token = service.issueToken(USER_ID, 10L, 42L).token();
        when(accounts.findById(USER_ID)).thenReturn(Optional.empty());
        assertThat(service.verifyToken(token, 10L, 42L)).isEmpty();

        when(accounts.findById(USER_ID)).thenThrow(new IllegalStateException("synthetic lookup failure"));
        assertThat(service.verifyToken(token, 10L, 42L)).isEmpty();
    }

    @Test
    void verifyToken_keepsStableIdentityWhenEmailChanges() {
        String token = service.issueToken(USER_ID, 10L, 42L).token();
        when(accounts.findById(USER_ID)).thenReturn(Optional.of(new AccountAuthStateDto(
                USER_ID, "renamed@taskpilot.invalid", "USER", "ACTIVE", true, 7L)));

        assertThat(service.verifyToken(token, 10L, 42L)).isPresent()
                .get().extracting(FileStreamTokenService.StreamTokenClaims::userId).isEqualTo(USER_ID);
    }

    @Test
    void verifyToken_rejectsExpiredAndIncompleteOrLegacyClaims() throws Exception {
        assertThat(service.verifyToken(signedToken("100", 7L, Instant.now().minusSeconds(60)), 10L, 42L)).isEmpty();
        assertThat(service.verifyToken(signedToken("100", null, Instant.now().plusSeconds(60)), 10L, 42L)).isEmpty();
        assertThat(service.verifyToken(signedToken("alice@taskpilot.invalid", 7L, Instant.now().plusSeconds(60)), 10L, 42L)).isEmpty();
        assertThat(service.verifyToken(signedToken("0", 7L, Instant.now().plusSeconds(60)), 10L, 42L)).isEmpty();
        assertThat(service.verifyToken(signedToken("100", -1L, Instant.now().plusSeconds(60)), 10L, 42L)).isEmpty();
    }

    @Test
    void streamToken_isCryptographicallyRejectedByJwtServiceAndUploadTokenService() {
        JwtTokenBlocklistService blocklist = mock(JwtTokenBlocklistService.class);
        JwtProperties properties = new JwtProperties();
        properties.setSecretKey(SHARED_SECRET);
        properties.setExpiration(3600_000L);
        JwtService jwtService = new JwtService(properties, blocklist);
        PresignedUploadTokenService uploadTokenService = new PresignedUploadTokenService(SHARED_SECRET);

        String loginJwt = jwtService.generateToken(USER_ID, 7L, java.util.UUID.randomUUID(), "USER");
        String streamToken = service.issueToken(USER_ID, 10L, 42L).token();
        String uploadToken = uploadTokenService.issueToken(
                10L, 5L, USER_ID, "recordings/10/5/file.webm", "video/webm", "file.webm", Duration.ofMinutes(10)
        );

        assertThat(jwtService.isTokenValid(loginJwt)).isTrue();
        assertThat(jwtService.isTokenValid(streamToken)).isFalse();
        assertThat(jwtService.isTokenValid(uploadToken)).isFalse();
        assertThat(service.verifyToken(uploadToken, 10L, 42L)).isEmpty();
        assertThat(service.verifyToken(loginJwt, 10L, 42L)).isEmpty();
        assertThat(uploadTokenService.verifyToken(streamToken, 10L, 5L, USER_ID)).isEmpty();
    }

    private static AccountAuthStateDto account(String lifecycle, boolean verified, long version) {
        return new AccountAuthStateDto(USER_ID, "alice@taskpilot.invalid", "USER", lifecycle, verified, version);
    }

    private static String signedToken(String subject, Long version, Instant expiresAt) throws Exception {
        return Jwts.builder().subject(subject).claim("type", "FILE_STREAM")
                .claim("projectId", 10L).claim("fileId", 42L).claim("token_version", version)
                .issuedAt(Date.from(Instant.now().minusSeconds(120)))
                .expiration(Date.from(expiresAt)).signWith(streamSigningKey()).compact();
    }

    private static SecretKey streamSigningKey() throws Exception {
        return Keys.hmacShaKeyFor(MessageDigest.getInstance("SHA-256")
                .digest((SHARED_SECRET + ":taskpilot-file-stream-v1").getBytes(StandardCharsets.UTF_8)));
    }
}
