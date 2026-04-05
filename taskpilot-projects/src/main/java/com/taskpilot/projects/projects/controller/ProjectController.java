package com.taskpilot.projects.projects.controller;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.projects.projects.dto.CreateProjectRequest;
import com.taskpilot.projects.projects.dto.JoinProjectRequest;
import com.taskpilot.projects.projects.dto.MyProjectResponse;
import com.taskpilot.projects.projects.dto.ProjectMemberResponse;
import com.taskpilot.projects.projects.dto.ProjectResponse;
import com.taskpilot.projects.projects.dto.ProjectSummaryResponse;
import com.taskpilot.projects.projects.dto.UpdateProjectRequest;
import com.taskpilot.projects.projects.service.ProjectServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "07. Projects", description = "APIs for managing projects")
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

        private final ProjectServiceImpl projectService;

        @Operation(summary = "Get my projects", description = "Get list of projects the current user has joined")
        @GetMapping("/my")
        public ApiResponse<Page<MyProjectResponse>> getMyProjects(
                        Authentication authentication,
                        @RequestParam(required = false) String keyword,
                        @PageableDefault(size = 10) Pageable pageable) {
                return ApiResponse.success(HttpStatus.OK.value(), "Projects retrieved successfully",
                                projectService.getMyProjects(authentication.getName(), keyword, pageable));
        }

        @Operation(summary = "Get project detail", description = "Get detailed information of a specific project")
        @GetMapping("/{projectId}")
        public ApiResponse<ProjectResponse> getProjectDetail(
                        @PathVariable Long projectId,
                        Authentication authentication) {
                return ApiResponse.success(HttpStatus.OK.value(), "Project retrieved successfully",
                                projectService.getProjectDetail(projectId, authentication.getName()));
        }

        @Operation(summary = "Create project", description = "Create a new project. Creator becomes Project Manager.")
        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        public ApiResponse<ProjectResponse> createProject(
                        @Valid @RequestBody CreateProjectRequest request,
                        Authentication authentication) {
                return ApiResponse.success(HttpStatus.CREATED.value(), "Project created successfully",
                                projectService.createProject(request, authentication.getName()));
        }

        @Operation(summary = "Update project", description = "Update project information. Only Project Manager can update.")
        @PutMapping("/{projectId}")
        public ApiResponse<ProjectResponse> updateProject(
                        @PathVariable Long projectId,
                        @Valid @RequestBody UpdateProjectRequest request,
                        Authentication authentication) {
                return ApiResponse.success(HttpStatus.OK.value(), "Project updated successfully",
                                projectService.updateProject(projectId, request, authentication.getName()));
        }

        @Operation(summary = "Join project", description = "Join a project using invitation code")
        @PostMapping("/join")
        public ApiResponse<ProjectMemberResponse> joinProject(
                        @Valid @RequestBody JoinProjectRequest request,
                        Authentication authentication) {
                return ApiResponse.success(HttpStatus.OK.value(), "Joined project successfully",
                                projectService.joinProject(request, authentication.getName()));
        }

        @Operation(summary = "Leave project", description = "Leave a project (remove membership)")
        @DeleteMapping("/{projectId}/leave")
        public ApiResponse<Void> leaveProject(
                        @PathVariable Long projectId,
                        Authentication authentication) {
                projectService.leaveProject(projectId, authentication.getName());
                return ApiResponse.success(HttpStatus.OK.value(), "Left project successfully", null);
        }

        @Operation(summary = "Get project summary", description = "Get project statistics and summary report")
        @GetMapping("/{projectId}/summary")
        public ApiResponse<ProjectSummaryResponse> getProjectSummary(
                        @PathVariable Long projectId,
                        Authentication authentication) {
                return ApiResponse.success(HttpStatus.OK.value(), "Project summary retrieved successfully",
                                projectService.getProjectSummary(projectId, authentication.getName()));
        }

        @Operation(summary = "Get project members", description = "Get all active members of a project")
        @GetMapping("/{projectId}/members")
        public ApiResponse<List<ProjectMemberResponse>> getProjectMembers(
                        @PathVariable Long projectId,
                        Authentication authentication) {
                return ApiResponse.success(HttpStatus.OK.value(), "Project members retrieved successfully",
                                projectService.getProjectMembers(projectId, authentication.getName()));
        }
}
