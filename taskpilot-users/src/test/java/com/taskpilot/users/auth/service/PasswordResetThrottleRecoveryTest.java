package com.taskpilot.users.auth.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PasswordResetThrottleRecoveryTest {
    @Test
    @SuppressWarnings("unchecked")
    void caseVariantsShareResetCooldownInTurkishLocale() {
        ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);
        var service = new PasswordResetThrottleService(redis);
        ReflectionTestUtils.setField(service, "provider", "memory");
        ReflectionTestUtils.setField(service, "cooldownSeconds", 60L);
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            service.checkAndConsume("I@Example.Test", "127.0.0.1");

            assertThatThrownBy(() -> service.checkAndConsume("i@example.test", "127.0.0.1"))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getStatus()).isEqualTo(429));
        } finally {
            Locale.setDefault(original);
        }
    }
}
