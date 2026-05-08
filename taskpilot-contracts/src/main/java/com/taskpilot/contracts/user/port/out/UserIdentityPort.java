package com.taskpilot.contracts.user.port.out;

import com.taskpilot.contracts.user.dto.UserIdentityDto;

import java.util.Optional;

public interface UserIdentityPort {

    Optional<UserIdentityDto> findByEmail(String email);
}
