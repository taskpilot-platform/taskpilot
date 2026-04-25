package com.taskpilot.contracts.user.port.out;

import java.util.Optional;

public interface UserIdentityPort {

    Optional<Long> findUserIdByEmail(String email);
}
