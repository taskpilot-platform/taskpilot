package com.taskpilot.users.admin.controller;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.users.admin.dto.AdminCreateUserRequest;
import com.taskpilot.users.admin.dto.AdminUpdateUserRequest;
import com.taskpilot.users.admin.dto.AdminUserResponse;
import com.taskpilot.users.admin.service.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "06. Admin - User Management", description = "APIs for managing system users (Admin only)")
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @Operation(summary = "View Global User List", description = "Get all users with pagination.")
    @GetMapping
    public ApiResponse<Page<AdminUserResponse>> getAllUsers(
            @PageableDefault(size = 10) Pageable pageable) {
        return ApiResponse.success(HttpStatus.OK.value(), "Users retrieved successfully",
                adminUserService.getAllUsers(pageable));
    }

    @Operation(summary = "Add System User", description = "Create a new user with auto-generated password.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminUserResponse> createUser(@Valid @RequestBody AdminCreateUserRequest request) {
        return ApiResponse.success(HttpStatus.CREATED.value(), "User created successfully",
                adminUserService.createUser(request));
    }

    @Operation(summary = "Edit System User", description = "Update user role, status, or workload.")
    @PutMapping("/{id}")
    public ApiResponse<AdminUserResponse> updateUser(@PathVariable Long id,
            @Valid @RequestBody AdminUpdateUserRequest request) {
        return ApiResponse.success(HttpStatus.OK.value(), "User updated successfully",
                adminUserService.updateUser(id, request));
    }

    @Operation(summary = "Delete System User", description = "Soft delete: set user status to DEACTIVATED.")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deactivateUser(@PathVariable Long id) {
        adminUserService.deactivateUser(id);
        return ApiResponse.success(HttpStatus.OK.value(), "User deactivated successfully", null);
    }

    @Operation(summary = "Reset User Password", description = "Generate a new random password for the user.")
    @PutMapping("/{id}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable Long id) {
        adminUserService.resetPassword(id);
        return ApiResponse.success(HttpStatus.OK.value(),
                "Password reset successfully. New password will be sent via email.", null);
    }
}
