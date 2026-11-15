package com.taskpilot.projects.tasks.controller;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.infrastructure.config.security.CurrentActorResolver;
import com.taskpilot.projects.tasks.dto.CreateLabelRequest;
import com.taskpilot.projects.tasks.dto.LabelDto;
import com.taskpilot.projects.tasks.service.LabelService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/labels")
@RequiredArgsConstructor
public class LabelController {

    private final LabelService labelService;

    @GetMapping
    public ApiResponse<List<LabelDto>> getProjectLabels(
            @PathVariable Long projectId,
            Authentication authentication) {
        return ApiResponse.ok("Labels retrieved successfully",
                labelService.getLabelsByProject(projectId, CurrentActorResolver.userId(authentication)));
    }

    @PostMapping
    public ApiResponse<LabelDto> createLabel(
            @PathVariable Long projectId,
            @Valid @RequestBody CreateLabelRequest request,
            Authentication authentication) {
        return ApiResponse.created("Label created successfully",
                labelService.createLabel(projectId, request, CurrentActorResolver.userId(authentication)));
    }

    @DeleteMapping("/{labelId}")
    public ApiResponse<Void> deleteLabel(
            @PathVariable Long projectId,
            @PathVariable Long labelId,
            Authentication authentication) {
        labelService.deleteLabel(projectId, labelId, CurrentActorResolver.userId(authentication));
        return ApiResponse.ok("Label deleted successfully", null);
    }
}
