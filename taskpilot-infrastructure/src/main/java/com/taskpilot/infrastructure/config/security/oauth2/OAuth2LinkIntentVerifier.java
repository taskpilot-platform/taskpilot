package com.taskpilot.infrastructure.config.security.oauth2;

/**
 * Optional SPI implemented by users module to validate authenticated link or re-auth intent tokens
 * before creating a server-side OAuth2 LINK or REAUTH authorization request.
 */
public interface OAuth2LinkIntentVerifier {

    record VerifiedIntent(long userId, String tokenHash) {}

    VerifiedIntent verifyIntent(OAuth2AuthorizationRequestStore.Purpose purpose, String provider, String rawIntentToken);
}
