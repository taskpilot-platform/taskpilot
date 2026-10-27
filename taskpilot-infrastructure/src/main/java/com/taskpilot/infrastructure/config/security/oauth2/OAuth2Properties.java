package com.taskpilot.infrastructure.config.security.oauth2;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "application.security.oauth2")
public class OAuth2Properties {

    private String successRedirectUri = "http://localhost:5173/auth/callback";
    private int codeTtlSeconds = 60;
    private ProviderConfig google = new ProviderConfig();
    private ProviderConfig github = new ProviderConfig();

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
