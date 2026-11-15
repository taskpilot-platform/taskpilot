package com.taskpilot.infrastructure.config.security;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validated JWT signing and TTL configuration.
 * Never logs or includes raw secret material in exception messages.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "application.security.jwt")
public class JwtProperties {

    public static final long DEFAULT_EXPIRATION_MS = 900_000L; // 15 minutes
    public static final long MIN_EXPIRATION_MS = 1_000L;
    public static final long MAX_EXPIRATION_MS = 3_600_000L; // 1 hour upper bound
    public static final int MIN_SECRET_BYTES = 32;
    public static final int MIN_DISTINCT_BYTES = 8;

    static final String KNOWN_PUBLIC_SAMPLE_KEY =
            "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";

    private static final List<String> NON_PRODUCTION_MARKERS = List.of(
            "dev-only",
            "test-only",
            "local-dummy",
            "not-a-secret",
            "changeme",
            "sample",
            "example",
            "default-secret"
    );

    private String secretKey;
    private long expiration = DEFAULT_EXPIRATION_MS;

    @Autowired(required = false)
    private Environment environment;

    public String getNormalizedSecretKey() {
        return secretKey == null ? null : secretKey.trim();
    }

    @PostConstruct
    public void validate() {
        validateSecretKey(secretKey, isNonProdProfileActive());
        validateExpiration(expiration);
    }

    public void validateForProfiles(String... activeProfiles) {
        boolean nonProd = Arrays.stream(activeProfiles)
                .anyMatch(p -> "dev".equalsIgnoreCase(p) || "test".equalsIgnoreCase(p));
        boolean prod = Arrays.stream(activeProfiles)
                .anyMatch(p -> "prod".equalsIgnoreCase(p));
        validateSecretKey(secretKey, nonProd && !prod);
        validateExpiration(expiration);
    }

    private boolean isNonProdProfileActive() {
        if (environment == null) {
            return false;
        }
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        boolean hasProd = profiles.stream().anyMatch(p -> "prod".equalsIgnoreCase(p));
        if (hasProd) {
            return false;
        }
        return profiles.stream().anyMatch(p -> "dev".equalsIgnoreCase(p) || "test".equalsIgnoreCase(p));
    }

    private static void validateSecretKey(String rawKey, boolean nonProdProfileAllowed) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new IllegalStateException("JWT signing key is missing (application.security.jwt.secret-key)");
        }
        String trimmed = rawKey.trim();
        if (KNOWN_PUBLIC_SAMPLE_KEY.equalsIgnoreCase(trimmed)) {
            throw new IllegalStateException("JWT signing key uses the forbidden public sample key");
        }

        byte[] bytes = trimmed.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT signing key must be at least " + MIN_SECRET_BYTES + " UTF-8 bytes");
        }
        Set<Byte> distinct = new HashSet<>();
        for (byte b : bytes) {
            distinct.add(b);
        }
        if (distinct.size() < MIN_DISTINCT_BYTES) {
            throw new IllegalStateException("JWT signing key has insufficient byte diversity");
        }

        if (!nonProdProfileAllowed) {
            String lower = trimmed.toLowerCase(Locale.ROOT);
            for (String marker : NON_PRODUCTION_MARKERS) {
                if (lower.contains(marker)) {
                    throw new IllegalStateException(
                            "Non-production JWT signing key marker is forbidden in production/common profile");
                }
            }
        }
    }

    private static void validateExpiration(long expirationMs) {
        if (expirationMs < MIN_EXPIRATION_MS || expirationMs > MAX_EXPIRATION_MS) {
            throw new IllegalStateException(
                    "JWT expiration must be between " + MIN_EXPIRATION_MS + " and " + MAX_EXPIRATION_MS + " ms");
        }
    }
}
