package com.taskpilot.infrastructure.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class P6SpyLogRedactionTest {

    private final P6SpySqlFormatter formatter = new P6SpySqlFormatter();

    @Test
    void formatterDoesNotEmitBoundPasswordsTokensOrJdbcUrlCredentials() {
        String formatted = formatter.formatMessage(
                7,
                "2026-11-15T21:00:00Z",
                12L,
                "statement",
                "UPDATE public.users SET password_hash = ? WHERE email = ?",
                "UPDATE public.users SET password_hash = 'SuperSecretPass123!' WHERE email = 'user@example.test'",
                "jdbc:postgresql://localhost:5432/taskpilot?password=DbSecret999"
        );

        assertThat(formatted)
                .doesNotContain("SuperSecretPass123!")
                .doesNotContain("user@example.test")
                .doesNotContain("DbSecret999");
    }

    @Test
    void formatterDoesNotEmitOAuthCodesOrRefreshResetTickets() {
        String formatted = formatter.formatMessage(
                8,
                "2026-11-15T21:00:00Z",
                5L,
                "statement",
                "INSERT INTO public.refresh_tokens (token) VALUES (?)",
                "INSERT INTO public.refresh_tokens (token) VALUES ('raw-refresh-ticket-999-oauth-code-xyz')",
                "jdbc:postgresql://localhost:5432/taskpilot"
        );

        assertThat(formatted).doesNotContain("raw-refresh-ticket-999-oauth-code-xyz");
    }
}
