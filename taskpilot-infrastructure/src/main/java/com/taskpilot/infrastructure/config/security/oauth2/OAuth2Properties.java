package com.taskpilot.infrastructure.config.security.oauth2;

import com.taskpilot.infrastructure.config.security.CorsOriginProperties;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "application.security.oauth2")
public class OAuth2Properties {

    public static final int DEFAULT_REQUEST_TTL_SECONDS = 300;
    public static final int MAX_REQUEST_TTL_SECONDS = 600;
    public static final int DEFAULT_MAX_PENDING_REQUESTS = 10_000;
    public static final int DEFAULT_MAX_PENDING_COOKIES_PER_BROWSER = 5;

    private String successRedirectUri = "http://localhost:5173/auth/callback";
    private String allowedRedirectOrigins = "http://localhost:5173,http://127.0.0.1:5173";
    private int codeTtlSeconds = 60;
    private int requestTtlSeconds = DEFAULT_REQUEST_TTL_SECONDS;
    private int maxPendingRequests = DEFAULT_MAX_PENDING_REQUESTS;
    private int maxPendingCookiesPerBrowser = DEFAULT_MAX_PENDING_COOKIES_PER_BROWSER;
    private boolean cookieSecure = false;
    private ProviderConfig google = new ProviderConfig();
    private ProviderConfig github = new ProviderConfig();

    @PostConstruct
    public void validate() {
        if (requestTtlSeconds < 1 || requestTtlSeconds > MAX_REQUEST_TTL_SECONDS) {
            throw new IllegalStateException(
                    "OAuth2 request TTL must be between 1 and " + MAX_REQUEST_TTL_SECONDS + " seconds");
        }
        if (maxPendingRequests < 1 || maxPendingRequests > 100_000) {
            throw new IllegalStateException("OAuth2 maxPendingRequests must be between 1 and 100000");
        }
        if (maxPendingCookiesPerBrowser < 1 || maxPendingCookiesPerBrowser > 20) {
            throw new IllegalStateException("OAuth2 maxPendingCookiesPerBrowser must be between 1 and 20");
        }
        validateRedirectUri(successRedirectUri);
    }

    public List<String> getAllowedRedirectOriginList() {
        return CorsOriginProperties.parseExplicitOrigins(
                allowedRedirectOrigins,
                "application.security.oauth2.allowed-redirect-origins");
    }

    public String validateRedirectUri(String candidateUri) {
        String origin = CorsOriginProperties.extractNormalizedOrigin(
                candidateUri,
                "application.security.oauth2.success-redirect-uri");
        List<String> allowed = getAllowedRedirectOriginList();
        if (!allowed.contains(origin)) {
            throw new IllegalStateException(
                    "OAuth2 redirect origin is not in allowed-redirect-origins: " + origin);
        }
        return candidateUri.trim();
    }

    @Getter
    @Setter
    public static class ProviderConfig {
        private String clientId;
        private String clientSecret;

        public boolean isConfigured() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }
    }
}
