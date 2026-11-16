package com.taskpilot.users.auth.oauth2;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded-timeout client for querying GitHub's {@code /user/emails} endpoint.
 * Never fabricates verification when the endpoint fails, times out, or omits {@code verified=true}.
 */
@Slf4j
@Component
public class GitHubVerifiedEmailClient {

    private static final String GITHUB_EMAILS_URL = "https://api.github.com/user/emails";

    private final RestTemplate restTemplate;

    @Autowired
    public GitHubVerifiedEmailClient() {
        this(createBoundedRestTemplate());
    }

    public GitHubVerifiedEmailClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate != null ? restTemplate : createBoundedRestTemplate();
    }

    public Optional<String> fetchVerifiedEmail(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return Optional.empty();
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    GITHUB_EMAILS_URL,
                    HttpMethod.GET,
                    entity,
                    new ParameterizedTypeReference<>() {
                    });

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return Optional.empty();
            }

            // Policy: prefer verified primary email, otherwise fall back to verified secondary email.
            for (Map<String, Object> emailObj : response.getBody()) {
                if (Boolean.TRUE.equals(emailObj.get("primary"))
                        && Boolean.TRUE.equals(emailObj.get("verified"))) {
                    Optional<String> normalized = normalizeEmail(emailObj.get("email"));
                    if (normalized.isPresent()) {
                        return normalized;
                    }
                }
            }
            for (Map<String, Object> emailObj : response.getBody()) {
                if (Boolean.TRUE.equals(emailObj.get("verified"))) {
                    Optional<String> normalized = normalizeEmail(emailObj.get("email"));
                    if (normalized.isPresent()) {
                        return normalized;
                    }
                }
            }
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("GitHub verified email lookup failed closed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<String> normalizeEmail(Object raw) {
        if (!(raw instanceof String str) || str.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(str.trim().toLowerCase(Locale.ROOT));
    }

    private static RestTemplate createBoundedRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(5_000);
        return new RestTemplate(factory);
    }
}
