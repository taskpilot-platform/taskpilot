package com.taskpilot.users.common.enums;

public enum AuthProvider {
    LOCAL,
    GOOGLE,
    GITHUB;

    public static AuthProvider fromString(String provider) {
        if (provider == null || provider.isBlank()) {
            return LOCAL;
        }
        try {
            return AuthProvider.valueOf(provider.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return LOCAL;
        }
    }
}
