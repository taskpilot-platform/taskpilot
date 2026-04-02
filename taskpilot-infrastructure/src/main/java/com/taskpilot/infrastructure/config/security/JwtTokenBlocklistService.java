package com.taskpilot.infrastructure.config.security;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class JwtTokenBlocklistService {

   private final Map<String, Instant> revokedTokens = new ConcurrentHashMap<>();

   public void revoke(String token, Instant expiresAt) {
      if (token == null || token.isBlank() || expiresAt == null) {
         return;
      }
      if (expiresAt.isBefore(Instant.now())) {
         return;
      }
      revokedTokens.put(token, expiresAt);
   }

   public boolean isRevoked(String token) {
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
}