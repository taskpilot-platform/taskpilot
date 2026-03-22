package com.taskpilot.users.profile.controller;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.users.profile.dto.ChangePasswordRequestDTO;
import com.taskpilot.users.profile.dto.UpdateProfileRequestDTO;
import com.taskpilot.users.profile.dto.UserProfileResponseDTO;
import com.taskpilot.users.profile.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "User Profile", description = "APIs for managing user profile")
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    @Operation(summary = "View Profile", description = "Get current user profile information.")
    @GetMapping
    public ApiResponse<UserProfileResponseDTO> getProfile() {
        return ApiResponse.success(HttpStatus.OK.value(), "Profile retrieved successfully",
                profileService.getProfile());
    }

    @Operation(summary = "Update Info", description = "Update user full name and avatar.")
    @PutMapping
    public ApiResponse<UserProfileResponseDTO> updateProfile(@Valid @RequestBody
    UpdateProfileRequestDTO request) {
        return ApiResponse.success(HttpStatus.OK.value(), "Profile updated successfully",
                profileService.updateProfile(request));
    }

    @Operation(summary = "Change Password", description = "Change the password for the current user.")
    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody
    ChangePasswordRequestDTO request) {
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
