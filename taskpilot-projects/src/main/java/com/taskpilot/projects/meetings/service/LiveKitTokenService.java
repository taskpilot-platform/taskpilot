package com.taskpilot.projects.meetings.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.infrastructure.exception.BusinessException;
import java.net.URI;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
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

    private static final long TOKEN_TTL_MS = 5 * 60 * 1000L;

    public String getLivekitUrl() {
        return livekitUrl;
    }

    /**
     * Generates a signed LiveKit Access Token JWT compliant with LiveKit WebRTC protocol.
     *
     * @param roomName    the unique LiveKit room name
     * @param identity    unique participant identity (e.g. user_123)
     * @param displayName participant human-readable name
     * @param isHost      TaskPilot host role, with the same media grants as a member
     * @param metadataMap optional key-value metadata to embed
     * @return signed JWT token string
     */
    public String createAccessToken(String roomName,
                                    String identity,
                                    String displayName,
                                    boolean isHost,
                                    Map<String, Object> metadataMap) {
        Map<String, Object> videoGrants = new HashMap<>();
        videoGrants.put("room", roomName);
        videoGrants.put("roomJoin", true);
        videoGrants.put("canPublish", true);
        videoGrants.put("canSubscribe", true);
        videoGrants.put("canPublishData", true);
        String metadataJson = "{}";
        if (metadataMap != null && !metadataMap.isEmpty()) {
            try {
                metadataJson = objectMapper.writeValueAsString(metadataMap);
            } catch (Exception e) {
                log.warn("Failed to serialize LiveKit participant metadata: {}", e.getMessage());
            }
        }

        return signedToken(identity, displayName, videoGrants, metadataJson, TOKEN_TTL_MS);
    }

    public String createRoomServiceToken(String roomName, boolean create) {
        Map<String, Object> grants = create
                ? Map.of("roomCreate", true)
                : Map.of("roomAdmin", true, "room", roomName);
        return signedToken("taskpilot-room-service", "TaskPilot", grants, "{}", 30_000);
    }

    public boolean verifiesWebhook(String authorization, byte[] body) {
        if (authorization == null || apiKey == null || apiKey.isBlank() || apiSecret == null
                || apiSecret.getBytes(StandardCharsets.UTF_8).length < 32) return false;
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        if (token.length() > 8192) return false;
        try {
            SecretKey key = Keys.hmacShaKeyFor(apiSecret.getBytes(StandardCharsets.UTF_8));
            var jwt = Jwts.parser().verifyWith(key).requireIssuer(apiKey).clockSkewSeconds(15)
                    .build().parseSignedClaims(token);
            var claims = jwt.getPayload();
            String digest = claims.get("sha256", String.class);
            if (!"HS256".equals(jwt.getHeader().getAlgorithm()) || claims.getExpiration() == null || digest == null) {
                return false;
            }
            return MessageDigest.isEqual(Base64.getDecoder().decode(digest),
                    MessageDigest.getInstance("SHA-256").digest(body));
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String signedToken(String identity, String displayName, Map<String, Object> grants,
                               String metadata, long ttlMs) {
        int keyLength = apiSecret != null ? apiSecret.getBytes(StandardCharsets.UTF_8).length : 0;
        URI url;
        try {
            url = URI.create(livekitUrl);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BusinessException(503, "LiveKit is unavailable");
        }
        if (apiKey == null || apiKey.isBlank() || keyLength < 32 || url.getHost() == null
                || url.getUserInfo() != null || !("wss".equals(url.getScheme()) || "ws".equals(url.getScheme()))) {
            throw new BusinessException(503, "LiveKit is unavailable");
        }

        long now = System.currentTimeMillis();
        Date issuedAt = new Date(now);
        SecretKey signingKey = Keys.hmacShaKeyFor(apiSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .issuer(apiKey)
                .subject(identity)
                .claim("name", displayName)
                .claim("video", grants)
                .claim("metadata", metadata)
                .issuedAt(issuedAt)
                .notBefore(issuedAt)
                .expiration(new Date(now + ttlMs))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }
}
