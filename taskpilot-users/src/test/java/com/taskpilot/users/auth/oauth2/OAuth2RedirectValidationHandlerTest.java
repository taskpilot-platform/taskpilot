package com.taskpilot.users.auth.oauth2;

import com.taskpilot.infrastructure.config.security.oauth2.HttpCookieOAuth2AuthorizationRequestRepository;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2AuthorizationRequestStore;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2OneTimeCodeStore;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2Properties;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.AuthProvider;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuth2RedirectValidationHandlerTest {

    @Test
    void successHandlerRedirectsToApprovedOriginAndCleansUpHarmlessly() throws Exception {
        OAuth2Properties props = new OAuth2Properties();
        props.setAllowedRedirectOrigins("https://app.taskpilot.example");
        props.setSuccessRedirectUri("https://app.taskpilot.example/auth/callback");

        OAuth2AuthorizationRequestStore store = mock(OAuth2AuthorizationRequestStore.class);
        HttpCookieOAuth2AuthorizationRequestRepository repo =
                new HttpCookieOAuth2AuthorizationRequestRepository(store, props);

        OAuth2UserInfoMapper mapper = mock(OAuth2UserInfoMapper.class);
        OAuth2AccountService accountService = mock(OAuth2AccountService.class);
        OAuth2OneTimeCodeStore codeStore = mock(OAuth2OneTimeCodeStore.class);

        when(mapper.map(any(), isNull())).thenReturn(
                new OAuth2UserInfo(AuthProvider.GOOGLE, "sub-1", "user@example.test", true, "User", null));
        UserEntity user = new UserEntity();
        user.setId(42L);
        when(accountService.resolveUser(any())).thenReturn(user);
        when(codeStore.generateAndStore(42L)).thenReturn("one-time-code");

        OAuth2LoginSuccessHandler handler = new OAuth2LoginSuccessHandler(
                mapper, accountService, codeStore, repo, props);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        request.setParameter("state", "missing-or-already-removed-state");
        request.setCookies(new Cookie("oauth2_auth_request", "legacy-or-cleared"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        DefaultOAuth2User principal = new DefaultOAuth2User(
                AuthorityUtils.NO_AUTHORITIES,
                Map.of("sub", "sub-1"),
                "sub");
        OAuth2AuthenticationToken token = new OAuth2AuthenticationToken(
                principal, AuthorityUtils.NO_AUTHORITIES, "google");

        handler.onAuthenticationSuccess(request, response, token);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl())
                .isEqualTo("https://app.taskpilot.example/auth/callback?code=one-time-code");
    }

    @Test
    void successAndFailureHandlersFailClosedOnDisallowedRedirectOrigin() {
        OAuth2Properties props = new OAuth2Properties();
        props.setAllowedRedirectOrigins("https://app.taskpilot.example");
        props.setSuccessRedirectUri("https://evil.example/steal");

        HttpCookieOAuth2AuthorizationRequestRepository repo =
                new HttpCookieOAuth2AuthorizationRequestRepository(mock(OAuth2AuthorizationRequestStore.class), props);

        OAuth2LoginSuccessHandler successHandler = new OAuth2LoginSuccessHandler(
                mock(OAuth2UserInfoMapper.class),
                mock(OAuth2AccountService.class),
                mock(OAuth2OneTimeCodeStore.class),
                repo,
                props);
        OAuth2LoginFailureHandler failureHandler = new OAuth2LoginFailureHandler(repo, props);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        DefaultOAuth2User principal = new DefaultOAuth2User(
                AuthorityUtils.NO_AUTHORITIES,
                Map.of("sub", "sub-1"),
                "sub");
        OAuth2AuthenticationToken token = new OAuth2AuthenticationToken(
                principal, AuthorityUtils.NO_AUTHORITIES, "google");

        assertThatThrownBy(() -> successHandler.onAuthenticationSuccess(
                request, new MockHttpServletResponse(), token))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-redirect-origins");

        assertThatThrownBy(() -> failureHandler.onAuthenticationFailure(
                request, new MockHttpServletResponse(), new BadCredentialsException("access_denied")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-redirect-origins");
    }
}
