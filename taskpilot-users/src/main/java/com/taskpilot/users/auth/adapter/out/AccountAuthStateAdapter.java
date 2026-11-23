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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    @Transactional
    public Optional<AccountAuthStateDto> findByIdForUpdate(long userId) {
        if (userId <= 0) {
            return Optional.empty();
        }
        return userRepository.findByIdForUpdate(userId).map(AccountAuthStateAdapter::toAccountDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, AccountAuthStateDto> findByIds(Collection<Long> userIds) {
        Map<Long, AccountAuthStateDto> result = new LinkedHashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return result;
        }
        List<Long> sortedIds = userIds.stream().filter(id -> id != null && id > 0).distinct().sorted().toList();
        if (sortedIds.isEmpty()) {
            return result;
        }
        userRepository.findAllById(sortedIds)
                .forEach(user -> result.put(user.getId(), toAccountDto(user)));
        return result;
    }

    @Override
    @Transactional
    public Map<Long, AccountAuthStateDto> lockAndLoadByIds(Collection<Long> userIds) {
        Map<Long, AccountAuthStateDto> result = new LinkedHashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return result;
        }
        List<Long> sortedIds = userIds.stream().filter(id -> id != null && id > 0).distinct().sorted().toList();
        for (Long id : sortedIds) {
            userRepository.findByIdForUpdate(id).ifPresent(user -> result.put(user.getId(), toAccountDto(user)));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SessionAuthStateDto> findById(UUID sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return refreshTokenRepository.findBySessionId(sessionId)
                .map(AccountAuthStateAdapter::toSessionDto);
    }

    @Override
    @Transactional
    public Optional<SessionAuthStateDto> findByIdForUpdate(UUID sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return refreshTokenRepository.findBySessionIdForUpdate(sessionId)
                .map(AccountAuthStateAdapter::toSessionDto);
    }

    private static SessionAuthStateDto toSessionDto(com.taskpilot.users.common.entity.RefreshTokenEntity session) {
        return new SessionAuthStateDto(
                session.getSessionId(),
                session.getUser().getId(),
                session.getTokenVersion() != null ? session.getTokenVersion() : 0L,
                session.getExpiryDate(),
                session.isRevoked()
        );
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
