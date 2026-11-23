package com.taskpilot.users.auth.controller;

import com.taskpilot.infrastructure.config.security.CurrentActorResolver;
import com.taskpilot.infrastructure.config.security.TrustedClientAddressResolver;
import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.users.auth.dto.*;
import com.taskpilot.users.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "01. Authentication", description = "APIs for user authentication, registration, and token management")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final TrustedClientAddressResolver clientAddressResolver;

    @Operation(summary = "Register new user", description = "Create a pending verification user account and send verification email.")
    @SecurityRequirements()
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ApiResponse.created("Registration received. Please check your email to verify and set your password.", null);
    }

    @Operation(summary = "Verify Email and Set Final Password", description = "Complete email verification with mailbox owner's chosen final password.")
    @SecurityRequirements()
    @PostMapping("/verify-email")
    public ApiResponse<Void> verifyEmail(
            @Valid @RequestBody VerifyEmailRequest request,
            HttpServletRequest httpRequest) {
        authService.verifyEmailAndChoosePassword(request, clientAddressResolver.resolve(httpRequest));
        return ApiResponse.ok("Email verified successfully. You may now sign in.", null);
    }

    @Operation(summary = "Resend Verification Email", description = "Resend verification email for unverified local accounts.")
    @SecurityRequirements()
    @PostMapping("/verify-email/resend")
    public ApiResponse<Void> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request,
            HttpServletRequest httpRequest) {
        authService.resendVerification(request, clientAddressResolver.resolve(httpRequest));
        return ApiResponse.ok("If an unverified account exists for this email, a verification link has been sent.", null);
    }

    @Operation(summary = "User Login", description = "Authenticate user using email and password. Returns tokens or discriminated lifecycle outcome.")
    @SecurityRequirements()
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok("Login processed", authService.login(request, clientAddressResolver.resolve(httpRequest)));
    }

    @Operation(summary = "Confirm Account Restore", description = "Explicitly confirm restoration of a self-deactivated account using single-use RESTORE ticket.")
    @SecurityRequirements()
    @PostMapping("/restore/confirm")
    public ApiResponse<AuthResponse> confirmRestore(
            @Valid @RequestBody RestoreAccountRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok("Account restored successfully!",
                authService.confirmRestore(request, clientAddressResolver.resolve(httpRequest)));
    }

    @Operation(summary = "Cancel Account Restore", description = "Discard a pending single-use RESTORE ticket without restoring.")
    @SecurityRequirements()
    @PostMapping("/restore/cancel")
    public ApiResponse<Void> cancelRestore(@Valid @RequestBody RestoreAccountRequest request) {
        authService.cancelRestore(request);
        return ApiResponse.ok("Restore cancelled.", null);
    }

    @Operation(summary = "Request Initial Password Setup", description = "Send a purpose-bound password setup email for an OAuth-only user.")
    @PostMapping("/password-setup/request")
    public ApiResponse<Void> requestPasswordSetup(Authentication authentication, HttpServletRequest httpRequest) {
        long actorUserId = CurrentActorResolver.userId(authentication);
        authService.requestPasswordSetup(actorUserId, clientAddressResolver.resolve(httpRequest));
        return ApiResponse.ok("Password setup email sent.", null);
    }

    @Operation(summary = "Complete Initial Password Setup", description = "Set initial password for an OAuth-only user via single-use email token.")
    @SecurityRequirements()
    @PostMapping("/password-setup/complete")
    public ApiResponse<Void> completePasswordSetup(
            @Valid @RequestBody CompletePasswordSetupRequest request,
            HttpServletRequest httpRequest) {
        authService.completePasswordSetup(request, clientAddressResolver.resolve(httpRequest));
        return ApiResponse.ok("Password setup completed successfully! Please sign in again.", null);
    }

    @Operation(summary = "Refresh Access Token", description = "Exchange a valid Refresh Token for a new JWT Access Token.")
    @SecurityRequirements()
    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.ok("Token refreshed successfully!", authService.refreshToken(request));
    }

    @Operation(summary = "Get enabled OAuth2 providers", description = "Returns the list of enabled OAuth2 social providers (e.g. google, github)")
    @SecurityRequirements()
    @GetMapping("/oauth2/providers")
    public ApiResponse<List<String>> getOAuth2Providers() {
        return ApiResponse.ok("Enabled OAuth2 providers retrieved successfully", authService.getEnabledOAuth2Providers());
    }

    @Operation(summary = "Exchange OAuth2 one-time code", description = "Exchange one-time authorization code for internal TaskPilot JWT tokens or lifecycle outcome")
    @SecurityRequirements()
    @PostMapping("/oauth2/exchange")
    public ApiResponse<AuthResponse> exchangeOAuth2Code(@Valid @RequestBody OAuth2ExchangeRequest request) {
        return ApiResponse.ok("OAuth2 exchange processed", authService.exchangeOAuth2Code(request.code()));
    }

    @Operation(summary = "User Logout", description = "Revoke the user's refresh token and clear the authentication session. Requires Bearer Token.")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @Valid @RequestBody RefreshTokenRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        authService.logout(request, authorizationHeader);
        return ApiResponse.ok("User logged out successfully!", null);
    }

    @Operation(summary = "Forgot Password", description = "Send a password reset link to the user's registered email.")
    @SecurityRequirements()
    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest) {
        authService.forgotPassword(request, clientAddressResolver.resolve(httpRequest));
        return ApiResponse.ok("If the email exists, a password reset link has been sent.", null);
    }

    @Operation(summary = "Reset Password", description = "Reset the user's password using the token sent via email.")
    @SecurityRequirements()
    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request,
            HttpServletRequest httpRequest) {
        authService.resetPassword(request, clientAddressResolver.resolve(httpRequest));
        return ApiResponse.ok("Password reset successfully! Please log in again.", null);
    }
}
