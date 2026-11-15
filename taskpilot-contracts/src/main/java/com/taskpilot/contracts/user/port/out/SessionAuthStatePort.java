package com.taskpilot.contracts.user.port.out;

import com.taskpilot.contracts.user.dto.SessionAuthStateDto;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port providing durable session authority state by session UUID ({@code sid}).
 */
public interface SessionAuthStatePort {

    Optional<SessionAuthStateDto> findById(UUID sessionId);
}
