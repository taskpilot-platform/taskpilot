package com.taskpilot.users.profile.adapter.out;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.taskpilot.contracts.user.dto.UserIdentityDto;
import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.users.common.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class UserProfileAdapter implements UserIdentityPort, UserProfilePort {

    private final UserRepository userRepository;

    @Override
    public Optional<UserIdentityDto> findByEmail(String email) {
        return userRepository.findByEmail(email)
                .map(user -> new UserIdentityDto(user.getId(), user.getEmail()));
    }

    @Override
    public Optional<UserProfileLiteDto> findLiteById(Long userId) {
        return userRepository.findById(userId)
                .map(user -> new UserProfileLiteDto(user.getId(), user.getFullName(), user.getAvatarUrl()));
    }

    @Override
    public List<UserProfileLiteDto> findLiteByIds(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return userRepository.findAllById(userIds).stream()
                .map(user -> new UserProfileLiteDto(user.getId(), user.getFullName(), user.getAvatarUrl()))
                .collect(Collectors.toList());
    }

    @Override
    public List<UserProfileLiteDto> searchLite(String keyword, int limit) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        if (normalizedKeyword.isBlank()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return userRepository.findByKeyword(normalizedKeyword, PageRequest.of(0, safeLimit)).stream()
                .map(user -> new UserProfileLiteDto(user.getId(), user.getFullName(), user.getAvatarUrl()))
                .collect(Collectors.toList());
    }
}
