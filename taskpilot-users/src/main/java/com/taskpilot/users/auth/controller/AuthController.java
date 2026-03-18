package com.taskpilot.users.auth.controller;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.users.auth.dto.AuthResponse;
import com.taskpilot.users.auth.dto.ForgotPasswordRequest;
import com.taskpilot.users.auth.dto.LoginRequest;
import com.taskpilot.users.auth.dto.RefreshTokenRequest;
import com.taskpilot.users.auth.dto.RegisterRequest;
import com.taskpilot.users.auth.dto.ResetPasswordRequest;
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
                null);
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse authResponse = authService.login(request);
        return ApiResponse.success(HttpStatus.OK.value(),
                "User logged in successfully!",
                authResponse);
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse authResponse = authService.refreshToken(request);
        return ApiResponse.success(HttpStatus.OK.value(),
                "Token refreshed successfully!",
                authResponse);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request);
        return ApiResponse.success(HttpStatus.OK.value(),
                "User logged out successfully!",
                null);
    }

    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ApiResponse.success(HttpStatus.OK.value(),
                "If the email exists, a password reset link has been sent.",
                null);
    }

    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ApiResponse.success(HttpStatus.OK.value(),
                "Password reset successfully! Please log in again.",
                null);
    }
}
