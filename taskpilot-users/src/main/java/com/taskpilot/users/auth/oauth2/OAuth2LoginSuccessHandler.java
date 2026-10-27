package com.taskpilot.users.auth.oauth2;

import com.taskpilot.infrastructure.config.security.oauth2.HttpCookieOAuth2AuthorizationRequestRepository;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2OneTimeCodeStore;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2Properties;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.common.entity.UserEntity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final OAuth2UserInfoMapper userInfoMapper;
    private final OAuth2AccountService accountService;
    private final OAuth2OneTimeCodeStore codeStore;
    private final HttpCookieOAuth2AuthorizationRequestRepository cookieRepository;
    private final OAuth2Properties oAuth2Properties;

    @Autowired(required = false)
    private OAuth2AuthorizedClientService authorizedClientService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException {
        try {
            if (!(authentication instanceof OAuth2AuthenticationToken authToken)) {
                log.error("Authentication is not OAuth2AuthenticationToken: {}", authentication.getClass());
                redirectToFailure(request, response, "provider_error");
                return;
            }

            OAuth2UserInfo userInfo = userInfoMapper.map(authToken, authorizedClientService);
            UserEntity user = accountService.resolveUser(userInfo);
            String code = codeStore.generateAndStore(user.getId());

            cookieRepository.removeAuthorizationRequest(request, response);

            String targetUrl = UriComponentsBuilder.fromUriString(oAuth2Properties.getSuccessRedirectUri())
                    .queryParam("code", code)
                    .build()
                    .toUriString();

            log.info("OAuth2 login succeeded for userId={}, redirecting to frontend callback", user.getId());
            getRedirectStrategy().sendRedirect(request, response, targetUrl);
        } catch (BusinessException be) {
            log.warn("OAuth2 business rejection: {}", be.getMessage());
            cookieRepository.removeAuthorizationRequest(request, response);
            redirectToFailure(request, response, be.getMessage());
        } catch (Exception ex) {
            log.error("Unexpected error in OAuth2LoginSuccessHandler: {}", ex.getMessage(), ex);
            cookieRepository.removeAuthorizationRequest(request, response);
            redirectToFailure(request, response, "provider_error");
        }
    }

    private void redirectToFailure(HttpServletRequest request, HttpServletResponse response, String error)
            throws IOException {
        String targetUrl = UriComponentsBuilder.fromUriString(oAuth2Properties.getSuccessRedirectUri())
                .queryParam("error", error != null ? error : "provider_error")
                .build()
                .toUriString();
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
