package com.taskpilot.users.auth.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsEightCodePointsAndExactly72Utf8Bytes() {
        assertThatNoException().isThrownBy(() -> policy.validate("Abcd1234"));
        assertThatNoException().isThrownBy(() -> policy.validate("a".repeat(72)));
        // 18 x 4-byte emoji = 72 UTF-8 bytes and 18 code points
        assertThatNoException().isThrownBy(() -> policy.validate("\uD83D\uDE00".repeat(18)));
    }

    @Test
    void rejectsBelowEightCodePointsAndAbove72Utf8BytesWithoutTruncation() {
        assertThatThrownBy(() -> policy.validate("Abc1234"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.validate("a".repeat(73)))
                .isInstanceOf(BusinessException.class);
        // 19 x 4-byte emoji = 76 UTF-8 bytes (> 72 bytes even though 19 code points)
        assertThatThrownBy(() -> policy.validate("\uD83D\uDE00".repeat(19)))
                .isInstanceOf(BusinessException.class);
    }
}
