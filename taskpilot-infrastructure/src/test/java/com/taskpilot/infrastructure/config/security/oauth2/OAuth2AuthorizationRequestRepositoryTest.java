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
        TestCookieJar jar = new TestCookieJar();

        MockHttpServletRequest initTab1 = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        jar.attachTo(initTab1);
        MockHttpServletResponse respTab1 = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(sampleRequest("state-tab-1"), initTab1, respTab1);
        jar.applySetCookies(respTab1.getHeaders("Set-Cookie"));

        MockHttpServletRequest initTab2 = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        jar.attachTo(initTab2);
        MockHttpServletResponse respTab2 = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(sampleRequest("state-tab-2"), initTab2, respTab2);
        jar.applySetCookies(respTab2.getHeaders("Set-Cookie"));

        MockHttpServletRequest callbackTab1 = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        callbackTab1.setServerPort(8080);
        callbackTab1.setParameter("state", "state-tab-1");
        callbackTab1.setParameter("code", "auth-code-tab-1");
        jar.attachTo(callbackTab1);

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
                && h.contains("Path=/;")
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

        TestCookieJar jar = new TestCookieJar();
        jar.applySetCookies(initResp.getHeaders("Set-Cookie"));

        MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        callback.setServerPort(8080);
        callback.setParameter("state", "state-pkce");
        callback.setParameter("code", "auth-code");
        jar.attachTo(callback);

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

        TestCookieJar jar = new TestCookieJar();
        jar.applySetCookies(initResp.getHeaders("Set-Cookie"));

        // Provider mismatch (/github instead of /google)
        MockHttpServletRequest wrongProvider = new MockHttpServletRequest("GET", "/login/oauth2/code/github");
        wrongProvider.setServerPort(8080);
        wrongProvider.setParameter("state", "state-bind");
        jar.attachTo(wrongProvider);
        assertThat(repository.loadAuthorizationRequest(wrongProvider)).isNull();

        // Host mismatch on remove must NOT consume valid state or burn the cookie
        MockHttpServletRequest wrongHost = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        wrongHost.setServerName("evil.example");
        wrongHost.setServerPort(8080);
        wrongHost.setParameter("state", "state-bind");
        jar.attachTo(wrongHost);
        MockHttpServletResponse wrongHostResp = new MockHttpServletResponse();
        assertThat(repository.loadAuthorizationRequest(wrongHost)).isNull();
        assertThat(repository.removeAuthorizationRequest(wrongHost, wrongHostResp)).isNull();
        assertThat(wrongHostResp.getHeaders("Set-Cookie"))
                .noneMatch(h -> h.contains(HttpCookieOAuth2AuthorizationRequestRepository.cookieNameForState("state-bind")));

        // Valid callback still succeeds after rejected host-mismatched remove
        MockHttpServletRequest validAfterWrongHost = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        validAfterWrongHost.setServerPort(8080);
        validAfterWrongHost.setParameter("state", "state-bind");
        jar.attachTo(validAfterWrongHost);
        assertThat(repository.loadAuthorizationRequest(validAfterWrongHost)).isNotNull();

        // Oversize state rejected before store lookup
        MockHttpServletRequest oversizeState = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        oversizeState.setParameter("state", "s".repeat(300));
        jar.attachTo(oversizeState);
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
    void defaultAndNonDefaultPortMismatchesAreRejectedAndOriginalRequestRemainsUsable() {
        // 1) Stored non-default :8080 rejected on default port 80 / 443 even without ':port' in Host header
        MockHttpServletRequest initNonDefault = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        MockHttpServletResponse respNonDefault = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(sampleRequest("state-8080"), initNonDefault, respNonDefault);

        TestCookieJar jar = new TestCookieJar();
        jar.applySetCookies(respNonDefault.getHeaders("Set-Cookie"));

        MockHttpServletRequest defaultHttpCallback = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        defaultHttpCallback.setScheme("http");
        defaultHttpCallback.setServerName("localhost");
        defaultHttpCallback.setServerPort(80);
        defaultHttpCallback.addHeader("Host", "localhost");
        defaultHttpCallback.setParameter("state", "state-8080");
        jar.attachTo(defaultHttpCallback);

        MockHttpServletResponse badPortResp = new MockHttpServletResponse();
        assertThat(repository.loadAuthorizationRequest(defaultHttpCallback)).isNull();
        assertThat(repository.removeAuthorizationRequest(defaultHttpCallback, badPortResp)).isNull();
        assertThat(badPortResp.getHeaders("Set-Cookie"))
                .noneMatch(h -> h.contains(HttpCookieOAuth2AuthorizationRequestRepository.cookieNameForState("state-8080")));

        // Original callback on :8080 must still load and consume cleanly
        MockHttpServletRequest valid8080 = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        valid8080.setServerPort(8080);
        valid8080.setParameter("state", "state-8080");
        jar.attachTo(valid8080);
        assertThat(repository.loadAuthorizationRequest(valid8080)).isNotNull();
        assertThat(repository.removeAuthorizationRequest(valid8080, new MockHttpServletResponse())).isNotNull();

        // 2) Stored default HTTPS (implicit 443) accepts implicit 443 without ':443', rejects :8443 and HTTP 80
        MockHttpServletRequest initDefaultHttps = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        MockHttpServletResponse respDefaultHttps = new MockHttpServletResponse();
        OAuth2AuthorizationRequest defaultHttpsReq = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("test-client-id")
                .redirectUri("https://app.taskpilot.example/login/oauth2/code/google")
                .scopes(Set.of("openid", "email"))
                .state("state-443")
                .attributes(attrs -> attrs.put("registration_id", "google"))
                .build();
        repository.saveAuthorizationRequest(defaultHttpsReq, initDefaultHttps, respDefaultHttps);
        jar.applySetCookies(respDefaultHttps.getHeaders("Set-Cookie"));

        MockHttpServletRequest wrongHttpsPort = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        wrongHttpsPort.setScheme("https");
        wrongHttpsPort.setServerName("app.taskpilot.example");
        wrongHttpsPort.setServerPort(8443);
        wrongHttpsPort.addHeader("Host", "app.taskpilot.example:8443");
        wrongHttpsPort.setParameter("state", "state-443");
        jar.attachTo(wrongHttpsPort);
        assertThat(repository.loadAuthorizationRequest(wrongHttpsPort)).isNull();

        MockHttpServletRequest validDefaultHttps = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        validDefaultHttps.setScheme("https");
        validDefaultHttps.setServerName("app.taskpilot.example");
        validDefaultHttps.setServerPort(443);
        validDefaultHttps.addHeader("Host", "app.taskpilot.example");
        validDefaultHttps.setParameter("state", "state-443");
        jar.attachTo(validDefaultHttps);
        assertThat(repository.loadAuthorizationRequest(validDefaultHttps)).isNotNull();
    }

    @Test
    void perBrowserPendingLimitRejectsFloodAfterPruningStaleCookies() {
        properties.setMaxPendingCookiesPerBrowser(2);
        TestCookieJar jar = new TestCookieJar();

        for (int i = 1; i <= 2; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
            jar.attachTo(req);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            repository.saveAuthorizationRequest(sampleRequest("state-" + i), req, resp);
            jar.applySetCookies(resp.getHeaders("Set-Cookie"));
        }

        // Proof 1: active cookies obey RFC 6265 Path and block the 3rd pending tab
        MockHttpServletRequest thirdReq = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        jar.attachTo(thirdReq);
        assertThat(thirdReq.getCookies()).isNotNull().hasSize(2);
        MockHttpServletResponse thirdResp = new MockHttpServletResponse();
        assertThatThrownBy(() -> repository.saveAuthorizationRequest(sampleRequest("state-3"), thirdReq, thirdResp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Too many pending");

        // Proof 2: when one pending request becomes stale in DB (consumed/expired), initiating again
        // prunes the stale cookie with Max-Age=0 and allows the new initiation
        store.markConsumedByState("state-1");
        String staleCookieName = HttpCookieOAuth2AuthorizationRequestRepository.cookieNameForState("state-1");

        MockHttpServletRequest retryReq = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        jar.attachTo(retryReq);
        MockHttpServletResponse retryResp = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(sampleRequest("state-3"), retryReq, retryResp);

        assertThat(retryResp.getHeaders("Set-Cookie"))
                .anyMatch(h -> h.startsWith(staleCookieName + "=;") && h.contains("Max-Age=0") && h.contains("Path=/"));
        jar.applySetCookies(retryResp.getHeaders("Set-Cookie"));
        assertThat(jar.cookiesForPath("/oauth2/authorization/google")).hasSize(2);
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

    private static final class TestCookieJar {
        private record StoredCookie(String name, String value, String path) {}

        private final Map<String, StoredCookie> cookies = new LinkedHashMap<>();

        void applySetCookies(List<String> setCookieHeaders) {
            for (String header : setCookieHeaders) {
                String[] parts = header.split(";");
                String nameValue = parts[0];
                int eq = nameValue.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = nameValue.substring(0, eq).trim();
                String value = nameValue.substring(eq + 1).trim();
                String path = "/";
                boolean expired = false;
                for (int i = 1; i < parts.length; i++) {
                    String attr = parts[i].trim();
                    if (attr.regionMatches(true, 0, "Path=", 0, 5)) {
                        path = attr.substring(5).trim();
                    } else if (attr.equalsIgnoreCase("Max-Age=0")) {
                        expired = true;
                    }
                }
                if (expired) {
                    cookies.remove(name + "|" + path);
                } else {
                    cookies.put(name + "|" + path, new StoredCookie(name, value, path));
                }
            }
        }

        List<Cookie> cookiesForPath(String requestPath) {
            return cookies.values().stream()
                    .filter(c -> matchesPath(requestPath, c.path()))
                    .map(c -> new Cookie(c.name(), c.value()))
                    .toList();
        }

        void attachTo(MockHttpServletRequest request) {
            List<Cookie> matching = cookiesForPath(request.getRequestURI());
            if (!matching.isEmpty()) {
                request.setCookies(matching.toArray(Cookie[]::new));
            }
        }

        private static boolean matchesPath(String requestPath, String cookiePath) {
            if (requestPath == null || cookiePath == null) {
                return false;
            }
            if ("/".equals(cookiePath) || requestPath.equals(cookiePath)) {
                return true;
            }
            if (!requestPath.startsWith(cookiePath)) {
                return false;
            }
            return cookiePath.endsWith("/") || requestPath.charAt(cookiePath.length()) == '/';
        }
    }

    private static final class InMemoryTestStore implements OAuth2AuthorizationRequestStore {
        private record Entry(SaveCommand command, Instant expiresAt, boolean consumed) {}

        private final Map<String, Entry> entries = new ConcurrentHashMap<>();

        void markConsumedByState(String state) {
            entries.forEach((handle, entry) -> {
                if (state.equals(entry.command().authorizationRequest().getState())) {
                    entries.put(handle, new Entry(entry.command(), entry.expiresAt(), true));
                }
            });
        }

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
