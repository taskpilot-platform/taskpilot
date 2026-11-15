package com.taskpilot.infrastructure.config.security.oauth2;

import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.time.Instant;
import java.util.Optional;

/**
 * Server-side store for short-lived, single-use OAuth2 authorization requests.
 * Implementations must avoid Java native serialization and enforce atomic single-use consume.
 */
public interface OAuth2AuthorizationRequestStore {

    enum Purpose {
        LOGIN,
        LINK
    }

    record SaveCommand(
            OAuth2AuthorizationRequest authorizationRequest,
            String provider,
            Purpose purpose,
            Long targetUserId,
            String browserNonce,
            String callbackRedirectUri
    ) {}

    record StoredHandle(
            String handle,
            Instant expiresAt
    ) {}

    record LookupKey(
            String handle,
            String browserNonce,
            String state,
            String expectedProvider,
            Purpose expectedPurpose,
            String expectedRedirectUri
    ) {}

    StoredHandle put(SaveCommand command);

    /**
     * Nondestructive lookup for Spring Security loadAuthorizationRequest contract.
     */
    Optional<OAuth2AuthorizationRequest> load(LookupKey key);

    /**
     * Atomic single-use consume for Spring Security removeAuthorizationRequest contract.
     */
    Optional<OAuth2AuthorizationRequest> consume(LookupKey key);

    /**
     * Checks whether a given opaque handle is still active (unconsumed and unexpired).
     */
    boolean isActive(String handle, String browserNonce);

    int purgeExpired();
}
