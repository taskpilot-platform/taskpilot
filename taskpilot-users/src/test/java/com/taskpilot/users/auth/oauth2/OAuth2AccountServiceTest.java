package com.taskpilot.users.auth.oauth2;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.entity.UserIdentityEntity;
import com.taskpilot.users.common.enums.AuthProvider;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.enums.UserStatus;
import com.taskpilot.users.common.repository.UserIdentityRepository;
import com.taskpilot.users.common.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth2AccountService Unit Tests")
class OAuth2AccountServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserIdentityRepository userIdentityRepository;

    private OAuth2AccountService service;

    @BeforeEach
    void setUp() {
        service = new OAuth2AccountService(userRepository, userIdentityRepository);
    }

    @Test
    @DisplayName("Should return user from existing identity and update last login")
    void testExistingIdentity() {
        UserEntity user = new UserEntity();
        user.setId(10L);
        user.setEmail("existing@example.com");

        UserIdentityEntity identity = new UserIdentityEntity();
        identity.setUser(user);
        identity.setProvider(AuthProvider.GOOGLE);
        identity.setProviderSubject("sub-123");

        when(userIdentityRepository.findByProviderAndProviderSubject(AuthProvider.GOOGLE, "sub-123"))
                .thenReturn(Optional.of(identity));

        OAuth2UserInfo userInfo = new OAuth2UserInfo(
                AuthProvider.GOOGLE, "sub-123", "existing@example.com", true, "Name", null);

        UserEntity resolved = service.resolveUser(userInfo);

        assertSame(user, resolved);
        verify(userIdentityRepository).save(identity);
        assertNotNull(identity.getLastLoginAt());
    }

    @Test
    @DisplayName("Should reject unverified email when no existing identity")
    void testUnverifiedEmailRejected() {
        when(userIdentityRepository.findByProviderAndProviderSubject(AuthProvider.GITHUB, "sub-999"))
                .thenReturn(Optional.empty());

        OAuth2UserInfo userInfo = new OAuth2UserInfo(
                AuthProvider.GITHUB, "sub-999", "unverified@example.com", false, "Name", null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.resolveUser(userInfo));
        assertEquals("email_unverified", ex.getMessage());
    }

    @Test
    @DisplayName("Should link new identity to existing user with verified matching email")
    void testLinkExistingUser() {
        when(userIdentityRepository.findByProviderAndProviderSubject(AuthProvider.GOOGLE, "sub-456"))
                .thenReturn(Optional.empty());

        UserEntity existingUser = new UserEntity();
        existingUser.setId(20L);
        existingUser.setEmail("match@example.com");
        existingUser.setFullName("Existing User");

        when(userRepository.findByEmail("match@example.com")).thenReturn(Optional.of(existingUser));

        OAuth2UserInfo userInfo = new OAuth2UserInfo(
                AuthProvider.GOOGLE, "sub-456", "match@example.com", true, "New Name", "https://img.com/a.jpg");

        UserEntity resolved = service.resolveUser(userInfo);

        assertSame(existingUser, resolved);

        ArgumentCaptor<UserIdentityEntity> captor = ArgumentCaptor.forClass(UserIdentityEntity.class);
        verify(userIdentityRepository).save(captor.capture());

        UserIdentityEntity savedIdentity = captor.getValue();
        assertEquals(AuthProvider.GOOGLE, savedIdentity.getProvider());
        assertEquals("sub-456", savedIdentity.getProviderSubject());
        assertEquals(existingUser, savedIdentity.getUser());
    }

    @Test
    @DisplayName("Should provision new password-less user and identity for first-time social login")
    void testProvisionNewUser() {
        when(userIdentityRepository.findByProviderAndProviderSubject(AuthProvider.GITHUB, "sub-777"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("newbie@example.com")).thenReturn(Optional.empty());

        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity u = invocation.getArgument(0);
            u.setId(99L);
            return u;
        });

        OAuth2UserInfo userInfo = new OAuth2UserInfo(
                AuthProvider.GITHUB, "sub-777", "newbie@example.com", true, "New Developer", "https://avatar.com/u");

        UserEntity resolved = service.resolveUser(userInfo);

        assertNotNull(resolved);
        assertEquals(99L, resolved.getId());
        assertEquals("newbie@example.com", resolved.getEmail());
        assertEquals("New Developer", resolved.getFullName());
        assertNull(resolved.getPassword()); // Password-less
        assertEquals(UserRole.USER, resolved.getRole());
        assertEquals(UserStatus.AVAILABLE, resolved.getStatus());

        verify(userIdentityRepository).save(any(UserIdentityEntity.class));
    }
}
