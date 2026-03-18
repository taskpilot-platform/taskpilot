package com.taskpilot.users.auth.service.email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@Profile("prod")
@RequiredArgsConstructor
@Slf4j
public class SmtpEmailService implements EmailService {

   private final JavaMailSender mailSender;

   @Value("${application.security.password-reset.frontend-reset-url:http://localhost:3000/reset-password}")
   private String frontendResetUrl;

   @Value("${application.mail.from:no-reply@taskpilot.local}")
   private String fromEmail;

   @Override
   public void sendPasswordResetEmail(String recipientEmail, String resetToken) {
      String resetLink = frontendResetUrl + "?token=" + resetToken;
      String plainText = "Use the link below to reset your password:\n" + resetLink;
      String htmlContent = buildResetPasswordEmailHtml(resetLink);

      try {
         MimeMessage mimeMessage = mailSender.createMimeMessage();
         MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
         helper.setFrom(fromEmail);
         helper.setTo(recipientEmail);
         helper.setSubject("TaskPilot Password Reset");
         helper.setText(plainText, htmlContent);

         mailSender.send(mimeMessage);
         log.info("Password reset email sent successfully to: {}", recipientEmail);
      } catch (Exception e) {
         log.error("Failed to send password reset email to {}. Token will remain valid for retry. Error: {}",
               recipientEmail, e.getMessage(), e);
         // Intentionally swallow exception to allow client graceful error response
         // Token is already created and stored; email delivery retry can be handled
         // separately
      }
   }

   private String buildResetPasswordEmailHtml(String resetLink) {
      return """
            <!doctype html>
            <html>
            <head>
               <meta charset="UTF-8" />
               <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            </head>
            <body style="margin:0;padding:24px;background:#f5f7fb;font-family:Arial,sans-serif;color:#1f2937;">
               <table role="presentation" width="100%%" cellspacing="0" cellpadding="0">
                  <tr>
                     <td align="center">
                        <table role="presentation" width="600" cellspacing="0" cellpadding="0"
                                 style="max-width:600px;background:#ffffff;border-radius:14px;overflow:hidden;box-shadow:0 8px 24px rgba(15,23,42,.08);">
                           <tr>
                              <td style="background:linear-gradient(135deg,#0f172a,#2563eb);padding:28px;color:#ffffff;">
                                 <h1 style="margin:0;font-size:24px;line-height:1.2;">Reset Your Password</h1>
                                 <p style="margin:10px 0 0;font-size:14px;opacity:.92;">TaskPilot Security Notification</p>
                              </td>
                           </tr>
                           <tr>
                              <td style="padding:28px;">
                                 <p style="margin:0 0 14px;font-size:15px;line-height:1.6;">We received a request to reset your password.</p>
                                 <p style="margin:0 0 20px;font-size:15px;line-height:1.6;">Click the button below to continue:</p>
                                 <p style="margin:0 0 24px;">
                                    <a href="%s"
                                       style="display:inline-block;background:#2563eb;color:#ffffff;text-decoration:none;font-weight:600;padding:12px 20px;border-radius:10px;">Reset Password</a>
                                 </p>
                                 <p style="margin:0 0 8px;font-size:13px;color:#6b7280;line-height:1.6;">If the button does not work, copy and paste this link:</p>
                                 <p style="margin:0;word-break:break-all;font-size:13px;line-height:1.5;"><a href="%s" style="color:#1d4ed8;">%s</a></p>
                              </td>
                           </tr>
                           <tr>
                              <td style="padding:18px 28px;background:#f8fafc;border-top:1px solid #e5e7eb;">
                                 <p style="margin:0;font-size:12px;line-height:1.6;color:#6b7280;">If you did not request this, you can safely ignore this email.</p>
                              </td>
                           </tr>
                        </table>
                     </td>
                  </tr>
               </table>
            </body>
            </html>
            """
            .formatted(resetLink, resetLink, resetLink);
   }
}
