package com.taskpilot.infrastructure.config.security.oauth2;

import com.taskpilot.infrastructure.config.security.oauth2.OAuth2AuthorizationRequestStore.LookupKey;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2AuthorizationRequestStore.Purpose;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2AuthorizationRequestStore.SaveCommand;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2AuthorizationRequestStore.StoredHandle;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opaque per-state-hash cookie + PostgreSQL store repository for OAuth2 authorization requests.
 * Eliminates Java ObjectInputStream deserialization and raw X-Forwarded-Proto trust,
 * binds browser nonce, OAuth state, provider, redirect context, and flow purpose.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpCookieOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    public static final String OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME = "tp_oauth2_auth_req";
    public static final String OAUTH2_REQUEST_COOKIE_PREFIX = "tp_oauth2_req_";

    private static final String CALLBACK_COOKIE_PATH = "/login/oauth2/code";
    private static final String LEGACY_COOKIE_PATH = "/";
    private static final int NONCE_BYTES = 32;
    private static final int MAX_STATE_LENGTH = 256;
    private static final int MAX_COOKIE_VALUE_LENGTH = 256;
    private static final Pattern CALLBACK_PROVIDER_PATTERN = Pattern.compile(".*/login/oauth2/code/([A-Za-z0-9_-]{1,32})/?$");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final OAuth2AuthorizationRequestStore store;
    private final OAuth2Properties oAuth2Properties;

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        Optional<ParsedCallback> parsed = parseCallbackRequest(request);
        if (parsed.isEmpty()) {
            return null;
        }
        ParsedCallback cb = parsed.get();
        return store.load(cb.lookupKey())
                .filter(authReq -> matchesCallbackContext(request, authReq, cb.provider()))
                .orElse(null);
    }

    @Override
    public void saveAuthorizationRequest(
            OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request,
            HttpServletResponse response) {
        expireCookie(response, OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME, LEGACY_COOKIE_PATH);

        if (authorizationRequest == null) {
            String state = extractBoundedState(request);
            if (state != null) {
                expireCookie(response, cookieNameForState(state), CALLBACK_COOKIE_PATH);
            }
            return;
        }

        String state = authorizationRequest.getState();
        if (state == null || state.isBlank() || state.length() > MAX_STATE_LENGTH) {
            throw new IllegalArgumentException("OAuth2 state is missing or exceeds maximum allowed length");
        }
        String provider = authorizationRequest.getAttribute("registration_id");
        if (provider == null || provider.isBlank() || provider.length() > 32) {
            throw new IllegalArgumentException("OAuth2 registration_id attribute is required");
        }
        oAuth2Properties.validateRedirectUri(oAuth2Properties.getSuccessRedirectUri());

        enforceAndPruneBrowserPendingLimit(request, response);

        String browserNonce = randomToken();
        StoredHandle stored = store.put(new SaveCommand(
                authorizationRequest,
                provider,
                Purpose.LOGIN,
                null,
                browserNonce,
                authorizationRequest.getRedirectUri()
        ));

        String cookieName = cookieNameForState(state);
        String cookieValue = stored.handle() + "." + browserNonce;
        addCookie(response, cookieName, cookieValue, oAuth2Properties.getRequestTtlSeconds(), CALLBACK_COOKIE_PATH);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(
            HttpServletRequest request,
            HttpServletResponse response) {
        expireCookie(response, OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME, LEGACY_COOKIE_PATH);

        String state = extractBoundedState(request);
        if (state != null) {
            expireCookie(response, cookieNameForState(state), CALLBACK_COOKIE_PATH);
        }

        Optional<ParsedCallback> parsed = parseCallbackRequest(request);
        if (parsed.isEmpty()) {
            return null;
        }
        ParsedCallback cb = parsed.get();
        return store.consume(cb.lookupKey())
                .filter(authReq -> matchesCallbackContext(request, authReq, cb.provider()))
                .orElse(null);
    }

    private void enforceAndPruneBrowserPendingLimit(HttpServletRequest request, HttpServletResponse response) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return;
        }
        int activeCount = 0;
        for (Cookie cookie : cookies) {
            String name = cookie.getName();
            if (name == null || !name.startsWith(OAUTH2_REQUEST_COOKIE_PREFIX)) {
                continue;
            }
            CookieTokens tokens = parseCookieTokens(cookie.getValue());
            if (tokens == null || !store.isActive(tokens.handle(), tokens.browserNonce())) {
                expireCookie(response, name, CALLBACK_COOKIE_PATH);
                continue;
            }
            activeCount++;
        }
        if (activeCount >= oAuth2Properties.getMaxPendingCookiesPerBrowser()) {
            throw new IllegalStateException("Too many pending OAuth2 authorization requests for browser");
        }
    }

    private Optional<ParsedCallback> parseCallbackRequest(HttpServletRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        String state = extractBoundedState(request);
        if (state == null) {
            return Optional.empty();
        }
        String cookieName = cookieNameForState(state);
        Optional<Cookie> cookieOpt = findCookie(request, cookieName);
        if (cookieOpt.isEmpty()) {
            return Optional.empty();
        }
        CookieTokens tokens = parseCookieTokens(cookieOpt.get().getValue());
        if (tokens == null) {
            return Optional.empty();
        }
        String provider = extractCallbackProvider(request);
        if (provider == null) {
            return Optional.empty();
        }
        return Optional.of(new ParsedCallback(
                provider,
                new LookupKey(tokens.handle(), tokens.browserNonce(), state, provider, Purpose.LOGIN, null)
        ));
    }

    private static boolean matchesCallbackContext(
            HttpServletRequest request,
            OAuth2AuthorizationRequest authReq,
            String callbackProvider) {
        String storedProvider = authReq.getAttribute("registration_id");
        if (storedProvider == null || !storedProvider.equals(callbackProvider)) {
            return false;
        }
        String storedRedirect = authReq.getRedirectUri();
        if (storedRedirect == null || storedRedirect.isBlank()) {
            return false;
        }
        URI storedUri = URI.create(storedRedirect);
        String reqUriPath = request.getRequestURI();
        if (reqUriPath == null || !reqUriPath.equals(storedUri.getPath())) {
            return false;
        }
        if (request.getScheme() == null || !request.getScheme().equalsIgnoreCase(storedUri.getScheme())) {
            return false;
        }
        if (request.getServerName() == null || !request.getServerName().equalsIgnoreCase(storedUri.getHost())) {
            return false;
        }
        String hostHeader = request.getHeader("Host");
        int storedPort = effectivePort(storedUri.getScheme(), storedUri.getPort());
        int reqPort = effectivePort(request.getScheme(), request.getServerPort());
        if ((hostHeader != null && hostHeader.contains(":")) || (reqPort != 80 && reqPort != 443)) {
            return reqPort == storedPort;
        }
        return true;
    }

    private static int effectivePort(String scheme, int port) {
        if (port > 0) {
            return port;
        }
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private static String extractBoundedState(HttpServletRequest request) {
        String state = request.getParameter("state");
        if (state == null || state.isBlank() || state.length() > MAX_STATE_LENGTH) {
            return null;
        }
        return state;
    }

    private static String extractCallbackProvider(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return null;
        }
        Matcher matcher = CALLBACK_PROVIDER_PATTERN.matcher(uri);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static Optional<Cookie> findCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return Optional.of(cookie);
            }
        }
        return Optional.empty();
    }

    private static CookieTokens parseCookieTokens(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_COOKIE_VALUE_LENGTH) {
            return null;
        }
        int dot = value.indexOf('.');
        if (dot <= 0 || dot >= value.length() - 1 || value.indexOf('.', dot + 1) >= 0) {
            return null;
        }
        String handle = value.substring(0, dot);
        String nonce = value.substring(dot + 1);
        if (handle.isBlank() || nonce.isBlank()) {
            return null;
        }
        return new CookieTokens(handle, nonce);
    }

    static String cookieNameForState(String state) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(state.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(hash);
            return OAUTH2_REQUEST_COOKIE_PREFIX + hex.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void addCookie(HttpServletResponse response, String name, String value, int maxAge, String path) {
        StringBuilder cookieHeader = new StringBuilder();
        cookieHeader.append(name).append('=').append(value)
                .append("; Path=").append(path)
                .append("; Max-Age=").append(maxAge)
                .append("; HttpOnly")
                .append("; SameSite=Lax");
        if (oAuth2Properties.isCookieSecure()) {
            cookieHeader.append("; Secure");
        }
        response.addHeader("Set-Cookie", cookieHeader.toString());
    }

    private void expireCookie(HttpServletResponse response, String name, String path) {
        addCookie(response, name, "", 0, path);
    }

    private static String randomToken() {
        byte[] bytes = new byte[NONCE_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record CookieTokens(String handle, String browserNonce) {}

    private record ParsedCallback(String provider, LookupKey lookupKey) {}
}
