package com.taskpilot.users.auth.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.auth.dto.RegisterRequest;
import com.taskpilot.users.entity.UserEntity;
import com.taskpilot.users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public void register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Email is already registered!");
        }

        UserEntity newUser = UserEntity.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .role(UserEntity.UserRole.USER)
                .build();

        userRepository.save(newUser);
    }
}
