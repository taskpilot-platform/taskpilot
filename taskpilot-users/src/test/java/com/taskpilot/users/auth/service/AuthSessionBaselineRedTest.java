package com.taskpilot.users.auth.service;

import com.taskpilot.infrastructure.config.security.JwtProperties;
import com.taskpilot.infrastructure.config.security.JwtService;
import com.taskpilot.infrastructure.config.security.JwtTokenBlocklistService;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2OneTimeCodeStore;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2Properties;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.auth.dto.AuthResponse;
import com.taskpilot.users.auth.dto.LoginRequest;
import com.taskpilot.users.auth.dto.RefreshTokenRequest;
import com.taskpilot.users.auth.service.email.EmailService;
import com.taskpilot.users.common.entity.RefreshTokenEntity;
import com.taskpilot.users.common.entity.RefreshTokenHistoryEntity;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.enums.UserStatus;
import com.taskpilot.users.common.repository.PasswordResetTokenRepository;
import com.taskpilot.users.common.repository.RefreshTokenHistoryRepository;
import com.taskpilot.users.common.repository.RefreshTokenRepository;
import com.taskpilot.users.common.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Verifies P1.2 lifecycle gating, 401 refresh normalization, and atomic refresh rotation.
 */
@ExtendWith(MockitoExtension.class)
class AuthSessionBaselineRedTest {

    private static final String TEST_SECRET =
            "taskpilot-p12-jwt-signing-hmac-key-0123456789abcdef0123456789abcdef";

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private RefreshTokenHistoryRepository refreshTokenHistoryRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private EmailService emailService;
    @Mock
    private PasswordResetThrottleService passwordResetThrottleService;
    @Mock
    private OAuth2OneTimeCodeStore oauth2OneTimeCodeStore;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    private JwtService jwtService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecretKey(TEST_SECRET);
        properties.setExpiration(900_000L);
        jwtService = new JwtService(properties, new JwtTokenBlocklistService(redisTemplateProvider));

        UserSessionService userSessionService = new UserSessionService(
                userRepository,
                refreshTokenRepository,
                refreshTokenHistoryRepository,
                jwtService,
                eventPublisher
        );
        ReflectionTestUtils.setField(userSessionService, "refreshTokenDurationMs", 604_800_000L);

