package com.taskpilot.users.auth.controller;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.users.auth.dto.AuthResponse;
import com.taskpilot.users.auth.dto.LoginRequest;
import com.taskpilot.users.auth.dto.RegisterRequest;
import com.taskpilot.users.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ApiResponse.success(HttpStatus.CREATED.value(),
                "User registered successfully! Please log in.",
                null
        );
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse authResponse = authService.login(request);
        return ApiResponse.success(HttpStatus.OK.value(),
                "User logged in successfully!",
                authResponse
        );
    }
}

