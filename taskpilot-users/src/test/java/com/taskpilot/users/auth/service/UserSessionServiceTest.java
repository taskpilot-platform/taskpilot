package com.taskpilot.users.auth.service;

import com.taskpilot.contracts.user.dto.SecurityChangeReason;
import com.taskpilot.contracts.user.dto.SessionRevocationReason;
import com.taskpilot.contracts.user.dto.UserSecurityChangedEvent;
import com.taskpilot.contracts.user.dto.UserSessionRevokedEvent;
import com.taskpilot.infrastructure.config.security.JwtProperties;
import com.taskpilot.infrastructure.config.security.JwtService;
import com.taskpilot.infrastructure.config.security.JwtTokenBlocklistService;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.auth.adapter.out.AccountAuthStateAdapter;
import com.taskpilot.users.common.entity.RefreshTokenEntity;
import com.taskpilot.users.common.entity.RefreshTokenHistoryEntity;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.enums.UserStatus;
import com.taskpilot.users.common.repository.RefreshTokenHistoryRepository;
import com.taskpilot.users.common.repository.RefreshTokenRepository;
import com.taskpilot.users.common.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserSessionServiceTest {

    private static final String TEST_SECRET =
            "taskpilot-p12-jwt-signing-hmac-key-0123456789abcdef0123456789abcdef";

    @Mock
    private UserRepository userRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private RefreshTokenHistoryRepository refreshTokenHistoryRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    private JwtService jwtService;
    private UserSessionService userSessionService;
    private AccountAuthStateAdapter accountAuthStateAdapter;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecretKey(TEST_SECRET);
        properties.setExpiration(900_000L);
        jwtService = new JwtService(properties, new JwtTokenBlocklistService(redisTemplateProvider));
        userSessionService = new UserSessionService(
                userRepository,
                refreshTokenRepository,
                refreshTokenHistoryRepository,
                jwtService,
                eventPublisher
        );
        ReflectionTestUtils.setField(userSessionService, "refreshTokenDurationMs", 604_800_000L);
        accountAuthStateAdapter = new AccountAuthStateAdapter(userRepository, refreshTokenRepository);
    }

    @Test
    void issueSession_storesSha256HashNotRawTokenAndRevokesPriorActiveSession() {
        UserEntity user = activeUser(5L, 2L);
        UUID oldSid = UUID.randomUUID();
        RefreshTokenEntity prior = RefreshTokenEntity.builder()
                .id(10L)
                .user(user)
                .sessionId(oldSid)
                .tokenHash("prior-hash")
                .tokenVersion(2L)
                .expiryDate(Instant.now().plusSeconds(3600))
                .build();

        when(userRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(user));
        when(refreshTokenRepository.findActiveByUserIdForUpdate(5L)).thenReturn(List.of(prior));
        when(refreshTokenRepository.saveAndFlush(any(RefreshTokenEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var response = userSessionService.issueSession(5L);

        assertThat(prior.isRevoked()).isTrue();
        assertThat(prior.getRevocationReason()).isEqualTo(SessionRevocationReason.REPLACED_BY_LOGIN.name());

        ArgumentCaptor<RefreshTokenEntity> savedCaptor = ArgumentCaptor.forClass(RefreshTokenEntity.class);
        verify(refreshTokenRepository, org.mockito.Mockito.atLeast(2)).saveAndFlush(savedCaptor.capture());
        RefreshTokenEntity created = savedCaptor.getAllValues().getLast();
        assertThat(created.getTokenHash()).isEqualTo(UserSessionService.hashToken(response.refreshToken()));
        assertThat(created.getTokenHash()).hasSize(64).isNotEqualTo(response.refreshToken());
        assertThat(created.getTokenVersion()).isEqualTo(2L);
    }

    @Test
    void replayOfConsumedToken_revokesOnlyAffectedSessionWithoutGlobalTokenVersionBump() {
        UserEntity user = activeUser(8L, 3L);
        UUID compromisedSid = UUID.randomUUID();
        String consumedRaw = "old-consumed-refresh-token-123456";
        String consumedHash = UserSessionService.hashToken(consumedRaw);

        RefreshTokenEntity activeSessionInCompromisedFamily = RefreshTokenEntity.builder()
                .id(20L)
                .user(user)
                .sessionId(compromisedSid)
                .tokenHash("newer-hash-in-same-sid")
                .tokenVersion(3L)
                .expiryDate(Instant.now().plusSeconds(3600))
                .build();

        RefreshTokenHistoryEntity history = RefreshTokenHistoryEntity.builder()
                .id(1L)
                .sessionId(compromisedSid)
                .userId(8L)
                .tokenHash(consumedHash)
                .consumedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(refreshTokenRepository.findUserIdByTokenHash(consumedHash)).thenReturn(Optional.empty());
        when(refreshTokenHistoryRepository.findByTokenHash(consumedHash)).thenReturn(Optional.of(history));
        when(refreshTokenRepository.findBySessionIdForUpdate(compromisedSid))
                .thenReturn(Optional.of(activeSessionInCompromisedFamily));

        BusinessException ex = catchThrowableOfType(
                BusinessException.class,
                () -> userSessionService.rotateRefreshToken(consumedRaw)
        );

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(activeSessionInCompromisedFamily.isRevoked()).isTrue();
        assertThat(activeSessionInCompromisedFamily.getRevocationReason())
                .isEqualTo(SessionRevocationReason.REPLAY_DETECTED.name());
        assertThat(user.effectiveTokenVersion()).isEqualTo(3L);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(UserSessionRevokedEvent.class);
        UserSessionRevokedEvent revokedEvent = (UserSessionRevokedEvent) eventCaptor.getValue();
        assertThat(revokedEvent.sessionId()).isEqualTo(compromisedSid);
        assertThat(revokedEvent.reason()).isEqualTo(SessionRevocationReason.REPLAY_DETECTED);
    }

    @Test
    void invalidateAll_incrementsEpochRevokesSessionsAndPublishesSecurityEvent() {
        UserEntity user = activeUser(9L, 4L);
        RefreshTokenEntity active = RefreshTokenEntity.builder()
                .id(30L)
                .user(user)
                .sessionId(UUID.randomUUID())
                .tokenHash("active-hash")
                .tokenVersion(4L)
                .expiryDate(Instant.now().plusSeconds(3600))
                .build();

        when(userRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(user));
        when(refreshTokenRepository.findActiveByUserIdForUpdate(9L)).thenReturn(List.of(active));

        long nextVersion = userSessionService.invalidateAll(9L, SecurityChangeReason.PASSWORD_RESET);

        assertThat(nextVersion).isEqualTo(5L);
        assertThat(user.effectiveTokenVersion()).isEqualTo(5L);
        assertThat(active.isRevoked()).isTrue();
        verify(eventPublisher).publishEvent(any(UserSecurityChangedEvent.class));
    }

    @Test
    void accountAuthStateAdapter_mapsLegacyDeactivatedSafelyAndSessionAuthority() {
        UserEntity legacyDisabled = UserEntity.builder()
                .id(15L)
                .email("legacy@taskpilot.io")
                .role(UserRole.USER)
                .status(UserStatus.DEACTIVATED)
                .accountState(AccountLifecycle.ACTIVE)
                .emailVerified(false)
                .tokenVersion(2L)
                .build();
        when(userRepository.findById(15L)).thenReturn(Optional.of(legacyDisabled));

        var dto = accountAuthStateAdapter.findById(15L).orElseThrow();
        assertThat(dto.lifecycle()).isEqualTo("LEGACY_DISABLED");
        assertThat(dto.emailVerified()).isFalse();
        assertThat(dto.tokenVersion()).isEqualTo(2L);
    }

    private static UserEntity activeUser(long id, long version) {
        return UserEntity.builder()
                .id(id)
                .email("user" + id + "@taskpilot.io")
                .fullName("User " + id)
                .role(UserRole.USER)
                .status(UserStatus.AVAILABLE)
                .accountState(AccountLifecycle.ACTIVE)
                .emailVerified(false)
                .tokenVersion(version)
                .build();
    }
}