        authService = new AuthService(
                userRepository,
                passwordResetTokenRepository,
                passwordEncoder,
                userSessionService,
                emailService,
                passwordResetThrottleService,
                oauth2OneTimeCodeStore,
                new OAuth2Properties()
        );
    }

    @Test
    void login_whenUserIsDeactivated_mustRejectAndNeverIssueJwtOrRefreshToken() {
        UserEntity deactivated = user(10L, "deactivated@taskpilot.io", UserStatus.DEACTIVATED, AccountLifecycle.SELF_DEACTIVATED);
        when(userRepository.findByEmail("deactivated@taskpilot.io")).thenReturn(Optional.of(deactivated));
        when(passwordEncoder.matches("CorrectPass1!", "encoded-pass")).thenReturn(true);

        BusinessException ex = catchThrowableOfType(
                BusinessException.class,
                () -> authService.login(new LoginRequest("deactivated@taskpilot.io", "CorrectPass1!"))
        );

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value());
    }

    @Test
    void exchangeOAuth2Code_whenUserIsDeactivated_mustRejectAndNeverIssueJwt() {
        UserEntity deactivated = user(11L, "oauth-deactivated@taskpilot.io", UserStatus.DEACTIVATED, AccountLifecycle.LEGACY_DISABLED);
        when(oauth2OneTimeCodeStore.consume("one-time-code")).thenReturn(Optional.of(11L));
        when(userRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(deactivated));

        BusinessException ex = catchThrowableOfType(
                BusinessException.class,
                () -> authService.exchangeOAuth2Code("one-time-code")
        );

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value());
    }

    @Test
    void refreshToken_whenUserIsDeactivated_mustRejectAndNeverIssueJwt() {
        String rawRefresh = "opaque-refresh-token-value-123456";
        String tokenHash = UserSessionService.hashToken(rawRefresh);
        UserEntity deactivated = user(12L, "refresh-deactivated@taskpilot.io", UserStatus.DEACTIVATED, AccountLifecycle.ADMIN_SUSPENDED);
        RefreshTokenEntity stored = RefreshTokenEntity.builder()
                .id(1L)
                .sessionId(UUID.randomUUID())
                .tokenHash(tokenHash)
                .tokenVersion(0L)
                .expiryDate(Instant.now().plusSeconds(3600))
                .user(deactivated)
                .build();

        when(refreshTokenRepository.findUserIdByTokenHash(tokenHash)).thenReturn(Optional.of(12L));
        when(userRepository.findByIdForUpdate(12L)).thenReturn(Optional.of(deactivated));
        when(refreshTokenRepository.findByTokenHashForUpdate(tokenHash)).thenReturn(Optional.of(stored));

        BusinessException ex = catchThrowableOfType(
                BusinessException.class,
                () -> authService.refreshToken(new RefreshTokenRequest(rawRefresh))
        );

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value());
    }

    @Test
    void refreshToken_whenTokenMissing_mustReturn401UnauthorizedInsteadOf404() {
        String unknownRefresh = UUID.randomUUID().toString();
        String unknownHash = UserSessionService.hashToken(unknownRefresh);
        when(refreshTokenRepository.findUserIdByTokenHash(unknownHash)).thenReturn(Optional.empty());
        when(refreshTokenHistoryRepository.findByTokenHash(unknownHash)).thenReturn(Optional.empty());

        BusinessException ex = catchThrowableOfType(
                BusinessException.class,
                () -> authService.refreshToken(new RefreshTokenRequest(unknownRefresh))
        );

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void refreshToken_mustRotateRefreshTokenRatherThanReturningConsumedToken() {
        String oldRefresh = UUID.randomUUID().toString();
        String oldHash = UserSessionService.hashToken(oldRefresh);
        UUID sessionId = UUID.randomUUID();
        UserEntity active = user(13L, "active@taskpilot.io", UserStatus.AVAILABLE, AccountLifecycle.ACTIVE);
        RefreshTokenEntity stored = RefreshTokenEntity.builder()
                .id(2L)
                .sessionId(sessionId)
                .tokenHash(oldHash)
                .tokenVersion(0L)
                .expiryDate(Instant.now().plusSeconds(3600))
                .user(active)
                .build();

        when(refreshTokenRepository.findUserIdByTokenHash(oldHash)).thenReturn(Optional.of(13L));
        when(userRepository.findByIdForUpdate(13L)).thenReturn(Optional.of(active));
        when(refreshTokenRepository.findByTokenHashForUpdate(oldHash)).thenReturn(Optional.of(stored));
        when(refreshTokenHistoryRepository.saveAndFlush(any(RefreshTokenHistoryEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(refreshTokenRepository.saveAndFlush(any(RefreshTokenEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AuthResponse response = authService.refreshToken(new RefreshTokenRequest(oldRefresh));

        assertThat(response.refreshToken())
                .isNotBlank()
                .isNotEqualTo(oldRefresh);
        var claims = jwtService.parseAndValidate(response.token()).orElseThrow();
        assertThat(claims.userId()).isEqualTo(13L);
        assertThat(claims.sessionId()).isEqualTo(sessionId);
    }

    private static UserEntity user(Long id, String email, UserStatus status, AccountLifecycle lifecycle) {
        return UserEntity.builder()
                .id(id)
                .email(email)
                .password("encoded-pass")
                .fullName("Test User")
                .role(UserRole.USER)
                .status(status)
                .accountState(lifecycle)
                .tokenVersion(0L)
                .build();
    }
}
