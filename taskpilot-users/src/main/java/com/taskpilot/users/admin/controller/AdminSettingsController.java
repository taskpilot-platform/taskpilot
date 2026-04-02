package com.taskpilot.users.admin.controller;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.users.admin.dto.SystemSettingResponse;
import com.taskpilot.users.admin.dto.SystemSettingUpdateRequest;
import com.taskpilot.users.admin.service.AdminSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "04. Admin - System Settings", description = "APIs for managing system configuration (Admin only)")
@RestController
@RequestMapping("/api/v1/admin/settings")
@RequiredArgsConstructor
public class AdminSettingsController {

    private final AdminSettingsService adminSettingsService;

    @Operation(summary = "View Config", description = "Get all system settings (AI heuristic weights, etc.)")
    @GetMapping
    public ApiResponse<List<SystemSettingResponse>> getAllSettings() {
        try {
            List<SystemSettingResponse> settings = adminSettingsService.getAllSettings();
            return ApiResponse.success(HttpStatus.OK.value(), "Settings retrieved successfully", settings);
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    @Operation(summary = "Update Config", description = "Create or update a system setting.")
    @PutMapping
    public ApiResponse<SystemSettingResponse> updateSetting(@Valid @RequestBody SystemSettingUpdateRequest request) {
        return ApiResponse.success(HttpStatus.OK.value(), "Setting updated successfully",
                adminSettingsService.updateSetting(request));
    }
}
