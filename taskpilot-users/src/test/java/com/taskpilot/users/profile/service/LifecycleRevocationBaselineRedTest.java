package com.taskpilot.users.profile.service;

import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.contracts.user.dto.SecurityChangeReason;
import com.taskpilot.infrastructure.config.security.TaskPilotPrincipal;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.storage.StorageService;
import com.taskpilot.users.admin.dto.AdminUpdateUserRequest;
import com.taskpilot.users.admin.service.AdminUserService;
import com.taskpilot.users.auth.service.UserSessionService;
import com.taskpilot.users.auth.service.email.EmailService;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.enums.UserStatus;
import com.taskpilot.users.common.repository.PasswordResetTokenRepository;
import com.taskpilot.users.common.repository.UserIdentityRepository;
import com.taskpilot.users.common.repository.UserRepository;
import com.taskpilot.users.profile.dto.ChangePasswordRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies lifecycle/credential mutations revoke sessions and advance security epoch cleanly.
 */
@ExtendWith(MockitoExtension.class)
class LifecycleRevocationBaselineRedTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserIdentityRepository userIdentityRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private UserSessionService userSessionService;
    @Mock
    private StorageService storageService;
    @Mock
    private EmailService emailService;
    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void changePassword_mustInvalidateAllUserSessionsAndAdvanceSecurityEpoch() {
        ProfileService profileService = new ProfileService(
                userRepository, userIdentityRepository, passwordEncoder, userSessionService, storageService);

        UserEntity user = UserEntity.builder()
                .id(42L)
                .email("alice@taskpilot.io")
                .password("encoded-old")
                .role(UserRole.USER)
                .status(UserStatus.AVAILABLE)
                .accountState(AccountLifecycle.ACTIVE)
                .tokenVersion(1L)
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new TaskPilotPrincipal(new CurrentActor(42L, "alice@taskpilot.io", "USER", 1L)),
                        null,
                        List.of()));
        when(userRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPass1!", "encoded-old")).thenReturn(true);
        when(passwordEncoder.encode("NewPass1!")).thenReturn("encoded-new");

        profileService.changePassword(new ChangePasswordRequest("OldPass1!", "NewPass1!"));

        verify(userSessionService, atLeastOnce()).invalidateAll(42L, SecurityChangeReason.PASSWORD_CHANGED);
    }

    @Test
    void deactivateUser_whenPrincipalIsNumericUidMatchingTarget_mustBlockSelfDeactivation() {
        AdminUserService adminUserService = new AdminUserService(
                userRepository, passwordEncoder, emailService, passwordResetTokenRepository, userSessionService);

        UserEntity selfAdmin = UserEntity.builder()
                .id(99L)
                .email("admin@taskpilot.io")
                .role(UserRole.ADMIN)
                .status(UserStatus.AVAILABLE)
                .accountState(AccountLifecycle.ACTIVE)
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new TaskPilotPrincipal(new CurrentActor(99L, "admin@taskpilot.io", "ADMIN", 0L)),
                        null,
                        List.of()));
        when(userRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(selfAdmin));

        BusinessException ex = catchThrowableOfType(
                BusinessException.class,
                () -> adminUserService.deactivateUser(99L)
        );

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        verify(userRepository, never()).save(selfAdmin);
    }

    @Test
    void updateUser_whenRoleChanges_mustInvalidateTargetSessions() {
        AdminUserService adminUserService = new AdminUserService(
                userRepository, passwordEncoder, emailService, passwordResetTokenRepository, userSessionService);

        UserEntity target = UserEntity.builder()
                .id(42L)
                .email("member@taskpilot.io")
                .role(UserRole.ADMIN)
                .status(UserStatus.AVAILABLE)
                .accountState(AccountLifecycle.ACTIVE)
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new TaskPilotPrincipal(new CurrentActor(99L, "admin@taskpilot.io", "ADMIN", 0L)),
                        null,
                        List.of()));
        when(userRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(target));

        adminUserService.updateUser(42L, new AdminUpdateUserRequest(UserRole.USER, null, null));

        verify(userSessionService).invalidateAll(42L, SecurityChangeReason.ROLE_CHANGED);
    }
}
