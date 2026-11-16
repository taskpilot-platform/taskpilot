package com.taskpilot.users.auth.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

/**
 * Bounded per-account and per-network attempt throttling applied before expensive BCrypt work
 * and across verification, password setup, password reset, and restore confirmation routes.
 */
@Service
public class AuthAttemptThrottleService {

    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);
    private static final Duration CHALLENGE_WINDOW = Duration.ofHours(1);
    private static final Duration CONSUME_WINDOW = Duration.ofMinutes(15);

    @Value("${application.security.auth-throttle.login-max-per-account-ip:5}")
    private int loginMaxPerAccountIp = 5;

    @Value("${application.security.auth-throttle.login-max-per-ip:100}")
    private int loginMaxPerIp = 100;

    @Value("${application.security.auth-throttle.challenge-cooldown-seconds:60}")
    private long challengeCooldownSeconds = 60L;

    @Value("${application.security.auth-throttle.challenge-max-per-email-hour:5}")
    private int challengeMaxPerEmailHour = 5;

    @Value("${application.security.auth-throttle.challenge-max-per-ip-hour:20}")
    private int challengeMaxPerIpHour = 20;

    @Value("${application.security.auth-throttle.token-consume-max-per-ip:20}")
    private int tokenConsumeMaxPerIp = 20;

    @Value("${application.security.auth-throttle.restore-confirm-max-per-ip:10}")
    private int restoreConfirmMaxPerIp = 10;

    private final AuthAttemptStore attemptStore;
    private final Clock clock;

    @Autowired
    public AuthAttemptThrottleService(AuthAttemptStore attemptStore) {
        this(attemptStore, Clock.systemUTC());
    }

    public AuthAttemptThrottleService(AuthAttemptStore attemptStore, Clock clock) {
        this.attemptStore = attemptStore != null ? attemptStore : new InMemoryAuthAttemptStore();
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public void checkLoginAllowed(String rawEmail, String clientIp) {
        Instant now = clock.instant();
        String accountIpKey = sha256(normalizeEmail(rawEmail) + "|" + normalizeIp(clientIp));
        String ipKey = sha256(normalizeIp(clientIp));

        if (loginMaxPerAccountIp > 0
                && attemptStore.currentCount("LOGIN_ACCT_IP", accountIpKey, now) >= loginMaxPerAccountIp) {
            throw tooManyRequests("Too many failed login attempts. Please try again later.");
        }
        if (loginMaxPerIp > 0
                && attemptStore.currentCount("LOGIN_IP_SPRAY", ipKey, now) >= loginMaxPerIp) {
            throw tooManyRequests("Too many login attempts from this network. Please try again later.");
        }
    }

    public void recordLoginFailure(String rawEmail, String clientIp) {
        Instant now = clock.instant();
        String accountIpKey = sha256(normalizeEmail(rawEmail) + "|" + normalizeIp(clientIp));
        String ipKey = sha256(normalizeIp(clientIp));
        attemptStore.incrementAndGet("LOGIN_ACCT_IP", accountIpKey, LOGIN_WINDOW, now);
        attemptStore.incrementAndGet("LOGIN_IP_SPRAY", ipKey, LOGIN_WINDOW, now);
    }

    public void clearLoginFailures(String rawEmail, String clientIp) {
        String accountIpKey = sha256(normalizeEmail(rawEmail) + "|" + normalizeIp(clientIp));
        attemptStore.clear("LOGIN_ACCT_IP", accountIpKey);
    }

    public void checkAndConsumeChallengeRequest(String action, String rawEmail, String clientIp) {
        Instant now = clock.instant();
        String emailHash = sha256(normalizeEmail(rawEmail));
        String ipHash = sha256(normalizeIp(clientIp));

        if (challengeCooldownSeconds > 0) {
            long waitSec = attemptStore.tryAcquireCooldownSeconds(
                    action + "_CD", emailHash, Duration.ofSeconds(challengeCooldownSeconds), now);
            if (waitSec > 0) {
                throw tooManyRequests("Please wait " + waitSec + " seconds before requesting another email.");
            }
        }
        if (challengeMaxPerEmailHour > 0) {
            long count = attemptStore.incrementAndGet(action + "_EM", emailHash, CHALLENGE_WINDOW, now);
            if (count > challengeMaxPerEmailHour) {
                throw tooManyRequests("Too many requests for this email. Please try again later.");
            }
        }
        if (challengeMaxPerIpHour > 0) {
            long count = attemptStore.incrementAndGet(action + "_IP", ipHash, CHALLENGE_WINDOW, now);
            if (count > challengeMaxPerIpHour) {
                throw tooManyRequests("Too many requests from this network. Please try again later.");
            }
        }
    }

    public void checkAndConsumeTokenAttempt(String action, String clientIp) {
        Instant now = clock.instant();
        String ipHash = sha256(normalizeIp(clientIp));
        int limit = "RESTORE_CONFIRM".equals(action) ? restoreConfirmMaxPerIp : tokenConsumeMaxPerIp;
        if (limit > 0) {
            long count = attemptStore.incrementAndGet(action, ipHash, CONSUME_WINDOW, now);
            if (count > limit) {
                throw tooManyRequests("Too many verification attempts from this network. Please try again later.");
            }
        }
    }

    private static BusinessException tooManyRequests(String message) {
        return new BusinessException(HttpStatus.TOO_MANY_REQUESTS.value(), message);
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeIp(String ip) {
        String trimmed = ip == null ? "" : ip.trim();
        return trimmed.isEmpty() ? "unknown" : trimmed;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
