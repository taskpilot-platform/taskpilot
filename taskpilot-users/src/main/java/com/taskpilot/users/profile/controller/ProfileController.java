package com.taskpilot.users.profile.controller;

import com.taskpilot.infrastructure.config.security.CurrentActorResolver;
import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.users.auth.dto.BeginCredentialLinkRequest;
import com.taskpilot.users.auth.dto.BeginCredentialLinkResponse;
import com.taskpilot.users.auth.oauth2.CredentialLinkService;
import com.taskpilot.users.profile.dto.ChangePasswordRequest;
import com.taskpilot.users.profile.dto.SelfDeactivateRequest;
import com.taskpilot.users.profile.dto.UpdateAvailabilityRequest;
import com.taskpilot.users.profile.dto.UpdateProfileRequest;
import com.taskpilot.users.profile.dto.UserProfileResponse;
import com.taskpilot.users.profile.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "02. User Profile", description = "APIs for managing user profile")
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;
    private final CredentialLinkService credentialLinkService;

    @Operation(summary = "View Profile", description = "Get current user profile information.")
    @GetMapping
    public ApiResponse<UserProfileResponse> getProfile() {
        return ApiResponse.ok("Profile retrieved successfully",
                profileService.getProfile());
    }

    @Operation(summary = "Update Info", description = "Update user full name and avatar.")
    @PutMapping
    public ApiResponse<UserProfileResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok("Profile updated successfully",
                profileService.updateProfile(request));
    }

    @Operation(summary = "Update Availability", description = "Update current user's work availability (AVAILABLE, BUSY, OOO).")
    @PutMapping("/availability")
    public ApiResponse<UserProfileResponse> updateAvailability(@Valid @RequestBody UpdateAvailabilityRequest request) {
        return ApiResponse.ok("Availability updated successfully",
                profileService.updateAvailability(request));
    }

    @Operation(summary = "Upload Avatar", description = "Upload a new avatar image (max 1MB, PNG/JPEG/WebP).")
    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UserProfileResponse> uploadAvatar(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok("Avatar uploaded successfully",
                profileService.uploadAvatar(file));
    }

    @Operation(summary = "Change Password", description = "Change the password for the current password-enabled user.")
    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        profileService.changePassword(request);
        return ApiResponse.ok("Password changed successfully", null);
    }

    @Operation(summary = "Begin OAuth Provider Link", description = "Prove existing credential to begin explicit OAuth provider linking.")
    @PostMapping("/identities/link/begin")
    public ApiResponse<BeginCredentialLinkResponse> beginProviderLink(
            @Valid @RequestBody BeginCredentialLinkRequest request,
            Authentication authentication) {
        long userId = CurrentActorResolver.userId(authentication);
        return ApiResponse.ok("Link challenge issued",
                credentialLinkService.beginLink(userId, request.provider(), request.currentPassword(), request.reauthTicket()));
    }

    @Operation(summary = "Begin OAuth Re-authentication", description = "Begin re-authentication with an already-linked OAuth provider.")
    @PostMapping("/identities/reauth/begin")
    public ApiResponse<BeginCredentialLinkResponse> beginProviderReauth(
            @RequestParam("provider") String provider,
            Authentication authentication) {
        long userId = CurrentActorResolver.userId(authentication);
        return ApiResponse.ok("Re-authentication challenge issued",
                credentialLinkService.beginReauth(userId, provider));
    }

    @Operation(summary = "Self-Deactivate Account", description = "Deactivate own account while preserving identity/history and revoking all sessions.")
    @PostMapping("/self-deactivate")
    public ApiResponse<Void> selfDeactivate(@Valid @RequestBody SelfDeactivateRequest request) {
        profileService.selfDeactivate(request);
        return ApiResponse.ok("Account self-deactivated successfully", null);
    }

    @Operation(summary = "Self-Deactivate Account (Compat)", description = "Deactivate own account with explicit confirmation and fresh credential proof.")
    @DeleteMapping
    public ApiResponse<Void> deleteAccount(@RequestBody(required = false) SelfDeactivateRequest request) {
        profileService.selfDeactivate(request != null ? request : new SelfDeactivateRequest("", null, null));
        return ApiResponse.ok("Account disabled successfully", null);
    }
}
