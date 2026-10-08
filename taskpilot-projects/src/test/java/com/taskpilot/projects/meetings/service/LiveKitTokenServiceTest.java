package com.taskpilot.projects.meetings.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LiveKitTokenServiceTest {

    private LiveKitTokenService liveKitTokenService;
    private final String apiKey = "API2brFXxNZvVJK";
    private final String apiSecret = "3bI1SnnAf1ye0crWH0e7SftfWuvQgOxgnocsMD0aewnM";
    private final String livekitUrl = "wss://taskpilot-collab-m3oqfj4g.livekit.cloud";

    @BeforeEach
    void setUp() {
        liveKitTokenService = new LiveKitTokenService(new ObjectMapper());
        ReflectionTestUtils.setField(liveKitTokenService, "apiKey", apiKey);
        ReflectionTestUtils.setField(liveKitTokenService, "apiSecret", apiSecret);
        ReflectionTestUtils.setField(liveKitTokenService, "livekitUrl", livekitUrl);
    }

    @Test
    void createAccessToken_generatesValidLiveKitJwt() {
        String roomName = "test-room-123";
        String identity = "user_42";
        String displayName = "Alice Dev";
        boolean isHost = true;
        Map<String, Object> metadata = Map.of("email", "alice@example.com", "role", "PM");

        String jwt = liveKitTokenService.createAccessToken(roomName, identity, displayName, isHost, metadata);

        assertNotNull(jwt);
        assertTrue(jwt.split("\\.").length == 3);

        // Verify with JJWT parser using same signing key
        SecretKey key = Keys.hmacShaKeyFor(apiSecret.getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(jwt)
                .getPayload();

        assertEquals(apiKey, claims.getIssuer());
        assertEquals(identity, claims.getSubject());
        assertEquals(displayName, claims.get("name"));

        @SuppressWarnings("unchecked")
        Map<String, Object> video = (Map<String, Object>) claims.get("video");
        assertNotNull(video);
        assertEquals(roomName, video.get("room"));
        assertEquals(true, video.get("roomJoin"));
        assertEquals(true, video.get("canPublish"));
        assertEquals(true, video.get("canSubscribe"));
        assertEquals(true, video.get("canPublishData"));
        assertEquals(true, video.get("roomAdmin"));

        assertNotNull(claims.get("metadata"));
        assertTrue(claims.get("metadata").toString().contains("alice@example.com"));
    }

    @Test
    void createAccessToken_participant_hasNoRoomAdmin() {
        String jwt = liveKitTokenService.createAccessToken("room-1", "user_99", "Bob", false, null);

        SecretKey key = Keys.hmacShaKeyFor(apiSecret.getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(jwt)
                .getPayload();

        @SuppressWarnings("unchecked")
        Map<String, Object> video = (Map<String, Object>) claims.get("video");
        assertNotNull(video);
        assertNull(video.get("roomAdmin"));
        assertEquals(true, video.get("roomJoin"));
    }

    @Test
    void createAccessToken_emptyCredentials_fallsBackGracefully() {
        ReflectionTestUtils.setField(liveKitTokenService, "apiKey", "");
        String token = liveKitTokenService.createAccessToken("room-fallback", "user_1", "Test", false, null);
        assertNotNull(token);
        assertTrue(token.startsWith("mock-livekit-token-"));
    }
}
