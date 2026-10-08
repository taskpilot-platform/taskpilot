package com.taskpilot.projects.meetings.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveKitTokenService {

    @Value("${livekit.url:wss://taskpilot-collab-m3oqfj4g.livekit.cloud}")
    private String livekitUrl;

    @Value("${livekit.api-key:}")
    private String apiKey;

    @Value("${livekit.api-secret:}")
    private String apiSecret;

    private final ObjectMapper objectMapper;

    // Default token TTL: 12 hours in milliseconds
    private static final long TOKEN_TTL_MS = 12 * 60 * 60 * 1000L;

    public String getLivekitUrl() {
        return livekitUrl;
    }

    /**
     * Generates a signed LiveKit Access Token JWT compliant with LiveKit WebRTC protocol.
     *
     * @param roomName    the unique LiveKit room name
     * @param identity    unique participant identity (e.g. user_123)
     * @param displayName participant human-readable name
     * @param isHost      whether participant has roomAdmin privileges
     * @param metadataMap optional key-value metadata to embed
     * @return signed JWT token string
     */
    public String createAccessToken(String roomName,
                                    String identity,
                                    String displayName,
                                    boolean isHost,
                                    Map<String, Object> metadataMap) {
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
            log.warn("LiveKit API Key or Secret not configured. Falling back to development mock token.");
            return "mock-livekit-token-" + roomName + "-" + identity;
        }

        long now = System.currentTimeMillis();
        Date issuedAt = new Date(now);
        Date expiration = new Date(now + TOKEN_TTL_MS);

        Map<String, Object> videoGrants = new HashMap<>();
        videoGrants.put("room", roomName);
        videoGrants.put("roomJoin", true);
        videoGrants.put("canPublish", true);
        videoGrants.put("canSubscribe", true);
        videoGrants.put("canPublishData", true);
        if (isHost) {
            videoGrants.put("roomAdmin", true);
            videoGrants.put("roomCreate", true);
            videoGrants.put("roomList", true);
            videoGrants.put("roomRecord", true);
        }

        String metadataJson = "{}";
        if (metadataMap != null && !metadataMap.isEmpty()) {
            try {
                metadataJson = objectMapper.writeValueAsString(metadataMap);
            } catch (Exception e) {
                log.warn("Failed to serialize LiveKit participant metadata: {}", e.getMessage());
            }
        }

        SecretKey signingKey = Keys.hmacShaKeyFor(apiSecret.getBytes(StandardCharsets.UTF_8));

        return Jwts.builder()
                .issuer(apiKey)
                .subject(identity)
                .claim("name", displayName)
                .claim("video", videoGrants)
                .claim("metadata", metadataJson)
                .issuedAt(issuedAt)
                .notBefore(issuedAt)
                .expiration(expiration)
                .signWith(signingKey)
                .compact();
    }
}
