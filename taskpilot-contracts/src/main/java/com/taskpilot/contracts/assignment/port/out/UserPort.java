package com.taskpilot.contracts.assignment.port.out;

import java.util.Optional;

import com.taskpilot.contracts.assignment.dto.UserProfileDto;

public interface UserPort {

    Optional<UserProfileDto> findById(Long userId);
}
