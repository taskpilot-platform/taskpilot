package com.taskpilot.users.auth.service;

import com.taskpilot.infrastructure.config.security.JwtService;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2OneTimeCodeStore;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2Properties;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.auth.dto.ForgotPasswordRequest;
import com.taskpilot.users.auth.dto.LoginRequest;
import com.taskpilot.users.auth.dto.RegisterRequest;
import com.taskpilot.users.auth.service.email.EmailService;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.repository.PasswordResetTokenRepository;
import com.taskpilot.users.common.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceRecoveryTest {
    @Mock UserRepository users;
    @Mock PasswordResetTokenRepository resets;
    @Mock PasswordEncoder passwords;
    @Mock JwtService jwt;
    @Mock RefreshTokenService refresh;
    @Mock EmailService mail;
    @Mock PasswordResetThrottleService throttle;
    @Mock OAuth2OneTimeCodeStore codes;
    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(users, resets, passwords, jwt, refresh, mail, throttle,
                codes, new OAuth2Properties());
    }

    @Test
    void registrationChecksCanonicalEmailAndStoresCanonicalValue() {
        when(passwords.encode("password123")).thenReturn("hash");
        service.register(new RegisterRequest(" Person@Example.Test ", "password123", "Test"));
        verify(users).existsByEmailIgnoreCase("person@example.test");
        var captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("person@example.test");
    }

    @Test
    void duplicateCaseVariantIs409AndNeverWrites() {
        when(users.existsByEmailIgnoreCase("person@example.test")).thenReturn(true);
        assertThatThrownBy(() -> service.register(new RegisterRequest("PERSON@example.test", "password123", "Test")))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getStatus()).isEqualTo(409));
        verify(users, never()).save(any());
        verifyNoInteractions(passwords);
    }

    @Test
    void normalizingEmailDoesNotDependOnServerLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            when(passwords.encode("password123")).thenReturn("hash");
            service.register(new RegisterRequest("I@Example.Test", "password123", "Test"));
            verify(users).existsByEmailIgnoreCase("i@example.test");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void loginLooksUpCanonicalEmailWithoutMutatingExistingAccounts() {
        when(users.findByEmail("person@example.test")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.login(new LoginRequest(" PERSON@example.test ", "password123")))
                .isInstanceOf(BusinessException.class);
        verify(users).findByEmail("person@example.test");
        verify(users, never()).save(any());
    }

    @Test
    void passwordResetUsesSameCanonicalEmail() {
        when(users.findByEmail("person@example.test")).thenReturn(Optional.empty());
        service.forgotPassword(new ForgotPasswordRequest(" PERSON@example.test "), "127.0.0.1");
        verify(users).findByEmail("person@example.test");
        verifyNoInteractions(mail);
    }
}
