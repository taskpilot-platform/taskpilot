package com.taskpilot.users.auth.oauth2;

import com.taskpilot.users.common.enums.AuthProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OAuth2UserInfoMapper {

    private final RestTemplate restTemplate = new RestTemplate();

    public OAuth2UserInfo map(OAuth2AuthenticationToken authenticationToken,
            OAuth2AuthorizedClientService authorizedClientService) {
        String registrationId = authenticationToken.getAuthorizedClientRegistrationId();
        OAuth2User principal = authenticationToken.getPrincipal();

        if ("google".equalsIgnoreCase(registrationId)) {
            return mapGoogle(principal);
        } else if ("github".equalsIgnoreCase(registrationId)) {
            String accessToken = null;
            if (authorizedClientService != null) {
                try {
                    OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient(
                            registrationId, authenticationToken.getName());
                    if (authorizedClient != null && authorizedClient.getAccessToken() != null) {
                        accessToken = authorizedClient.getAccessToken().getTokenValue();
                    }
                } catch (Exception e) {
                    log.warn("Could not load authorized client for GitHub: {}", e.getMessage());
                }
            }
            return mapGithub(principal, accessToken);
        }

        throw new IllegalArgumentException("Unsupported OAuth2 provider: " + registrationId);
    }

    public OAuth2UserInfo mapGoogle(OAuth2User principal) {
        Map<String, Object> attributes = principal.getAttributes();
        String subject = (String) attributes.get("sub");
        String email = (String) attributes.get("email");
        Boolean emailVerified = (Boolean) attributes.get("email_verified");
        String name = (String) attributes.get("name");
        String picture = (String) attributes.get("picture");

        return new OAuth2UserInfo(
                AuthProvider.GOOGLE,
                subject,
                email != null ? email.trim().toLowerCase() : null,
                Boolean.TRUE.equals(emailVerified),
                name != null && !name.isBlank() ? name.trim() : (email != null ? email.split("@")[0] : "Google User"),
                picture);
    }

    public OAuth2UserInfo mapGithub(OAuth2User principal, String accessToken) {
        Map<String, Object> attributes = principal.getAttributes();
        Object idObj = attributes.get("id");
        String subject = idObj != null ? String.valueOf(idObj) : null;
        String login = (String) attributes.get("login");
        String name = (String) attributes.get("name");
        String avatarUrl = (String) attributes.get("avatar_url");
        String email = (String) attributes.get("email");

        boolean emailVerified = false;

        if (email != null && !email.isBlank()) {
            emailVerified = true;
        } else if (accessToken != null && !accessToken.isBlank()) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setBearerAuth(accessToken);
                headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                HttpEntity<Void> entity = new HttpEntity<>(headers);

                ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                        "https://api.github.com/user/emails",
                        HttpMethod.GET,
                        entity,
                        new ParameterizedTypeReference<>() {
                        });

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    for (Map<String, Object> emailObj : response.getBody()) {
                        Boolean verified = (Boolean) emailObj.get("verified");
                        Boolean primary = (Boolean) emailObj.get("primary");
                        String emailVal = (String) emailObj.get("email");

                        if (Boolean.TRUE.equals(primary) && Boolean.TRUE.equals(verified) && emailVal != null) {
                            email = emailVal;
                            emailVerified = true;
                            break;
                        }
                    }
                    if (!emailVerified) {
                        for (Map<String, Object> emailObj : response.getBody()) {
                            Boolean verified = (Boolean) emailObj.get("verified");
                            String emailVal = (String) emailObj.get("email");
                            if (Boolean.TRUE.equals(verified) && emailVal != null) {
                                email = emailVal;
                                emailVerified = true;
                                break;
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to fetch private emails from GitHub API: {}", ex.getMessage());
            }
        }

        String displayName = name != null && !name.isBlank() ? name.trim()
                : (login != null ? login.trim() : "GitHub User");

        return new OAuth2UserInfo(
                AuthProvider.GITHUB,
                subject,
                email != null ? email.trim().toLowerCase() : null,
                emailVerified,
                displayName,
                avatarUrl);
    }
}
