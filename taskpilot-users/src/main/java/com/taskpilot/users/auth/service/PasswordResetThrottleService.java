package com.taskpilot.users.auth.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class PasswordResetThrottleService {
    private static final String PROVIDER_MEMORY = "memory";
    private static final String PROVIDER_REDIS = "redis";

    private static final Duration WINDOW_DURATION = Duration.ofHours(1);
    private static final String REDIS_KEY_COOLDOWN_PREFIX = "pwdreset:cooldown:email:";
    private static final String REDIS_KEY_EMAIL_RATE_PREFIX = "pwdreset:rate:email:";
    private static final String REDIS_KEY_IP_RATE_PREFIX = "pwdreset:rate:ip:";

    @Value("${application.security.password-reset.rate-limit.provider:${application.security.jwt.blocklist.provider:memory}}")
    private String provider;

    @Value("${application.security.password-reset.rate-limit.cooldown-seconds:60}")
    private long cooldownSeconds;

    @Value("${application.security.password-reset.rate-limit.max-per-email-per-hour:5}")
    private int maxPerEmailPerHour;

    @Value("${application.security.password-reset.rate-limit.max-per-ip-per-hour:20}")
    private int maxPerIpPerHour;

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final Map<String, Instant> emailCooldowns = new ConcurrentHashMap<>();
    private final Map<String, FixedWindowCounter> emailCounters = new ConcurrentHashMap<>();
    private final Map<String, FixedWindowCounter> ipCounters = new ConcurrentHashMap<>();

    public PasswordResetThrottleService(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplateProvider = redisTemplateProvider;
    }

    public void checkAndConsume(String email, String clientIp) {
        String normalizedEmail = normalizeEmail(email);
        String normalizedIp = normalizeIp(clientIp);

        long cooldownRemainingSeconds = checkEmailCooldown(normalizedEmail);
        if (cooldownRemainingSeconds > 0) {
            throw tooManyRequests("Please wait " + cooldownRemainingSeconds
                    + " seconds before requesting another reset email.");
        }

        if (maxPerEmailPerHour > 0) {
            long emailRequests = incrementEmailCounter(normalizedEmail);
            if (emailRequests > maxPerEmailPerHour) {
                throw tooManyRequests("Too many password reset requests for this email. Please try again later.");
            }
        }

        if (maxPerIpPerHour > 0) {
            long ipRequests = incrementIpCounter(normalizedIp);
            if (ipRequests > maxPerIpPerHour) {
                throw tooManyRequests("Too many password reset requests from this network. Please try again later.");
            }
        }
    }

    private long checkEmailCooldown(String normalizedEmail) {
        if (cooldownSeconds <= 0) {
            return 0;
        }

        if (isRedisProviderEnabled()) {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            if (redisTemplate != null) {
                String key = REDIS_KEY_COOLDOWN_PREFIX + sha256(normalizedEmail);
                Boolean created = redisTemplate.opsForValue().setIfAbsent(key, "1",
                        Duration.ofSeconds(cooldownSeconds));
                if (Boolean.TRUE.equals(created)) {
                    return 0;
                }
                Long ttl = redisTemplate.getExpire(key);
                if (ttl != null && ttl > 0) {
                    return ttl;
                }
                return cooldownSeconds;
            }
            log.warn(
                    "Password reset rate-limit provider is redis, but Redis template is unavailable. Falling back to in-memory rate-limit.");
        }

        return checkMemoryCooldown(normalizedEmail);
    }

    private long checkMemoryCooldown(String normalizedEmail) {
        Instant now = Instant.now();
        Instant expiresAt = emailCooldowns.get(normalizedEmail);
        if (expiresAt != null && expiresAt.isAfter(now)) {
            return Math.max(1L, Duration.between(now, expiresAt).toSeconds());
        }
        emailCooldowns.put(normalizedEmail, now.plusSeconds(cooldownSeconds));
        return 0;
    }

    private long incrementEmailCounter(String normalizedEmail) {
        String key = sha256(normalizedEmail);
        if (isRedisProviderEnabled()) {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            if (redisTemplate != null) {
                return incrementRedisCounter(redisTemplate, REDIS_KEY_EMAIL_RATE_PREFIX + key);
            }
            log.warn(
                    "Password reset rate-limit provider is redis, but Redis template is unavailable. Falling back to in-memory rate-limit.");
        }

        return incrementMemoryCounter(emailCounters, key);
    }

    private long incrementIpCounter(String normalizedIp) {
        String key = sha256(normalizedIp);
        if (isRedisProviderEnabled()) {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            if (redisTemplate != null) {
                return incrementRedisCounter(redisTemplate, REDIS_KEY_IP_RATE_PREFIX + key);
            }
            log.warn(
                    "Password reset rate-limit provider is redis, but Redis template is unavailable. Falling back to in-memory rate-limit.");
        }

        return incrementMemoryCounter(ipCounters, key);
    }

    private long incrementRedisCounter(StringRedisTemplate redisTemplate, String key) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, WINDOW_DURATION);
        }
        return count == null ? 1L : count;
    }

    private long incrementMemoryCounter(Map<String, FixedWindowCounter> counters, String key) {
        Instant now = Instant.now();
        FixedWindowCounter updatedCounter = counters.compute(key, (k, current) -> {
            if (current == null || current.expiresAt().isBefore(now)) {
                return new FixedWindowCounter(1, now.plus(WINDOW_DURATION));
            }
            return current.increment();
        });
        return updatedCounter.count();
    }

    private BusinessException tooManyRequests(String message) {
        return new BusinessException(HttpStatus.TOO_MANY_REQUESTS.value(), message);
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private String normalizeIp(String ip) {
        String normalized = ip == null ? "" : ip.trim();
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private boolean isRedisProviderEnabled() {
        return PROVIDER_REDIS.equalsIgnoreCase(provider);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", ex);
        }
    }

    private record FixedWindowCounter(long count, Instant expiresAt) {
        private FixedWindowCounter increment() {
            return new FixedWindowCounter(count + 1, expiresAt);
        }
    }
}
