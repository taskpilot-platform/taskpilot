package com.taskpilot.infrastructure.config.security.oauth2;

import java.util.Optional;

public interface OAuth2OneTimeCodeStore {

    enum OutcomeType {
        AUTHENTICATED,
        RESTORE_REQUIRED,
        EMAIL_VERIFICATION_REQUIRED,
        OAUTH_EMAIL_COLLISION,
        LINK_COMPLETED,
        REAUTH_COMPLETED
    }

    record ExchangePayload(
            OutcomeType outcome,
            Long userId,
            String email,
            String provider,
            String restoreTicket,
            Long restoreExpiresIn,
            String reauthTicket,
            Long reauthExpiresIn,
            String message
    ) {
        public static ExchangePayload authenticated(long userId) {
            return new ExchangePayload(OutcomeType.AUTHENTICATED, userId, null, null, null, null, null, null, null);
        }

        public static ExchangePayload restoreRequired(long userId, String email, String provider,
                String restoreTicket, long expiresInSeconds) {
            return new ExchangePayload(OutcomeType.RESTORE_REQUIRED, userId, email, provider,
                    restoreTicket, expiresInSeconds, null, null, null);
        }

        public static ExchangePayload emailVerificationRequired(long userId, String email, String provider,
                String message) {
            return new ExchangePayload(OutcomeType.EMAIL_VERIFICATION_REQUIRED, userId, email, provider,
                    null, null, null, null, message);
        }

        public static ExchangePayload oauthEmailCollision(String email, String provider, String message) {
            return new ExchangePayload(OutcomeType.OAUTH_EMAIL_COLLISION, null, email, provider,
                    null, null, null, null, message);
        }

        public static ExchangePayload linkCompleted(long userId, String provider) {
            return new ExchangePayload(OutcomeType.LINK_COMPLETED, userId, null, provider,
                    null, null, null, null, null);
        }

        public static ExchangePayload reauthCompleted(long userId, String provider, String reauthTicket,
                long expiresInSeconds) {
            return new ExchangePayload(OutcomeType.REAUTH_COMPLETED, userId, null, provider,
                    null, null, reauthTicket, expiresInSeconds, null);
        }
    }

    /**
     * Sinh và lưu mã trao đổi một lần liên kết với userId (Outcome AUTHENTICATED).
     */
    String generateAndStore(Long userId);

    /**
     * Sinh và lưu mã trao đổi một lần gắn với outcome cụ thể.
     */
    String generateAndStore(ExchangePayload payload);

    /**
     * Tiêu thụ nguyên tử mã trao đổi cho AUTHENTICATED (chỉ dùng được một lần).
     */
    Optional<Long> consume(String code);

    /**
     * Tiêu thụ nguyên tử mã trao đổi và trả về toàn bộ ExchangePayload (chỉ dùng được một lần).
     */
    Optional<ExchangePayload> consumePayload(String code);
}
