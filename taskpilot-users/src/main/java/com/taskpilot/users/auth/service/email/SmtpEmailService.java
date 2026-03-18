package com.taskpilot.users.auth.service.email;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@Profile("prod")
@RequiredArgsConstructor
public class SmtpEmailService implements EmailService {

   private final JavaMailSender mailSender;

   @Value("${application.security.password-reset.frontend-reset-url:http://localhost:3000/reset-password}")
   private String frontendResetUrl;

   @Value("${application.mail.from:no-reply@taskpilot.local}")
   private String fromEmail;

   @Override
   public void sendPasswordResetEmail(String recipientEmail, String resetToken) {
      String resetLink = frontendResetUrl + "?token=" + resetToken;

      SimpleMailMessage message = new SimpleMailMessage();
      message.setFrom(fromEmail);
      message.setTo(recipientEmail);
      message.setSubject("TaskPilot Password Reset");
      message.setText("Use the link below to reset your password:\n" + resetLink);

      mailSender.send(message);
   }
}
