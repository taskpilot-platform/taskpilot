package com.taskpilot.projects.meetings.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveKitEgressClientAdapterTest {

    private static final String API_KEY = "API_TEST_KEY_123";
    private static final String API_SECRET = "super_secret_livekit_signing_key_for_tests_2026";

    private final LiveKitEgressClientAdapter adapter = new LiveKitEgressClientAdapter(
            "wss://demo.livekit.cloud",
            API_KEY,
            API_SECRET,
            "grid"
    );

    @Test
    void receiveWebhook_verifiesRealLiveKitJwtAndSha256Body() throws Exception {
        String rawBody = """
                {
                  "event": "egress_ended",
                  "egressInfo": {
                    "egressId": "EG_REAL_1",
                    "roomName": "tp-proj-1-m-2",
                    "status": "EGRESS_COMPLETE",
                    "backupStorageUsed": false,
                    "fileResults": [
                      {
                        "filename": "recordings/project-1/meeting-2.mp4",
                        "size": "1048576",
                        "duration": "60000000000"
                      }
                    ]
                  }
                }
                """;
        String jwt = signLiveKitWebhook(rawBody, API_KEY, API_SECRET, Instant.now().plusSeconds(300));

        LiveKitEgressClientAdapter.ParsedWebhookEvent parsed = adapter.receiveWebhook(rawBody, jwt);

        assertThat(parsed.eventName()).isEqualTo("egress_ended");
        assertThat(parsed.egressSnapshot()).isNotNull();
        assertThat(parsed.egressSnapshot().egressId()).isEqualTo("EG_REAL_1");
        assertThat(parsed.egressSnapshot().status()).isEqualTo("EGRESS_COMPLETE");
        assertThat(parsed.egressSnapshot().filepath()).isEqualTo("recordings/project-1/meeting-2.mp4");
        assertThat(parsed.egressSnapshot().fileSize()).isEqualTo(1048576L);
    }

    @Test
    void receiveWebhook_rejectsWrongSecretExpiredTokenAndTamperedBodyAsSecurityException() throws Exception {
        String rawBody = "{\"event\":\"egress_ended\",\"egressInfo\":{\"egressId\":\"EG_BAD\"}}";

        String wrongSecretJwt = signLiveKitWebhook(rawBody, API_KEY, "wrong_secret_key_value_for_testing_12345", Instant.now().plusSeconds(300));
        assertThatThrownBy(() -> adapter.receiveWebhook(rawBody, wrongSecretJwt))
                .isInstanceOf(SecurityException.class);

        String expiredJwt = signLiveKitWebhook(rawBody, API_KEY, API_SECRET, Instant.now().minusSeconds(60));
        assertThatThrownBy(() -> adapter.receiveWebhook(rawBody, expiredJwt))
                .isInstanceOf(SecurityException.class);

        String validJwtForOriginal = signLiveKitWebhook(rawBody, API_KEY, API_SECRET, Instant.now().plusSeconds(300));
        assertThatThrownBy(() -> adapter.receiveWebhook(rawBody + " ", validJwtForOriginal))
                .isInstanceOf(SecurityException.class);

        assertThatThrownBy(() -> adapter.receiveWebhook(rawBody, "not-even-a-jwt"))
                .isInstanceOf(SecurityException.class);
    }

    private static String signLiveKitWebhook(String rawBody, String key, String secret, Instant expiresAt) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawBody.getBytes(StandardCharsets.UTF_8));
        String sha256 = Base64.getEncoder().encodeToString(digest);
        return Jwts.builder()
                .issuer(key)
                .claim("sha256", sha256)
                .issuedAt(Date.from(Instant.now().minusSeconds(5)))
                .expiration(Date.from(expiresAt))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
    }
}
