package com.taskpilot.projects.tasks.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.taskpilot.infrastructure.dto.ApiResponse;
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
            @AuthenticationPrincipal String email) {
        return ApiResponse.success(200, "Labels retrieved successfully", labelService.getLabelsByProject(projectId, email));
    }

    @PostMapping
    public ApiResponse<LabelDto> createLabel(
            @PathVariable Long projectId,
            @Valid @RequestBody CreateLabelRequest request,
            @AuthenticationPrincipal String email) {
        return ApiResponse.success(201, "Label created successfully", labelService.createLabel(projectId, request, email));
    }

    @DeleteMapping("/{labelId}")
    public ApiResponse<Void> deleteLabel(
            @PathVariable Long projectId,
            @PathVariable Long labelId,
            @AuthenticationPrincipal String email) {
        labelService.deleteLabel(projectId, labelId, email);
        return ApiResponse.success(200, "Label deleted successfully", null);
    }
}
