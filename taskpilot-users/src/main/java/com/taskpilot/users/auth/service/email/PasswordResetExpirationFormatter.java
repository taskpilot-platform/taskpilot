package com.taskpilot.users.auth.service.email;

import java.util.Locale;

final class PasswordResetExpirationFormatter {
   private static final long MILLIS_PER_MINUTE = 60_000L;

   private PasswordResetExpirationFormatter() {
   }

   static String toMinutesText(long expirationMs) {
      long normalized = Math.max(expirationMs, 1L);
      long minutes = Math.max(1L, normalized / MILLIS_PER_MINUTE);
      return minutes == 1 ? "1 minute" : String.format(Locale.ROOT, "%d minutes", minutes);
   }
}
