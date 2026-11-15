package com.taskpilot.infrastructure.config.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses and validates explicit HTTP/WebSocket CORS allowed origins once at startup.
 * Wildcard origins (including port wildcards) are rejected fail-closed.
 */
@Component
public class CorsOriginProperties {

    private final String rawOrigins;
    private List<String> allowedOrigins = List.of();

    public CorsOriginProperties(
            @Value("${FE_ORIGIN:}") String feOrigin,
            @Value("${app.cors.allowed-origins:http://localhost:5173}") String configuredOrigins) {
        this.rawOrigins = (feOrigin != null && !feOrigin.isBlank()) ? feOrigin : configuredOrigins;
        validate();
    }

    @PostConstruct
    public final void validate() {
        this.allowedOrigins = parseExplicitOrigins(rawOrigins, "app.cors.allowed-origins");
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public String[] getAllowedOriginsArray() {
        return allowedOrigins.toArray(String[]::new);
    }

    public static List<String> parseExplicitOrigins(String commaSeparatedOrigins, String configName) {
        if (commaSeparatedOrigins == null || commaSeparatedOrigins.isBlank()) {
            throw new IllegalStateException("Origin allowlist must not be blank: " + configName);
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String token : commaSeparatedOrigins.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            normalized.add(normalizeExplicitOrigin(trimmed, configName));
        }
        if (normalized.isEmpty()) {
            throw new IllegalStateException("Origin allowlist resolved to empty set: " + configName);
        }
        return List.copyOf(new ArrayList<>(normalized));
    }

    public static String normalizeExplicitOrigin(String origin, String configName) {
        if (origin == null || origin.isBlank()) {
            throw new IllegalStateException("Origin entry must not be blank: " + configName);
        }
        String trimmed = origin.trim();
        if (trimmed.contains("*")) {
            throw new IllegalStateException("Wildcard origins are forbidden in " + configName + ": " + trimmed);
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid origin URI in " + configName + ": " + trimmed, ex);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalStateException("Origin scheme must be http or https in " + configName + ": " + trimmed);
        }
        if (uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException("Origin must not contain userinfo, query, or fragment: " + trimmed);
        }
        String rawPath = uri.getRawPath();
        if (rawPath != null && !rawPath.isEmpty()) {
            throw new IllegalStateException("Origin must not contain a path in " + configName + ": " + trimmed);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("Origin must include a valid host in " + configName + ": " + trimmed);
        }
        int port = uri.getPort();
        StringBuilder sb = new StringBuilder(scheme).append("://").append(host.toLowerCase(Locale.ROOT));
        if (port != -1) {
            if ((port == 80 && "http".equals(scheme)) || (port == 443 && "https".equals(scheme))) {
                return sb.toString();
            }
            sb.append(':').append(port);
        }
        return sb.toString();
    }

    public static String extractNormalizedOrigin(String uriString, String configName) {
        if (uriString == null || uriString.isBlank()) {
            throw new IllegalStateException("Redirect URI must not be blank: " + configName);
        }
        URI uri;
        try {
            uri = URI.create(uriString.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid URI in " + configName, ex);
        }
        if (uri.getUserInfo() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException("URI must not contain userinfo or fragment in " + configName);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if ((!"http".equals(scheme) && !"https".equals(scheme)) || host == null || host.isBlank()) {
            throw new IllegalStateException("URI must be absolute http/https with host in " + configName);
        }
        String baseOrigin = scheme + "://" + host.toLowerCase(Locale.ROOT)
                + (uri.getPort() != -1 ? ":" + uri.getPort() : "");
        return normalizeExplicitOrigin(baseOrigin, configName);
    }
}
