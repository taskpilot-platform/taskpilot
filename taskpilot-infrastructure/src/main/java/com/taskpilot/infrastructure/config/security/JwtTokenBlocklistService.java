package com.taskpilot.infrastructure.config.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class JwtTokenBlocklistService {

   private static final String PROVIDER_MEMORY = "memory";
   private static final String PROVIDER_REDIS = "redis";
   private static final String REDIS_KEY_PREFIX = "jwt:blocklist:";

   @Value("${application.security.jwt.blocklist.provider:memory}")
   private String provider;

   private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
   private final Map<String, Instant> revokedTokens = new ConcurrentHashMap<>();

   public JwtTokenBlocklistService(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
      this.redisTemplateProvider = redisTemplateProvider;
   }

   public void revoke(String token, Instant expiresAt) {
      if (token == null || token.isBlank() || expiresAt == null) {
         return;
      }
      if (expiresAt.isBefore(Instant.now())) {
         return;
      }

      if (isRedisProviderEnabled()) {
         StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
         if (redisTemplate != null) {
            Duration ttl = Duration.between(Instant.now(), expiresAt);
            if (!ttl.isNegative() && !ttl.isZero()) {
               redisTemplate.opsForValue().set(buildRedisKey(token), "1", ttl);
               return;
            }
            return;
         }

         log.warn(
               "JWT blocklist provider is set to redis, but Redis template is unavailable. Falling back to in-memory blocklist.");
      }

      revokedTokens.put(token, expiresAt);
   }

   public boolean isRevoked(String token) {
      if (token == null || token.isBlank()) {
         return false;
      }

      if (isRedisProviderEnabled()) {
         StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
         if (redisTemplate != null) {
            Boolean exists = redisTemplate.hasKey(buildRedisKey(token));
            return Boolean.TRUE.equals(exists);
         }

         log.warn(
               "JWT blocklist provider is set to redis, but Redis template is unavailable. Falling back to in-memory blocklist.");
      }

      Instant expiresAt = revokedTokens.get(token);
      if (expiresAt == null) {
         return false;
      }

      if (expiresAt.isBefore(Instant.now())) {
         revokedTokens.remove(token);
         return false;
      }
      return true;
   }

   private boolean isRedisProviderEnabled() {
      return PROVIDER_REDIS.equalsIgnoreCase(provider);
   }

   private String buildRedisKey(String token) {
      return REDIS_KEY_PREFIX + sha256(token);
   }

   private String sha256(String input) {
      try {
         MessageDigest digest = MessageDigest.getInstance("SHA-256");
         byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
         return HexFormat.of().formatHex(hash);
      } catch (Exception ex) {
         return Integer.toHexString(input.hashCode());
      }
   }
}