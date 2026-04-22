package com.taskpilot.users.auth.service.email;

public interface EmailService {
   void sendPasswordResetEmail(String recipientEmail, String resetToken, long expirationMs);
}
