package com.taskpilot.infrastructure.config.security.oauth2;

import com.taskpilot.infrastructure.config.security.oauth2.OAuth2AuthorizationRequestStore.LookupKey;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2AuthorizationRequestStore.Purpose;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2AuthorizationRequestStore.SaveCommand;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2AuthorizationRequestStore.StoredHandle;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuth2AuthorizationRequestRepositoryTest {

    private InMemoryTestStore store;
    private OAuth2Properties properties;
    private HttpCookieOAuth2AuthorizationRequestRepository repository;

    @BeforeEach
    void setUp() {
        store = new InMemoryTestStore();
        properties = new OAuth2Properties();
        repository = new HttpCookieOAuth2AuthorizationRequestRepository(store, properties);
    }

    @Test
    void legacySerializedBenignCookieIsRejectedWithoutDeserializing() throws Exception {
        OAuth2AuthorizationRequest benign = sampleRequest("state-legacy");
        String legacyCookieValue = javaSerializeBase64(benign);

        MockHttpServletRequest callbackRequest = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        callbackRequest.setParameter("state", "state-legacy");
        callbackRequest.setParameter("code", "auth-code-123");
        callbackRequest.setCookies(new Cookie(
                HttpCookieOAuth2AuthorizationRequestRepository.OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME,
                legacyCookieValue));

        OAuth2AuthorizationRequest loaded = repository.loadAuthorizationRequest(callbackRequest);
        assertThat(loaded).isNull();
    }

    @Test
    void twoPendingTabsDoNotOverwriteEachOther() {
        MockHttpServletRequest initTab1 = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        MockHttpServletResponse respTab1 = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(sampleRequest("state-tab-1"), initTab1, respTab1);

        MockHttpServletRequest initTab2 = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        MockHttpServletResponse respTab2 = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(sampleRequest("state-tab-2"), initTab2, respTab2);

        Map<String, Cookie> browserCookies = new LinkedHashMap<>();
        applySetCookies(respTab1.getHeaders("Set-Cookie"), browserCookies);
        applySetCookies(respTab2.getHeaders("Set-Cookie"), browserCookies);

        MockHttpServletRequest callbackTab1 = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        callbackTab1.setParameter("state", "state-tab-1");
        callbackTab1.setParameter("code", "auth-code-tab-1");
        callbackTab1.setCookies(browserCookies.values().toArray(Cookie[]::new));

        OAuth2AuthorizationRequest loaded = repository.loadAuthorizationRequest(callbackTab1);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getState()).isEqualTo("state-tab-1");
    }

    @Test
    void spoofedXForwardedProtoDoesNotMarkPlainHttpCookieSecure() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        request.setSecure(false);
        request.addHeader("X-Forwarded-Proto", "https");
        MockHttpServletResponse response = new MockHttpServletResponse();

        repository.saveAuthorizationRequest(sampleRequest("state-proto"), request, response);

        List<String> setCookies = response.getHeaders("Set-Cookie");
        assertThat(setCookies).isNotEmpty();
        assertThat(setCookies).noneMatch(header -> header.toLowerCase().contains("; secure"));
    }

    @Test
    void cookieSecureFollowsConfiguredDeploymentPolicyAndLegacyCookieIsCleared() {
        properties.setCookieSecure(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        request.setSecure(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        repository.saveAuthorizationRequest(sampleRequest("state-secure"), request, response);

        List<String> setCookies = response.getHeaders("Set-Cookie");
        assertThat(setCookies).anyMatch(h -> h.startsWith(
                HttpCookieOAuth2AuthorizationRequestRepository.OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME + "=;")
                && h.contains("Max-Age=0")
                && h.contains("Path=/"));
        assertThat(setCookies).anyMatch(h -> h.startsWith(
                HttpCookieOAuth2AuthorizationRequestRepository.OAUTH2_REQUEST_COOKIE_PREFIX)
                && h.contains("Path=/login/oauth2/code")
                && h.contains("HttpOnly")
                && h.contains("SameSite=Lax")
                && h.contains("; Secure"));
    }

    @Test
    void validCallbackLoadsNondestructivelyConsumesOnceAndAllowsHarmlessHandlerCleanup() {
        MockHttpServletRequest initReq = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        MockHttpServletResponse initResp = new MockHttpServletResponse();
        OAuth2AuthorizationRequest original = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("test-client-id")
                .redirectUri("http://localhost:8080/login/oauth2/code/google")
                .scopes(Set.of("openid", "email"))
                .state("state-pkce")
                .additionalParameters(p -> {
                    p.put("code_challenge", "challenge-abc");
                    p.put("code_challenge_method", "S256");
                    p.put("nonce", "oidc-nonce-123");
                })
                .attributes(a -> {
                    a.put("registration_id", "google");
                    a.put("code_verifier", "verifier-xyz");
                    a.put("nonce", "oidc-nonce-123");
                })
                .build();
        repository.saveAuthorizationRequest(original, initReq, initResp);

        Map<String, Cookie> jar = new LinkedHashMap<>();
        applySetCookies(initResp.getHeaders("Set-Cookie"), jar);
        Cookie[] savedCookies = jar.values().toArray(Cookie[]::new);

        MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        callback.setServerPort(8080);
        callback.setParameter("state", "state-pkce");
        callback.setParameter("code", "auth-code");
        callback.setCookies(savedCookies);

        // Nondestructive load
        assertThat(repository.loadAuthorizationRequest(callback)).isNotNull();
        OAuth2AuthorizationRequest secondLoad = repository.loadAuthorizationRequest(callback);
        assertThat(secondLoad).isNotNull();
        assertThat((String) secondLoad.getAttribute("code_verifier")).isEqualTo("verifier-xyz");
        assertThat(secondLoad.getAdditionalParameters()).containsEntry("nonce", "oidc-nonce-123");

        // Filter remove-before-exchange consumes atomically
        MockHttpServletResponse removeResp = new MockHttpServletResponse();
        OAuth2AuthorizationRequest consumed = repository.removeAuthorizationRequest(callback, removeResp);
        assertThat(consumed).isNotNull();
        assertThat(consumed.getState()).isEqualTo("state-pkce");

        // Handler cleanup remove called again with empty/replayed cookies must be harmless
        MockHttpServletResponse handlerResp = new MockHttpServletResponse();
        assertThat(repository.removeAuthorizationRequest(callback, handlerResp)).isNull();
        assertThat(repository.loadAuthorizationRequest(callback)).isNull();
    }

    @Test
    void mismatchedProviderHostOversizeOrTamperedCookieIsRejected() {
        MockHttpServletRequest initReq = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        MockHttpServletResponse initResp = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(sampleRequest("state-bind"), initReq, initResp);

        Map<String, Cookie> jar = new LinkedHashMap<>();
        applySetCookies(initResp.getHeaders("Set-Cookie"), jar);
        Cookie[] savedCookies = jar.values().toArray(Cookie[]::new);

        // Provider mismatch (/github instead of /google)
        MockHttpServletRequest wrongProvider = new MockHttpServletRequest("GET", "/login/oauth2/code/github");
        wrongProvider.setServerPort(8080);
        wrongProvider.setParameter("state", "state-bind");
        wrongProvider.setCookies(savedCookies);
        assertThat(repository.loadAuthorizationRequest(wrongProvider)).isNull();

        // Host mismatch
        MockHttpServletRequest wrongHost = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        wrongHost.setServerName("evil.example");
        wrongHost.setServerPort(8080);
        wrongHost.setParameter("state", "state-bind");
        wrongHost.setCookies(savedCookies);
        assertThat(repository.loadAuthorizationRequest(wrongHost)).isNull();

        // Oversize state rejected before store lookup
        MockHttpServletRequest oversizeState = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        oversizeState.setParameter("state", "s".repeat(300));
        oversizeState.setCookies(savedCookies);
        assertThat(repository.loadAuthorizationRequest(oversizeState)).isNull();

        // Tampered cookie value rejected
        Cookie[] tampered = new Cookie[] {
                new Cookie(HttpCookieOAuth2AuthorizationRequestRepository.cookieNameForState("state-bind"), "bad.nonce")
        };
        MockHttpServletRequest tamperedReq = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        tamperedReq.setServerPort(8080);
        tamperedReq.setParameter("state", "state-bind");
        tamperedReq.setCookies(tampered);
        assertThat(repository.loadAuthorizationRequest(tamperedReq)).isNull();
    }

    @Test
    void perBrowserPendingLimitRejectsFloodAfterPruningStaleCookies() {
        properties.setMaxPendingCookiesPerBrowser(2);
        Map<String, Cookie> jar = new LinkedHashMap<>();

        for (int i = 1; i <= 2; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
            req.setCookies(jar.values().toArray(Cookie[]::new));
            MockHttpServletResponse resp = new MockHttpServletResponse();
            repository.saveAuthorizationRequest(sampleRequest("state-" + i), req, resp);
            applySetCookies(resp.getHeaders("Set-Cookie"), jar);
        }

        MockHttpServletRequest thirdReq = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        thirdReq.setCookies(jar.values().toArray(Cookie[]::new));
        MockHttpServletResponse thirdResp = new MockHttpServletResponse();

        assertThatThrownBy(() -> repository.saveAuthorizationRequest(sampleRequest("state-3"), thirdReq, thirdResp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Too many pending");
    }

    private static OAuth2AuthorizationRequest sampleRequest(String state) {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("test-client-id")
                .redirectUri("http://localhost:8080/login/oauth2/code/google")
                .scopes(Set.of("openid", "profile", "email"))
                .state(state)
                .attributes(attrs -> attrs.put("registration_id", "google"))
                .build();
    }

    private static String javaSerializeBase64(OAuth2AuthorizationRequest request) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(request);
            return Base64.getUrlEncoder().encodeToString(baos.toByteArray());
        }
    }

    private static void applySetCookies(List<String> setCookieHeaders, Map<String, Cookie> jar) {
        for (String header : setCookieHeaders) {
            String nameValue = header.split(";", 2)[0];
            int eq = nameValue.indexOf('=');
            if (eq > 0) {
                String name = nameValue.substring(0, eq).trim();
                String value = nameValue.substring(eq + 1).trim();
                if (header.contains("Max-Age=0")) {
                    jar.remove(name);
                } else {
                    jar.put(name, new Cookie(name, value));
                }
            }
        }
    }

    private static final class InMemoryTestStore implements OAuth2AuthorizationRequestStore {
        private record Entry(SaveCommand command, Instant expiresAt, boolean consumed) {}

        private final Map<String, Entry> entries = new ConcurrentHashMap<>();

        @Override
        public StoredHandle put(SaveCommand command) {
            String handle = UUID.randomUUID().toString().replace("-", "");
            Instant expiresAt = Instant.now().plusSeconds(300);
            entries.put(handle, new Entry(command, expiresAt, false));
            return new StoredHandle(handle, expiresAt);
        }

        @Override
        public Optional<OAuth2AuthorizationRequest> load(LookupKey key) {
            Entry e = entries.get(key.handle());
            return matches(e, key) ? Optional.of(e.command().authorizationRequest()) : Optional.empty();
        }

        @Override
        public Optional<OAuth2AuthorizationRequest> consume(LookupKey key) {
            Entry e = entries.get(key.handle());
            if (!matches(e, key)) {
                return Optional.empty();
            }
            entries.put(key.handle(), new Entry(e.command(), e.expiresAt(), true));
            return Optional.of(e.command().authorizationRequest());
        }

        @Override
        public boolean isActive(String handle, String browserNonce) {
            Entry e = entries.get(handle);
            return e != null && !e.consumed() && e.command().browserNonce().equals(browserNonce);
        }

        @Override
        public int purgeExpired() {
            return 0;
        }

        private boolean matches(Entry e, LookupKey key) {
            return e != null
                    && !e.consumed()
                    && e.expiresAt().isAfter(Instant.now())
                    && e.command().browserNonce().equals(key.browserNonce())
                    && e.command().authorizationRequest().getState().equals(key.state())
                    && e.command().provider().equals(key.expectedProvider())
                    && e.command().purpose() == key.expectedPurpose();
        }
    }
}
