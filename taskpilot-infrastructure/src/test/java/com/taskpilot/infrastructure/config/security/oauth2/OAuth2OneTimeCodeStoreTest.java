package com.taskpilot.infrastructure.config.security.oauth2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OAuth2OneTimeCodeStore Tests")
class OAuth2OneTimeCodeStoreTest {

    private OAuth2Properties properties;
    private InMemoryOAuth2OneTimeCodeStore store;

    @BeforeEach
    void setUp() {
        properties = new OAuth2Properties();
        properties.setCodeTtlSeconds(60);
        store = new InMemoryOAuth2OneTimeCodeStore(properties);
    }

    @Test
    @DisplayName("Should generate, consume, and enforce single-use semantics")
    void testSingleUseCode() {
        Long userId = 123L;

        String code = store.generateAndStore(userId);
        assertNotNull(code);
        assertFalse(code.isBlank());

        // First consumption succeeds
        Optional<Long> retrieved = store.consume(code);
        assertTrue(retrieved.isPresent());
        assertEquals(userId, retrieved.get());

        // Second consumption fails (replay attack prevented)
        Optional<Long> replay = store.consume(code);
        assertTrue(replay.isEmpty());
    }

    @Test
    @DisplayName("Non-existent code returns empty Optional")
    void testNonExistentCode() {
        Optional<Long> retrieved = store.consume("non-existent-code");
        assertTrue(retrieved.isEmpty());
    }

    @Test
    @DisplayName("Expired code is rejected")
    void testExpiredCode() {
        Instant initialInstant = Instant.parse("2026-01-01T00:00:00Z");
        // Custom clock that we can advance
        TestClock testClock = new TestClock(initialInstant);

        properties.setCodeTtlSeconds(15);
        InMemoryOAuth2OneTimeCodeStore clockStore = new InMemoryOAuth2OneTimeCodeStore(properties, testClock);

        String code = clockStore.generateAndStore(456L);

        // Advance clock past TTL (15s + 5s = 20s)
        testClock.setInstant(initialInstant.plusSeconds(20));

        Optional<Long> retrieved = clockStore.consume(code);
        assertTrue(retrieved.isEmpty());
    }

    private static class TestClock extends Clock {
        private Instant currentInstant;

        TestClock(Instant initial) {
            this.currentInstant = initial;
        }

        void setInstant(Instant instant) {
            this.currentInstant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }
    }
}

