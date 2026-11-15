package com.taskpilot.contracts.user.port.out;

import com.taskpilot.contracts.user.dto.AccountAuthStateDto;

import java.util.Optional;

/**
 * Outbound port providing authoritative account security state by immutable numeric user identifier.
 */
public interface AccountAuthStatePort {

    Optional<AccountAuthStateDto> findById(long userId);
}
