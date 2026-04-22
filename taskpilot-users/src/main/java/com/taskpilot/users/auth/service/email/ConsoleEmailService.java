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

   @Override
   public void sendPasswordResetEmail(String recipientEmail, String resetToken, long expirationMs) {
      String resetLink = frontendResetUrl + "?token=" + resetToken;
      String expirationText = PasswordResetExpirationFormatter.toMinutesText(expirationMs);
      log.info("Password reset link for {} (expires in {}): {}", recipientEmail, expirationText, resetLink);
   }
}
