package com.taskpilot.users.auth.controller;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.users.auth.dto.AuthResponse;
import com.taskpilot.users.auth.dto.ForgotPasswordRequest;
import com.taskpilot.users.auth.dto.LoginRequest;
import com.taskpilot.users.auth.dto.RefreshTokenRequest;
import com.taskpilot.users.auth.dto.RegisterRequest;
import com.taskpilot.users.auth.dto.ResetPasswordRequest;
import com.taskpilot.users.auth.service.AuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "01. Authentication", description = "APIs for user authentication, registration, and token management")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @Operation(summary = "Register new user", description = "Create a new user account in the system.")
    @SecurityRequirements()
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ApiResponse.success(HttpStatus.CREATED.value(),
                "User registered successfully! Please log in.",
                null);
    }

    @Operation(summary = "User Login", description = "Authenticate user using email and password. Returns Access Token and Refresh Token.")
    @SecurityRequirements()
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse authResponse = authService.login(request);
        return ApiResponse.success(HttpStatus.OK.value(),
                "User logged in successfully!",
                authResponse);
    }

    @Operation(summary = "Refresh Access Token", description = "Exchange a valid Refresh Token for a new JWT Access Token.")
    @SecurityRequirements()
    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse authResponse = authService.refreshToken(request);
        return ApiResponse.success(HttpStatus.OK.value(),
                "Token refreshed successfully!",
                authResponse);
    }

    @Operation(summary = "User Logout", description = "Revoke the user's refresh token and clear the authentication session. Requires Bearer Token.")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @Valid @RequestBody RefreshTokenRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        authService.logout(request, authorizationHeader);
        return ApiResponse.success(HttpStatus.OK.value(),
                "User logged out successfully!",
                null);
    }

    @Operation(summary = "Forgot Password", description = "Send a password reset link to the user's registered email.")
    @SecurityRequirements()
    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ApiResponse.success(HttpStatus.OK.value(),
                "If the email exists, a password reset link has been sent.",
                null);
    }

    @Operation(summary = "Reset Password", description = "Reset the user's password using the token sent via email.")
    @SecurityRequirements()
    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ApiResponse.success(HttpStatus.OK.value(),
                "Password reset successfully! Please log in again.",
                null);
    }
}
