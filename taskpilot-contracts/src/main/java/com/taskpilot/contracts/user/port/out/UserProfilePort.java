package com.taskpilot.contracts.user.port.out;

import com.taskpilot.contracts.user.dto.UserProfileLiteDto;

import java.util.Optional;

public interface UserProfilePort {

    Optional<UserProfileLiteDto> findLiteById(Long userId);
}
