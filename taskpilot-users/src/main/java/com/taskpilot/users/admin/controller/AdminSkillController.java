package com.taskpilot.users.admin.controller;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.users.admin.dto.AdminSkillRequest;
import com.taskpilot.users.admin.dto.AdminSkillResponse;
import com.taskpilot.users.admin.service.AdminSkillService;
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

@Tag(name = "5. Admin - Skill Directory", description = "APIs for managing the system skill directory (Admin only)")
@RestController
@RequestMapping("/api/v1/admin/skills")
@RequiredArgsConstructor
public class AdminSkillController {

    private final AdminSkillService adminSkillService;

    @Operation(summary = "View Directory", description = "Get all skills in the system with search and pagination.")
    @GetMapping
    public ApiResponse<Page<AdminSkillResponse>> getAllSkills(
            @RequestParam(required = false)
            String keyword,
            @PageableDefault(size = 10)
            Pageable pageable) {
        return ApiResponse.success(HttpStatus.OK.value(), "Skills retrieved successfully",
                adminSkillService.getAllSkills(keyword, pageable));
    }

    @Operation(summary = "Add System Skill", description = "Add a new skill to the system directory.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminSkillResponse> createSkill(@Valid @RequestBody
    AdminSkillRequest request) {
        return ApiResponse.success(HttpStatus.CREATED.value(), "Skill created successfully",
                adminSkillService.createSkill(request));
    }

    @Operation(summary = "Edit System Skill", description = "Update an existing skill's name or description.")
    @PutMapping("/{id}")
    public ApiResponse<AdminSkillResponse> updateSkill(@PathVariable
    Long id,
            @Valid @RequestBody
            AdminSkillRequest request) {
        return ApiResponse.success(HttpStatus.OK.value(), "Skill updated successfully",
                adminSkillService.updateSkill(id, request));
    }

    @Operation(summary = "Delete System Skill", description = "Soft delete a skill (set is_active = false).")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteSkill(@PathVariable
    Long id) {
        adminSkillService.deleteSkill(id);
        return ApiResponse.success(HttpStatus.OK.value(), "Skill deactivated successfully", null);
    }
}
