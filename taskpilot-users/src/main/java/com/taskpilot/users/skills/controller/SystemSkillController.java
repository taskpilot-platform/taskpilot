package com.taskpilot.users.skills.controller;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.users.skills.dto.SkillDirectoryResponse;
import com.taskpilot.users.skills.service.SkillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "04. System Skills", description = "APIs for managing and searching global system skills")
@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
public class SystemSkillController {

    private final SkillService skillService;

    @Operation(summary = "Search System Skills", description = "Search for system skills by keyword. Hard limited to 20 results.")
    @GetMapping("/search")
    public ApiResponse<List<SkillDirectoryResponse>> searchSkills(@RequestParam String keyword) {
        return ApiResponse.success(HttpStatus.OK.value(), "Skills retrieved successfully",
                skillService.searchSkills(keyword));
    }
}
