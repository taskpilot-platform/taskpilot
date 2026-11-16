package com.taskpilot.users.auth.dto;

public record BeginCredentialLinkResponse(
        String linkToken,
        String provider,
        long expiresInSeconds
) {}
