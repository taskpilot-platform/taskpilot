package com.taskpilot.infrastructure.config.security.oauth2;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class InMemoryOAuth2OneTimeCodeStore implements OAuth2OneTimeCodeStore {

    private final OAuth2Properties oAuth2Properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, CodeEntry> store = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryOAuth2OneTimeCodeStore(OAuth2Properties oAuth2Properties) {
        this(oAuth2Properties, Clock.systemUTC());
    }

    public InMemoryOAuth2OneTimeCodeStore(OAuth2Properties oAuth2Properties, Clock clock) {
        this.oAuth2Properties = oAuth2Properties;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    private record CodeEntry(Long userId, Instant expiresAt) {
    }

    @Override
    public String generateAndStore(Long userId) {
        cleanupExpired();

        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        int ttl = Math.max(10, oAuth2Properties.getCodeTtlSeconds());
        Instant expiresAt = Instant.now(clock).plusSeconds(ttl);

        store.put(code, new CodeEntry(userId, expiresAt));
        log.debug("Generated OAuth2 one-time code for userId={}, expires in {}s", userId, ttl);
        return code;
    }

    @Override
    public Optional<Long> consume(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }

        CodeEntry entry = store.remove(code);
        if (entry == null) {
            log.warn("OAuth2 one-time code not found or already consumed");
            return Optional.empty();
        }

        if (Instant.now(clock).isAfter(entry.expiresAt())) {
            log.warn("OAuth2 one-time code expired for userId={}", entry.userId());
            return Optional.empty();
        }

        log.info("OAuth2 one-time code successfully consumed for userId={}", entry.userId());
        return Optional.of(entry.userId());
    }

    private void cleanupExpired() {
        if (store.size() < 100) {
            return;
        }
        Instant now = Instant.now(clock);
        store.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
    }
}
