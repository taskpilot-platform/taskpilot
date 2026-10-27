package com.taskpilot.users.auth.oauth2;

import com.taskpilot.users.common.enums.AuthProvider;

public record OAuth2UserInfo(
        AuthProvider provider,
        String providerSubject,
        String email,
        boolean emailVerified,
        String fullName,
        String avatarUrl) {
}
