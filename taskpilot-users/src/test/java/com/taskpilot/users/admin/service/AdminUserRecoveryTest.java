package com.taskpilot.users.admin.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.admin.dto.AdminCreateUserRequest;
import com.taskpilot.users.auth.service.email.EmailService;
import com.taskpilot.users.common.entity.PasswordResetTokenEntity;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.UserRole;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminUserRecoveryTest {
    @Mock UserRepository users;
    @Mock PasswordEncoder passwords;
    @Mock EmailService mail;
    @Mock PasswordResetTokenRepository resets;
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(users, passwords, mail, resets);
    }

    @Test
    void adminCreatedAccountUsesCanonicalEmailForStorageAndResetDelivery() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            when(passwords.encode(anyString())).thenReturn("hash");
            when(resets.save(any(PasswordResetTokenEntity.class))).thenAnswer(call -> call.getArgument(0));

            service.createUser(new AdminCreateUserRequest(" I@Example.Test ", "Recovery test", UserRole.USER));

            verify(users).existsByEmailIgnoreCase("i@example.test");
            var captor = ArgumentCaptor.forClass(UserEntity.class);
            verify(users).save(captor.capture());
            assertThat(captor.getValue().getEmail()).isEqualTo("i@example.test");
            verify(mail).sendPasswordResetEmail(eq("i@example.test"), anyString(), anyLong());
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void duplicateAdminCreationDoesNotWriteOrSendResetEmail() {
        when(users.existsByEmailIgnoreCase("person@example.test")).thenReturn(true);

        assertThatThrownBy(() -> service.createUser(
                new AdminCreateUserRequest("PERSON@example.test", "Recovery test", UserRole.USER)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getStatus()).isEqualTo(409));

        verify(users, never()).save(any());
        verifyNoInteractions(passwords, resets, mail);
    }
}
