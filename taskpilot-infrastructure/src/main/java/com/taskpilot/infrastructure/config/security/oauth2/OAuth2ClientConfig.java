package com.taskpilot.infrastructure.config.security.oauth2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class OAuth2ClientConfig {

    private final OAuth2Properties oAuth2Properties;

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository() {
        List<ClientRegistration> registrations = new ArrayList<>();

        if (oAuth2Properties.getGoogle().isConfigured()) {
            registrations.add(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId(oAuth2Properties.getGoogle().getClientId().trim())
                    .clientSecret(oAuth2Properties.getGoogle().getClientSecret().trim())
                    .scope("openid", "profile", "email")
                    .build());
            log.info("OAuth2 provider registered: GOOGLE");
        } else {
            log.info("OAuth2 provider GOOGLE is disabled (client-id/secret not configured)");
        }

        if (oAuth2Properties.getGithub().isConfigured()) {
            registrations.add(CommonOAuth2Provider.GITHUB.getBuilder("github")
                    .clientId(oAuth2Properties.getGithub().getClientId().trim())
                    .clientSecret(oAuth2Properties.getGithub().getClientSecret().trim())
                    .scope("read:user", "user:email")
                    .build());
            log.info("OAuth2 provider registered: GITHUB");
        } else {
            log.info("OAuth2 provider GITHUB is disabled (client-id/secret not configured)");
        }

        if (registrations.isEmpty()) {
            log.info("No OAuth2 providers configured. Creating dormant registration to ensure clean startup.");
            registrations.add(ClientRegistration.withRegistrationId("none")
                    .clientId("dormant")
                    .clientSecret("dormant")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .authorizationUri("https://localhost/dormant/oauth/authorize")
                    .tokenUri("https://localhost/dormant/oauth/token")
                    .build());
        }

        return new InMemoryClientRegistrationRepository(registrations);
    }
}
