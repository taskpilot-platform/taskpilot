package com.taskpilot.users.auth.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthAttemptThrottleServiceTest {

    @Test
    void blocksLoginAfterBoundedAccountFailuresAndRecoversAfterWindowExpires() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-16T10:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };

        AuthAttemptThrottleService throttle = new AuthAttemptThrottleService(new InMemoryAuthAttemptStore(), clock);
        for (int i = 0; i < 5; i++) {
            throttle.checkLoginAllowed("victim@example.com", "203.0.113.10");
            throttle.recordLoginFailure("victim@example.com", "203.0.113.10");
        }

        assertThatThrownBy(() -> throttle.checkLoginAllowed("victim@example.com", "203.0.113.10"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Too many failed login attempts");

        now.set(now.get().plusSeconds(16 * 60));
        assertThatNoException().isThrownBy(() -> throttle.checkLoginAllowed("victim@example.com", "203.0.113.10"));
    }

    @Test
    void enforcesCooldownAndIpSprayCapsAcrossChallengeAndConsumeEndpoints() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-16T10:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        AuthAttemptThrottleService throttle = new AuthAttemptThrottleService(new InMemoryAuthAttemptStore(), clock);

        throttle.checkAndConsumeChallengeRequest("VERIFY_RESEND", "user@example.com", "198.51.100.1");
        assertThatThrownBy(() -> throttle.checkAndConsumeChallengeRequest("VERIFY_RESEND", "user@example.com", "198.51.100.1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Please wait");

        for (int i = 0; i < 10; i++) {
            throttle.checkAndConsumeTokenAttempt("RESTORE_CONFIRM", "198.51.100.2");
        }
        assertThatThrownBy(() -> throttle.checkAndConsumeTokenAttempt("RESTORE_CONFIRM", "198.51.100.2"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Too many verification attempts");
    }
}
