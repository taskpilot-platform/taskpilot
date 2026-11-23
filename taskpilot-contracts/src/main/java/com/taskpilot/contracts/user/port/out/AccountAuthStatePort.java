package com.taskpilot.contracts.user.port.out;

import com.taskpilot.contracts.user.dto.AccountAuthStateDto;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Outbound port providing authoritative account security state by immutable numeric user identifier.
 */
public interface AccountAuthStatePort {

    Optional<AccountAuthStateDto> findById(long userId);

    default Optional<AccountAuthStateDto> findByIdForUpdate(long userId) {
        return findById(userId);
    }

    default Map<Long, AccountAuthStateDto> findByIds(Collection<Long> userIds) {
        Map<Long, AccountAuthStateDto> result = new LinkedHashMap<>();
        if (userIds == null) {
            return result;
        }
        for (Long id : userIds.stream().distinct().sorted().toList()) {
            if (id != null) {
                findById(id).ifPresent(dto -> result.put(id, dto));
            }
        }
        return result;
    }

    /**
     * Acquires pessimistic write locks on the requested user rows in deterministic ascending userId order
     * and returns their authoritative security state within the active transaction.
     */
    default Map<Long, AccountAuthStateDto> lockAndLoadByIds(Collection<Long> userIds) {
        Map<Long, AccountAuthStateDto> result = new LinkedHashMap<>();
        if (userIds == null) {
            return result;
        }
        for (Long id : userIds.stream().distinct().sorted().toList()) {
            if (id != null) {
                findByIdForUpdate(id).ifPresent(dto -> result.put(id, dto));
            }
        }
        return result;
    }
}
