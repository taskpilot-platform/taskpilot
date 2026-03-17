package com.taskpilot.users.auth.service;

import com.taskpilot.infrastructure.config.security.JwtService;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.auth.dto.AuthResponse;
import com.taskpilot.users.auth.dto.LoginRequest;
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
    private final JwtService jwtService;

    public void register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Email is already registered!");
        }

        UserEntity newUser = UserEntity.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .role(UserEntity.UserRole.USER)
                .build();

        userRepository.save(newUser);
    }

    public AuthResponse login(LoginRequest request) {
        UserEntity user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Invalid email or password");
        }
        String token = jwtService.generateToken(user.getEmail(), user.getRole().name());

        return new AuthResponse(token, "Bearer", 86400000L);
    }
}
