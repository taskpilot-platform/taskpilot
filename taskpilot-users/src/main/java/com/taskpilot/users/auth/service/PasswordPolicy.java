package com.taskpilot.users.auth.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Canonical server password policy applied uniformly across registration, email verification,
 * password setup, password reset, profile password change, and admin credential writes.
 * Validates minimum 8 Unicode code points and maximum 72 UTF-8 bytes before BCrypt without truncation.
 */
@Component
public class PasswordPolicy {

    public static final int MIN_CODE_POINTS = 8;
    public static final int MAX_UTF8_BYTES = 72;

    public void validate(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Password is required");
        }
        int codePoints = rawPassword.codePointCount(0, rawPassword.length());
        if (codePoints < MIN_CODE_POINTS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Password must be at least " + MIN_CODE_POINTS + " characters");
        }
        int utf8Bytes = rawPassword.getBytes(StandardCharsets.UTF_8).length;
        if (utf8Bytes > MAX_UTF8_BYTES) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Password must not exceed " + MAX_UTF8_BYTES + " UTF-8 bytes");
        }
    }
}
