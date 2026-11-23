package com.taskpilot.infrastructure.config.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Revoked JWT {@code jti} blocklist backed by Redis or bounded in-memory storage.
 * Keys are hashed with SHA-256 so raw bearer strings or identifiers are never stored in memory or Redis.
 */
@Service
@Slf4j
public class JwtTokenBlocklistService {

   private static final String PROVIDER_MEMORY = "memory";
   private static final String PROVIDER_REDIS = "redis";
   private static final String REDIS_KEY_PREFIX = "jwt:blocklist:jti:";

   @Value("${application.security.jwt.blocklist.provider:memory}")
   private String provider;

   private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
   private final Map<String, Instant> revokedHashes = new ConcurrentHashMap<>();

   public JwtTokenBlocklistService(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
      this.redisTemplateProvider = redisTemplateProvider;
   }

   public void revokeJti(String jti, Instant expiresAt) {
      revoke(jti, expiresAt);
   }

   public boolean isJtiRevoked(String jti) {
      return isRevoked(jti);
   }

   public void revoke(String tokenId, Instant expiresAt) {
      if (tokenId == null || tokenId.isBlank() || expiresAt == null) {
         return;
      }
      Instant now = Instant.now();
      if (!expiresAt.isAfter(now)) {
         return;
      }

      String keyHash = sha256(tokenId.trim());
      if (isRedisProviderEnabled()) {
         StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
         if (redisTemplate != null) {
            Duration ttl = Duration.between(now, expiresAt);
            if (!ttl.isNegative() && !ttl.isZero()) {
               redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + keyHash, "1", ttl);
            }
            return;
         }
         log.warn(
               "JWT blocklist provider is set to redis, but Redis template is unavailable. Falling back to in-memory blocklist.");
      }

      evictExpired(now);
      revokedHashes.put(keyHash, expiresAt);
   }

   public boolean isRevoked(String tokenId) {
      if (tokenId == null || tokenId.isBlank()) {
         return false;
      }

      String keyHash = sha256(tokenId.trim());
      if (isRedisProviderEnabled()) {
         StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
         if (redisTemplate != null) {
            Boolean exists = redisTemplate.hasKey(REDIS_KEY_PREFIX + keyHash);
            return Boolean.TRUE.equals(exists);
         }
         log.warn(
               "JWT blocklist provider is set to redis, but Redis template is unavailable. Falling back to in-memory blocklist.");
      }

      Instant now = Instant.now();
      Instant expiresAt = revokedHashes.get(keyHash);
      if (expiresAt == null) {
         return false;
      }
      if (!expiresAt.isAfter(now)) {
         revokedHashes.remove(keyHash, expiresAt);
         return false;
      }
      return true;
   }

   private void evictExpired(Instant now) {
      revokedHashes.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
   }

   private boolean isRedisProviderEnabled() {
      return PROVIDER_REDIS.equalsIgnoreCase(provider);
   }

   private static String sha256(String input) {
      try {
         MessageDigest digest = MessageDigest.getInstance("SHA-256");
         byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
         return HexFormat.of().formatHex(hash);
      } catch (Exception e) {
         throw new IllegalStateException("Failed to hash JWT blocklist key", e);
      }
   }
}
