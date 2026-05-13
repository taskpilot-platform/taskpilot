package com.taskpilot.contracts.user.port.out;

import com.taskpilot.contracts.user.dto.UserProfileLiteDto;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface UserProfilePort {

    Optional<UserProfileLiteDto> findLiteById(Long userId);

    List<UserProfileLiteDto> findLiteByIds(Set<Long> userIds);

    List<UserProfileLiteDto> searchLite(String keyword, int limit);
}
