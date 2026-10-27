package com.taskpilot.users.auth.oauth2;

import com.taskpilot.infrastructure.config.security.oauth2.HttpCookieOAuth2AuthorizationRequestRepository;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2Properties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2LoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private final HttpCookieOAuth2AuthorizationRequestRepository cookieRepository;
    private final OAuth2Properties oAuth2Properties;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        cookieRepository.removeAuthorizationRequest(request, response);

        String errorCode = mapErrorCode(exception);
        log.warn("OAuth2 authentication failed with code={}: {}", errorCode, exception.getMessage());

        String targetUrl = UriComponentsBuilder.fromUriString(oAuth2Properties.getSuccessRedirectUri())
                .queryParam("error", errorCode)
                .build()
                .toUriString();

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    private String mapErrorCode(AuthenticationException exception) {
        if (exception == null || exception.getMessage() == null) {
            return "provider_error";
        }
        String msg = exception.getMessage().toLowerCase();
        if (msg.contains("access_denied") || msg.contains("user_cancelled") || msg.contains("cancelled")) {
            return "access_denied";
        }
        if (msg.contains("authorization_request_not_found") || msg.contains("invalid_state")) {
            return "invalid_state";
        }
        if (msg.contains("email_unverified")) {
            return "email_unverified";
        }
        if (msg.contains("disabled") || msg.contains("locked")) {
            return "account_disabled";
        }
        return "provider_error";
    }
}
