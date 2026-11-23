package com.taskpilot.users.auth.oauth2;

import com.taskpilot.users.common.enums.AuthProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class OAuth2UserInfoMapper {

    private final GitHubVerifiedEmailClient gitHubVerifiedEmailClient;

    public OAuth2UserInfoMapper() {
        this(new GitHubVerifiedEmailClient());
    }

    @Autowired
    public OAuth2UserInfoMapper(GitHubVerifiedEmailClient gitHubVerifiedEmailClient) {
        this.gitHubVerifiedEmailClient = gitHubVerifiedEmailClient != null
                ? gitHubVerifiedEmailClient
                : new GitHubVerifiedEmailClient();
    }

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
        String rawEmail = (String) attributes.get("email");
        String normalizedEmail = (rawEmail != null && !rawEmail.isBlank())
                ? rawEmail.trim().toLowerCase(Locale.ROOT)
                : null;
        Boolean emailVerifiedAttr = (Boolean) attributes.get("email_verified");
        boolean emailVerified = Boolean.TRUE.equals(emailVerifiedAttr) && normalizedEmail != null;
        String name = (String) attributes.get("name");
        String picture = (String) attributes.get("picture");

        return new OAuth2UserInfo(
                AuthProvider.GOOGLE,
                subject,
                normalizedEmail,
                emailVerified,
                name != null && !name.isBlank() ? name.trim() : (normalizedEmail != null ? normalizedEmail.split("@")[0] : "Google User"),
                picture);
    }

    public OAuth2UserInfo mapGithub(OAuth2User principal, String accessToken) {
        Map<String, Object> attributes = principal.getAttributes();
        Object idObj = attributes.get("id");
        String subject = idObj != null ? String.valueOf(idObj) : null;
        String login = (String) attributes.get("login");
        String name = (String) attributes.get("name");
        String avatarUrl = (String) attributes.get("avatar_url");
        String publicEmailRaw = (String) attributes.get("email");
        String publicEmail = (publicEmailRaw != null && !publicEmailRaw.isBlank())
                ? publicEmailRaw.trim().toLowerCase(Locale.ROOT)
                : null;

        // Public /user.email is NEVER treated as verified proof; require explicit /user/emails verification.
        Optional<String> verifiedEmailOpt = gitHubVerifiedEmailClient.fetchVerifiedEmail(accessToken);
        boolean emailVerified = verifiedEmailOpt.isPresent();
        String effectiveEmail = verifiedEmailOpt.orElse(publicEmail);

        String displayName = name != null && !name.isBlank() ? name.trim()
                : (login != null ? login.trim() : "GitHub User");

        return new OAuth2UserInfo(
                AuthProvider.GITHUB,
                subject,
                effectiveEmail,
                emailVerified,
                displayName,
                avatarUrl);
    }
}
