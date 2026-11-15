package com.taskpilot.infrastructure.config.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.function.Function;

@Service
public class JwtService {

    private final JwtProperties jwtProperties;
    private final JwtTokenBlocklistService tokenBlocklistService;
    private final SecretKey signingKey;

    public JwtService(JwtProperties jwtProperties, JwtTokenBlocklistService tokenBlocklistService) {
        this.jwtProperties = jwtProperties;
        this.tokenBlocklistService = tokenBlocklistService;
        jwtProperties.validate();
        this.signingKey = Keys.hmacShaKeyFor(jwtProperties.getSecretKey().getBytes(StandardCharsets.UTF_8));
    }

    public long getJwtExpiration() {
        return jwtProperties.getExpiration();
    }

    public String generateToken(String email, String role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(email)
                .claim("role", role)
                .issuedAt(new Date(now))
                .expiration(new Date(now + jwtProperties.getExpiration()))
                .signWith(signingKey)
                .compact();
    }

    public String extractEmail(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String extractRole(String token) {
        return extractClaim(token, claims -> claims.get("role", String.class));
    }

    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isTokenValid(String token) {
        try {
            Claims claims = extractAllClaims(token);
            Date expiration = claims.getExpiration();
            if (expiration == null || expiration.before(new Date())) {
                return false;
            }
            // Reject purpose-scoped tokens (e.g. FILE_STREAM, B2_RECORDING_UPLOAD) and tokens without a user role
            if (claims.get("type") != null) {
                return false;
            }
            String role = claims.get("role", String.class);
            if (role == null || role.isBlank()) {
                return false;
            }
            return !tokenBlocklistService.isRevoked(token);
        } catch (Exception e) {
            return false;
        }
    }

    public void revokeToken(String token) {
        try {
            Date expiration = extractClaim(token, Claims::getExpiration);
            tokenBlocklistService.revoke(token, Instant.ofEpochMilli(expiration.getTime()));
        } catch (Exception e) {
            // Invalid/expired tokens cannot be added safely to blocklist
        }
    }
}
