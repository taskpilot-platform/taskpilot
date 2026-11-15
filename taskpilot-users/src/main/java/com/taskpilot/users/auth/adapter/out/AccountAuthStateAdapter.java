package com.taskpilot.users.auth.adapter.out;

import com.taskpilot.contracts.user.dto.AccountAuthStateDto;
import com.taskpilot.contracts.user.dto.SessionAuthStateDto;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.contracts.user.port.out.SessionAuthStatePort;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.UserStatus;
import com.taskpilot.users.common.repository.RefreshTokenRepository;
import com.taskpilot.users.common.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Authoritative persistence adapter backing {@link AccountAuthStatePort} and {@link SessionAuthStatePort}.
 * Depends strictly on repositories so security filters never create bean cycles with auth services.
 */
@Component
@RequiredArgsConstructor
public class AccountAuthStateAdapter implements AccountAuthStatePort, SessionAuthStatePort {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountAuthStateDto> findById(long userId) {
        if (userId <= 0) {
            return Optional.empty();
        }
        return userRepository.findById(userId).map(AccountAuthStateAdapter::toAccountDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SessionAuthStateDto> findById(UUID sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return refreshTokenRepository.findBySessionId(sessionId)
                .map(session -> new SessionAuthStateDto(
                        session.getSessionId(),
                        session.getUser().getId(),
                        session.getTokenVersion() != null ? session.getTokenVersion() : 0L,
                        session.getExpiryDate(),
                        session.isRevoked()
                ));
    }

    private static AccountAuthStateDto toAccountDto(UserEntity user) {
        AccountLifecycle lifecycle = user.getAccountState() != null
                ? user.getAccountState()
                : AccountLifecycle.ACTIVE;
        if (user.getStatus() == UserStatus.DEACTIVATED && lifecycle == AccountLifecycle.ACTIVE) {
            lifecycle = AccountLifecycle.LEGACY_DISABLED;
        }
        String roleName = user.getRole() != null ? user.getRole().name() : "";
        return new AccountAuthStateDto(
                user.getId(),
                user.getEmail(),
                roleName,
                lifecycle.name(),
                Boolean.TRUE.equals(user.getEmailVerified()),
                user.effectiveTokenVersion()
        );
    }
}
