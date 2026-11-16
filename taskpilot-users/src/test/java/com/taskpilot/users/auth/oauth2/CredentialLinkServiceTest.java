package com.taskpilot.users.auth.oauth2;

import com.taskpilot.infrastructure.config.security.oauth2.OAuth2AuthorizationRequestStore;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.auth.dto.BeginCredentialLinkResponse;
import com.taskpilot.users.auth.service.EmailChallengeService;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.entity.UserIdentityEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.AuthProvider;
import com.taskpilot.users.common.enums.EmailTokenPurpose;
import com.taskpilot.users.common.repository.UserIdentityRepository;
import com.taskpilot.users.common.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CredentialLinkServiceTest {

    @Mock UserRepository userRepository;
    @Mock UserIdentityRepository userIdentityRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock EmailChallengeService emailChallengeService;

    private CredentialLinkService service;

    @BeforeEach
    void setUp() {
        service = new CredentialLinkService(userRepository, userIdentityRepository, passwordEncoder, emailChallengeService);
    }

    @Test
    void beginLinkRejectsWrongPasswordAlreadyLinkedOrUnverifiedAccount() {
        UserEntity unverified = activeUser(1L, "pass-hash", false);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(unverified));
        assertThatThrownBy(() -> service.beginLink(1L, "github", "Pass!123", null))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getStatus()).isEqualTo(403));

        UserEntity active = activeUser(2L, "pass-hash", true);
        when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(active));
        when(userIdentityRepository.existsByUserIdAndProvider(2L, AuthProvider.GITHUB)).thenReturn(true);
        assertThatThrownBy(() -> service.beginLink(2L, "github", "Pass!123", null))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getStatus()).isEqualTo(409));

        when(userIdentityRepository.existsByUserIdAndProvider(2L, AuthProvider.GOOGLE)).thenReturn(false);
        when(passwordEncoder.matches("WrongPass!", "pass-hash")).thenReturn(false);
        assertThatThrownBy(() -> service.beginLink(2L, "google", "WrongPass!", null))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getStatus()).isEqualTo(403));
    }

    @Test
    void beginLinkIssuesScopedChallengeOnValidPasswordOrReauthTicket() {
        UserEntity active = activeUser(3L, "pass-hash", true);
        when(userRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(active));
        when(userIdentityRepository.existsByUserIdAndProvider(3L, AuthProvider.GITHUB)).thenReturn(false);
        when(passwordEncoder.matches("ValidPass!1", "pass-hash")).thenReturn(true);
        when(emailChallengeService.issueForLockedUser(active, EmailTokenPurpose.CREDENTIAL_LINK,
                CredentialLinkService.LINK_INTENT_TTL_SECONDS * 1000L, "GITHUB"))
                .thenReturn(new EmailChallengeService.IssuedChallenge("raw-link-tok", "hash", Instant.now().plusSeconds(300)));

        BeginCredentialLinkResponse resp = service.beginLink(3L, "github", "ValidPass!1", null);
        assertThat(resp.linkToken()).isEqualTo("raw-link-tok");
        assertThat(resp.provider()).isEqualTo("github");
    }

    @Test
    void verifyIntentRejectsUnsupportedPurposeAndDelegatesLinkOrReauth() {
        assertThatThrownBy(() -> service.verifyIntent(OAuth2AuthorizationRequestStore.Purpose.LOGIN, "google", "tok"))
                .isInstanceOf(IllegalArgumentException.class);

        when(emailChallengeService.verifyActiveIntent("raw-tok", EmailTokenPurpose.CREDENTIAL_LINK, "google"))
                .thenReturn(new EmailChallengeService.VerifiedIntentChallenge(7L, "hash-tok", EmailTokenPurpose.CREDENTIAL_LINK, "GOOGLE"));
        var verified = service.verifyIntent(OAuth2AuthorizationRequestStore.Purpose.LINK, "google", "raw-tok");
        assertThat(verified.userId()).isEqualTo(7L);
        assertThat(verified.tokenHash()).isEqualTo("hash-tok");
    }

    @SuppressWarnings("unchecked")
    @Test
    void completeLinkRejectsUnverifiedEmailAndCrossUserCollisionAndCompleteReauthRejectsMismatch() {
        assertThatThrownBy(() -> service.completeLink(5L, "h",
                new OAuth2UserInfo(AuthProvider.GITHUB, "gh-1", "u@example.com", false, "U", null)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getMessage()).isEqualTo("email_unverified"));

        UserEntity user5 = activeUser(5L, null, true);
        UserEntity other = activeUser(99L, null, true);
        UserIdentityEntity boundToOther = UserIdentityEntity.builder()
                .user(other).provider(AuthProvider.GITHUB).providerSubject("gh-1").build();

        doAnswer(inv -> {
            Consumer<UserEntity> mutator = inv.getArgument(4);
            mutator.accept(user5);
            return null;
        }).when(emailChallengeService).consumeByHash(eq("h"), eq(EmailTokenPurpose.CREDENTIAL_LINK), eq(5L), eq("GITHUB"), any());

        when(userIdentityRepository.findByProviderAndProviderSubject(AuthProvider.GITHUB, "gh-1"))
                .thenReturn(Optional.of(boundToOther));

        assertThatThrownBy(() -> service.completeLink(5L, "h",
                new OAuth2UserInfo(AuthProvider.GITHUB, "gh-1", "u@example.com", true, "U", null)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(409);
                    assertThat(ex.getMessage()).isEqualTo("provider_already_bound");
                });
        verify(userIdentityRepository, never()).saveAndFlush(any());

        doAnswer(inv -> {
            Consumer<UserEntity> mutator = inv.getArgument(4);
            mutator.accept(user5);
            return null;
        }).when(emailChallengeService).consumeByHash(eq("h2"), eq(EmailTokenPurpose.CREDENTIAL_LINK), eq(5L), eq("GOOGLE"), any());
        UserIdentityEntity googleOther = UserIdentityEntity.builder()
                .user(other).provider(AuthProvider.GOOGLE).providerSubject("g-other").build();
        when(userIdentityRepository.findByProviderAndProviderSubject(AuthProvider.GOOGLE, "g-other"))
                .thenReturn(Optional.of(googleOther));

        assertThatThrownBy(() -> service.completeReauth(5L, "h2",
                new OAuth2UserInfo(AuthProvider.GOOGLE, "g-other", "other@example.com", true, "Other", null)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(403);
                    assertThat(ex.getMessage()).isEqualTo("provider_reauth_mismatch");
                });
    }

    private static UserEntity activeUser(long id, String passwordHash, boolean emailVerified) {
        return UserEntity.builder()
                .id(id)
                .email("user" + id + "@example.com")
                .password(passwordHash)
                .accountState(AccountLifecycle.ACTIVE)
                .emailVerified(emailVerified)
                .tokenVersion(0L)
                .build();
    }
}
