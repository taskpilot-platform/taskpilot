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
import org.springframework.web.bind.annotation.*;

@Tag(name = "06. Admin - User Management", description = "APIs for managing system users (Admin only)")
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @Operation(summary = "View Global User List", description = "Get all users with pagination and optional keyword search.")
    @GetMapping
    public ApiResponse<Page<AdminUserResponse>> getAllUsers(
            @RequestParam(required = false) String keyword,
            @PageableDefault(size = 10) Pageable pageable) {
        return ApiResponse.ok("Users retrieved successfully",
                adminUserService.getAllUsers(keyword, pageable));
    }

    @Operation(summary = "View User Detail", description = "Get detailed information of a system user by id.")
    @GetMapping("/{id}")
    public ApiResponse<AdminUserResponse> getUserDetail(@PathVariable Long id) {
        return ApiResponse.ok("User retrieved successfully",
                adminUserService.getUserDetail(id));
    }

    @Operation(summary = "Add System User", description = "Provision a new pending-verification user and send verification/setup link.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminUserResponse> createUser(@Valid @RequestBody AdminCreateUserRequest request) {
        return ApiResponse.created("User created successfully",
                adminUserService.createUser(request));
    }

    @Operation(summary = "Edit System User", description = "Update user role, availability, or workload.")
    @PutMapping("/{id}")
    public ApiResponse<AdminUserResponse> updateUser(@PathVariable Long id,
            @Valid @RequestBody AdminUpdateUserRequest request) {
        return ApiResponse.ok("User updated successfully",
                adminUserService.updateUser(id, request));
    }

    @Operation(summary = "Suspend System User", description = "Administratively suspend user immediately and queue project recovery.")
    @PostMapping("/{id}/suspend")
    public ApiResponse<AdminUserResponse> suspendUser(
            @PathVariable Long id,
            @RequestParam(required = false) String reason) {
        return ApiResponse.ok("User suspended successfully",
                adminUserService.suspendUser(id, reason));
    }

    @Operation(summary = "Reactivate System User", description = "Administratively reactivate a verified suspended/disabled user.")
    @PostMapping("/{id}/reactivate")
    public ApiResponse<AdminUserResponse> reactivateUser(
            @PathVariable Long id,
            @RequestParam(required = false) String reason) {
        return ApiResponse.ok("User reactivated successfully",
                adminUserService.reactivateUser(id, reason));
    }

    @Operation(summary = "Suspend System User (Compat)", description = "Administratively suspend user account.")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deactivateUser(@PathVariable Long id) {
        adminUserService.deactivateUser(id);
        return ApiResponse.ok("User deactivated successfully", null);
    }

    @Operation(summary = "Reset User Password", description = "Invalidate existing password/sessions and send a purpose-bound reset link.")
    @PutMapping("/{id}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable Long id) {
        adminUserService.resetPassword(id);
        return ApiResponse.ok("Password reset link sent.", null);
    }
}
