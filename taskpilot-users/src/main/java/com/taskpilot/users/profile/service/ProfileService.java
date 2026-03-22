package com.taskpilot.users.profile.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.entity.UserEntity;
import com.taskpilot.users.profile.dto.ChangePasswordRequestDTO;
import com.taskpilot.users.profile.dto.UpdateProfileRequestDTO;
import com.taskpilot.users.profile.dto.UserProfileResponseDTO;
import com.taskpilot.users.repository.UserRepository;
import com.taskpilot.users.auth.service.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProfileService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;

    private UserEntity getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));
    }

    public UserProfileResponseDTO getProfile() {
        UserEntity user = getCurrentUser();
        return UserProfileResponseDTO.fromEntity(user);
    }

    public UserProfileResponseDTO updateProfile(UpdateProfileRequestDTO request) {
        UserEntity user = getCurrentUser();
        user.setFullName(request.fullName());
        if (request.avatarUrl() != null) {
            user.setAvatarUrl(request.avatarUrl());
        }
        userRepository.save(user);
        return getProfile();
    }

    public void changePassword(ChangePasswordRequestDTO request) {
        UserEntity user = getCurrentUser();
        if (!passwordEncoder.matches(request.oldPassword(), user.getPassword())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Old password does not match");
        }
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }

    public void deleteAccount() {
        UserEntity user = getCurrentUser();
        user.setStatus(UserEntity.UserStatus.DEACTIVATED);
        user.setEmail(user.getEmail() + "_deleted_" + System.currentTimeMillis());
        userRepository.save(user);

        // Revoke refresh token to force logout
        refreshTokenService.deleteByUser(user);
    }
}
