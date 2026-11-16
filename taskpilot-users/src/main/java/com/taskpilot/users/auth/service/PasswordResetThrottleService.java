package com.taskpilot.users.auth.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

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
    private String provider = PROVIDER_MEMORY;

    @Value("${application.security.password-reset.rate-limit.cooldown-seconds:60}")
    private long cooldownSeconds = 60L;

    @Value("${application.security.password-reset.rate-limit.max-per-email-per-hour:5}")
    private int maxPerEmailPerHour = 5;

    @Value("${application.security.password-reset.rate-limit.max-per-ip-per-hour:20}")
    private int maxPerIpPerHour = 20;

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final AuthAttemptStore attemptStore;
    private final Clock clock;

    public PasswordResetThrottleService(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this(redisTemplateProvider, new InMemoryAuthAttemptStore(), Clock.systemUTC());
    }

    @Autowired
    public PasswordResetThrottleService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            AuthAttemptStore attemptStore) {
        this(redisTemplateProvider, attemptStore, Clock.systemUTC());
    }

    public PasswordResetThrottleService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            AuthAttemptStore attemptStore,
            Clock clock) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.attemptStore = attemptStore != null ? attemptStore : new InMemoryAuthAttemptStore();
        this.clock = clock != null ? clock : Clock.systemUTC();
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
        String keyHash = sha256(normalizedEmail);
        if (isRedisProviderEnabled()) {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            if (redisTemplate != null) {
                String key = REDIS_KEY_COOLDOWN_PREFIX + keyHash;
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
            log.warn("Password reset rate-limit provider is redis, but Redis template is unavailable. Falling back to bounded attempt store.");
        }
        return attemptStore.tryAcquireCooldownSeconds(
                "PWD_RESET_CD", keyHash, Duration.ofSeconds(cooldownSeconds), clock.instant());
    }

    private long incrementEmailCounter(String normalizedEmail) {
        String keyHash = sha256(normalizedEmail);
        if (isRedisProviderEnabled()) {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            if (redisTemplate != null) {
                return incrementRedisCounter(redisTemplate, REDIS_KEY_EMAIL_RATE_PREFIX + keyHash);
            }
            log.warn("Password reset rate-limit provider is redis, but Redis template is unavailable. Falling back to bounded attempt store.");
        }
        return attemptStore.incrementAndGet("PWD_RESET_EM", keyHash, WINDOW_DURATION, clock.instant());
    }

    private long incrementIpCounter(String normalizedIp) {
        String keyHash = sha256(normalizedIp);
        if (isRedisProviderEnabled()) {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            if (redisTemplate != null) {
                return incrementRedisCounter(redisTemplate, REDIS_KEY_IP_RATE_PREFIX + keyHash);
            }
            log.warn("Password reset rate-limit provider is redis, but Redis template is unavailable. Falling back to bounded attempt store.");
        }
        return attemptStore.incrementAndGet("PWD_RESET_IP", keyHash, WINDOW_DURATION, clock.instant());
    }

    private long incrementRedisCounter(StringRedisTemplate redisTemplate, String key) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, WINDOW_DURATION);
        }
        return count == null ? 1L : count;
    }

    private static BusinessException tooManyRequests(String message) {
        return new BusinessException(HttpStatus.TOO_MANY_REQUESTS.value(), message);
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeIp(String ip) {
        String normalized = ip == null ? "" : ip.trim();
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private boolean isRedisProviderEnabled() {
        return PROVIDER_REDIS.equalsIgnoreCase(provider);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", ex);
        }
    }
}
