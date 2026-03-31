package com.taskpilot.users.profile.controller;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.users.profile.dto.ChangePasswordRequest;
import com.taskpilot.users.profile.dto.UpdateProfileRequest;
import com.taskpilot.users.profile.dto.UserProfileResponse;
import com.taskpilot.users.profile.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "2. User Profile", description = "APIs for managing user profile")
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    @Operation(summary = "View Profile", description = "Get current user profile information.")
    @GetMapping
    public ApiResponse<UserProfileResponse> getProfile() {
        return ApiResponse.success(HttpStatus.OK.value(), "Profile retrieved successfully",
                profileService.getProfile());
    }

    @Operation(summary = "Update Info", description = "Update user full name and avatar.")
    @PutMapping
    public ApiResponse<UserProfileResponse> updateProfile(@Valid @RequestBody
    UpdateProfileRequest request) {
        return ApiResponse.success(HttpStatus.OK.value(), "Profile updated successfully",
                profileService.updateProfile(request));
    }

    @Operation(summary = "Change Password", description = "Change the password for the current user.")
    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody
    ChangePasswordRequest request) {
        profileService.changePassword(request);
        return ApiResponse.success(HttpStatus.OK.value(), "Password changed successfully", null);
    }

    @Operation(summary = "Delete Account", description = "Soft delete the user account and revoke session.")
    @DeleteMapping
    public ApiResponse<Void> deleteAccount() {
        profileService.deleteAccount();
        return ApiResponse.success(HttpStatus.OK.value(), "Account disabled successfully", null);
    }
}
