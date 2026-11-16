package com.taskpilot.users.auth.oauth2;

import com.taskpilot.infrastructure.config.security.oauth2.HttpCookieOAuth2AuthorizationRequestRepository;
import com.taskpilot.infrastructure.config.security.oauth2.HttpCookieOAuth2AuthorizationRequestRepository.ConsumedContext;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2AuthorizationRequestStore.Purpose;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2OneTimeCodeStore;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2OneTimeCodeStore.ExchangePayload;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2Properties;
import com.taskpilot.infrastructure.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.Locale;

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

    @Autowired(required = false)
    private CredentialLinkService credentialLinkService;

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

            ConsumedContext consumedCtx = resolveConsumedContext(request, response);
            OAuth2UserInfo userInfo = userInfoMapper.map(authToken, authorizedClientService);

            if (consumedCtx != null && consumedCtx.purpose() == Purpose.LINK) {
                if (credentialLinkService == null || consumedCtx.targetUserId() == null) {
                    redirectToFailure(request, response, "invalid_link_state");
                    return;
                }
                credentialLinkService.completeLink(consumedCtx.targetUserId(), consumedCtx.linkTokenHash(), userInfo);
                String code = codeStore.generateAndStore(ExchangePayload.linkCompleted(
                        consumedCtx.targetUserId(),
                        userInfo.provider().name().toLowerCase(Locale.ROOT)));
                redirectWithCode(request, response, code);
                return;
            }

            if (consumedCtx != null && consumedCtx.purpose() == Purpose.REAUTH) {
                if (credentialLinkService == null || consumedCtx.targetUserId() == null) {
                    redirectToFailure(request, response, "invalid_reauth_state");
                    return;
                }
                String reauthTicket = credentialLinkService.completeReauth(
                        consumedCtx.targetUserId(), consumedCtx.linkTokenHash(), userInfo);
                String code = codeStore.generateAndStore(ExchangePayload.reauthCompleted(
                        consumedCtx.targetUserId(),
                        userInfo.provider().name().toLowerCase(Locale.ROOT),
                        reauthTicket,
                        CredentialLinkService.LINK_INTENT_TTL_SECONDS));
                redirectWithCode(request, response, code);
                return;
            }

            OAuth2AccountService.Resolution resolution = accountService.resolveLoginOrRegister(userInfo);
            String code = switch (resolution) {
                case OAuth2AccountService.Resolution.Authenticated auth ->
                        codeStore.generateAndStore(auth.user().getId());
                case OAuth2AccountService.Resolution.RestoreRequired restore ->
                        codeStore.generateAndStore(ExchangePayload.restoreRequired(
                                restore.user().getId(),
                                restore.user().getEmail(),
                                userInfo.provider().name().toLowerCase(Locale.ROOT),
                                restore.restoreTicket(),
                                restore.expiresInSeconds()));
                case OAuth2AccountService.Resolution.EmailVerificationRequired verifyReq ->
                        codeStore.generateAndStore(ExchangePayload.emailVerificationRequired(
                                verifyReq.user().getId(),
                                verifyReq.user().getEmail(),
                                userInfo.provider().name().toLowerCase(Locale.ROOT),
                                verifyReq.message()));
                case OAuth2AccountService.Resolution.EmailCollision collision ->
                        codeStore.generateAndStore(ExchangePayload.oauthEmailCollision(
                                collision.email(),
                                collision.provider(),
                                collision.message()));
            };

            redirectWithCode(request, response, code);
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

    private ConsumedContext resolveConsumedContext(HttpServletRequest request, HttpServletResponse response) {
        Object attr = request.getAttribute(HttpCookieOAuth2AuthorizationRequestRepository.CONSUMED_CONTEXT_ATTR);
        if (attr instanceof ConsumedContext ctx) {
            return ctx;
        }
        OAuth2AuthorizationRequest removed = cookieRepository.removeAuthorizationRequest(request, response);
        Object afterRemove = request.getAttribute(HttpCookieOAuth2AuthorizationRequestRepository.CONSUMED_CONTEXT_ATTR);
        if (afterRemove instanceof ConsumedContext ctx) {
            return ctx;
        }
        if (removed != null) {
            Object rawPurpose = removed.getAttributes().get("tp_purpose");
            Object rawUser = removed.getAttributes().get("tp_target_user_id");
            Object rawHash = removed.getAttributes().get("tp_link_token_hash");
            Purpose purpose = rawPurpose != null ? Purpose.valueOf(String.valueOf(rawPurpose)) : Purpose.LOGIN;
            Long userId = rawUser != null ? Long.valueOf(String.valueOf(rawUser)) : null;
            return new ConsumedContext(purpose, userId, rawHash != null ? String.valueOf(rawHash) : null);
        }
        return null;
    }

    private void redirectWithCode(HttpServletRequest request, HttpServletResponse response, String code)
            throws IOException {
        String safeRedirectBase = oAuth2Properties.validateRedirectUri(oAuth2Properties.getSuccessRedirectUri());
        String targetUrl = UriComponentsBuilder.fromUriString(safeRedirectBase)
                .queryParam("code", code)
                .build()
                .toUriString();
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    private void redirectToFailure(HttpServletRequest request, HttpServletResponse response, String error)
            throws IOException {
        String safeRedirectBase = oAuth2Properties.validateRedirectUri(oAuth2Properties.getSuccessRedirectUri());
        String targetUrl = UriComponentsBuilder.fromUriString(safeRedirectBase)
                .queryParam("error", error != null ? error : "provider_error")
                .build()
                .toUriString();
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
