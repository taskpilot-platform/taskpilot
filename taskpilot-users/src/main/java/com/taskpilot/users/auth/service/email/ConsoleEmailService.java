package com.taskpilot.users.auth.service.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!prod")
@Slf4j
public class ConsoleEmailService implements EmailService {

    @Value("${application.security.password-reset.frontend-reset-url:http://localhost:5173/reset-password}")
    private String frontendResetUrl;

    @Value("${application.security.password-reset.frontend-verify-url:http://localhost:5173/verify-email}")
    private String frontendVerifyUrl;

    @Value("${application.security.password-reset.frontend-setup-url:http://localhost:5173/setup-password}")
    private String frontendSetupUrl;

    @Override
    public void sendPasswordResetEmail(String recipientEmail, String resetToken, long expirationMs) {
        String expirationText = PasswordResetExpirationFormatter.toMinutesText(expirationMs);
        log.info("Console email [PASSWORD_RESET] queued for {} (expires in {}, endpoint={})",
                recipientEmail, expirationText, frontendResetUrl);
    }

    @Override
    public void sendEmailVerificationEmail(String recipientEmail, String verifyToken, long expirationMs) {
        String expirationText = PasswordResetExpirationFormatter.toMinutesText(expirationMs);
        log.info("Console email [EMAIL_VERIFY] queued for {} (expires in {}, endpoint={})",
                recipientEmail, expirationText, frontendVerifyUrl);
    }

    @Override
    public void sendPasswordSetupEmail(String recipientEmail, String setupToken, long expirationMs) {
        String expirationText = PasswordResetExpirationFormatter.toMinutesText(expirationMs);
        log.info("Console email [PASSWORD_SETUP] queued for {} (expires in {}, endpoint={})",
                recipientEmail, expirationText, frontendSetupUrl);
    }
}
