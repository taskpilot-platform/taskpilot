package com.taskpilot.users.skills.controller;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.users.skills.dto.AddSkillRequest;
import com.taskpilot.users.skills.dto.UpdateSkillRequest;
import com.taskpilot.users.skills.dto.UserSkillResponse;
import com.taskpilot.users.skills.service.SkillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "03. Personal Skills", description = "APIs for managing user skills")
@RestController
@RequestMapping("/api/v1/users/me/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;

    @Operation(summary = "View List", description = "Get all skills of the current user.")
    @GetMapping
    public ApiResponse<List<UserSkillResponse>> getMySkills() {
        return ApiResponse.success(HttpStatus.OK.value(), "Skills retrieved successfully", skillService.getMySkills());
    }

    @Operation(summary = "View Detail", description = "Get detail of a specific skill for the current user.")
    @GetMapping("/{skill_id}")
    public ApiResponse<UserSkillResponse> getSkillDetail(@PathVariable("skill_id") Long skillId) {
        return ApiResponse.success(HttpStatus.OK.value(), "Skill retrieved successfully",
                skillService.getSkillDetail(skillId));
    }

    @Operation(summary = "Add Skill", description = "Add a new skill or existing skill for the current user.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Void> addSkill(@Valid @RequestBody AddSkillRequest request) {
        skillService.addSkill(request);
        return ApiResponse.success(HttpStatus.CREATED.value(), "Skill added successfully", null);
    }

    @Operation(summary = "Update Skill", description = "Update the level of an existing user skill.")
    @PutMapping("/{skill_id}")
    public ApiResponse<Void> updateSkill(@PathVariable("skill_id") Long skillId,
            @Valid @RequestBody UpdateSkillRequest request) {
        skillService.updateSkill(skillId, request);
        return ApiResponse.success(HttpStatus.OK.value(), "Skill updated successfully", null);
    }

    @Operation(summary = "Delete Skill", description = "Remove a skill from the current user.")
    @DeleteMapping("/{skill_id}")
    public ApiResponse<Void> deleteSkill(@PathVariable("skill_id") Long skillId) {
        skillService.deleteSkill(skillId);
        return ApiResponse.success(HttpStatus.OK.value(), "Skill deleted successfully", null);
    }
}
