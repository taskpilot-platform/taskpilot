package com.taskpilot.users.auth.service.email;

public interface EmailService {
    void sendPasswordResetEmail(String recipientEmail, String resetToken, long expirationMs);

    default void sendEmailVerificationEmail(String recipientEmail, String verifyToken, long expirationMs) {
        sendPasswordResetEmail(recipientEmail, verifyToken, expirationMs);
    }

    default void sendPasswordSetupEmail(String recipientEmail, String setupToken, long expirationMs) {
        sendPasswordResetEmail(recipientEmail, setupToken, expirationMs);
    }
}
