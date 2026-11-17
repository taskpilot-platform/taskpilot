package com.taskpilot.projects.projects.controller;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.infrastructure.config.security.CurrentActorResolver;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.projects.dto.CreateProjectRequest;
import com.taskpilot.projects.projects.dto.DeleteProjectRequest;
import com.taskpilot.projects.projects.dto.JoinProjectRequest;
import com.taskpilot.projects.projects.dto.MyProjectResponse;
import com.taskpilot.projects.projects.dto.ProjectDeletionAuditDto;
import com.taskpilot.projects.projects.dto.ProjectMemberResponse;
import com.taskpilot.projects.projects.dto.ProjectResponse;
import com.taskpilot.projects.projects.dto.ProjectSummaryResponse;
import com.taskpilot.projects.projects.dto.UpdateMemberRoleRequest;
import com.taskpilot.projects.projects.dto.UpdateProjectRequest;
import com.taskpilot.projects.projects.service.ProjectServiceImpl;
import com.taskpilot.projects.tasks.dto.TaskWorkflowHistoryDto;
import com.taskpilot.projects.tasks.dto.TombstonedTaskAuditDto;
import com.taskpilot.projects.tasks.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "07. Projects", description = "APIs for managing projects")
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

        private final ProjectServiceImpl projectService;
        private com.taskpilot.projects.tasks.service.TaskService taskService;
        private com.taskpilot.projects.tasks.service.TaskWorkflowService taskWorkflowService;

        @Autowired(required = false)
        void setAuditServices(
                        TaskService taskService,
                        com.taskpilot.projects.tasks.service.TaskWorkflowService taskWorkflowService) {
                this.taskService = taskService;
                this.taskWorkflowService = taskWorkflowService;
        }

        @Operation(summary = "Get my projects", description = "Get list of projects the current user has joined")
        @GetMapping("/my")
        public ApiResponse<Page<MyProjectResponse>> getMyProjects(
                        Authentication authentication,
                        @RequestParam(required = false) String keyword,
                        @PageableDefault(size = 10) Pageable pageable) {
                return ApiResponse.ok("Projects retrieved successfully",
                                projectService.getMyProjects(CurrentActorResolver.userId(authentication), keyword, pageable));
        }

        @Operation(summary = "Get project detail", description = "Get detailed information of a specific project")
        @GetMapping("/{projectId}")
        public ApiResponse<ProjectResponse> getProjectDetail(
                        @PathVariable Long projectId,
                        Authentication authentication) {
                return ApiResponse.ok("Project retrieved successfully",
                                projectService.getProjectDetail(projectId, CurrentActorResolver.userId(authentication)));
        }

        @Operation(summary = "Create project", description = "Create a new project. Creator becomes Project Manager.")
        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        public ApiResponse<ProjectResponse> createProject(
                        @Valid @RequestBody CreateProjectRequest request,
                        Authentication authentication) {
                return ApiResponse.created("Project created successfully",
                                projectService.createProject(request, CurrentActorResolver.userId(authentication)));
        }

        @Operation(summary = "Update project", description = "Update project information. Only Project Manager can update.")
        @PutMapping("/{projectId}")
        public ApiResponse<ProjectResponse> updateProject(
                        @PathVariable Long projectId,
                        @Valid @RequestBody UpdateProjectRequest request,
                        Authentication authentication) {
                return ApiResponse.ok("Project updated successfully",
                                projectService.updateProject(projectId, request, CurrentActorResolver.userId(authentication)));
        }

        @Operation(summary = "Join project", description = "Join a project using invitation code")
        @PostMapping("/join")
        public ApiResponse<ProjectMemberResponse> joinProject(
                        @Valid @RequestBody JoinProjectRequest request,
                        Authentication authentication) {
                return ApiResponse.ok("Joined project successfully",
                                projectService.joinProject(request, CurrentActorResolver.userId(authentication)));
        }

        @Operation(summary = "Leave project", description = "Leave a project (remove membership)")
        @DeleteMapping("/{projectId}/leave")
        public ApiResponse<Void> leaveProject(
                        @PathVariable Long projectId,
                        Authentication authentication) {
                projectService.leaveProject(projectId, CurrentActorResolver.userId(authentication));
                return ApiResponse.ok("Left project successfully", null);
        }

        @Operation(summary = "Get project summary", description = "Get project statistics and summary report")
        @GetMapping("/{projectId}/summary")
        public ApiResponse<ProjectSummaryResponse> getProjectSummary(
                        @PathVariable Long projectId,
                        Authentication authentication) {
                return ApiResponse.ok("Project summary retrieved successfully",
                                projectService.getProjectSummary(projectId, CurrentActorResolver.userId(authentication)));
        }

        @Operation(summary = "Get project members", description = "Get all active members of a project")
        @GetMapping("/{projectId}/members")
        public ApiResponse<List<ProjectMemberResponse>> getProjectMembers(
                        @PathVariable Long projectId,
                        Authentication authentication) {
                return ApiResponse.ok("Project members retrieved successfully",
                                projectService.getProjectMembers(projectId, CurrentActorResolver.userId(authentication)));
        }

        @Operation(summary = "Update member role", description = "Update the role of a project member (Manager only)")
        @PutMapping("/{projectId}/members/{userId}/role")
        public ApiResponse<Void> updateMemberRole(
                        @PathVariable Long projectId,
                        @PathVariable Long userId,
                        @Valid @RequestBody UpdateMemberRoleRequest request,
                        Authentication authentication) {
                projectService.updateMemberRole(projectId, userId, request.role(), CurrentActorResolver.userId(authentication));
                return ApiResponse.ok("Member role updated successfully", null);
        }

        @Operation(summary = "Remove member", description = "Remove a member from the project (Manager only)")
        @DeleteMapping("/{projectId}/members/{userId}")
        public ApiResponse<Void> removeMember(
                        @PathVariable Long projectId,
                        @PathVariable Long userId,
                        Authentication authentication) {
                projectService.removeMember(projectId, userId, CurrentActorResolver.userId(authentication));
                return ApiResponse.ok("Member removed successfully", null);
        }

        @Operation(summary = "Archive project", description = "Archive a project to make it read-only (Manager only)")
        @PostMapping("/{projectId}/archive")
        public ApiResponse<Void> archiveProject(
                        @PathVariable Long projectId,
                        Authentication authentication) {
                projectService.archiveProject(projectId, CurrentActorResolver.userId(authentication));
                return ApiResponse.ok("Project archived successfully", null);
        }

        @Operation(summary = "Restore project", description = "Restore an archived project to active status (Manager only)")
        @PostMapping("/{projectId}/restore")
        public ApiResponse<Void> restoreProject(
                        @PathVariable Long projectId,
                        Authentication authentication) {
                projectService.restoreProject(projectId, CurrentActorResolver.userId(authentication));
                return ApiResponse.ok("Project restored successfully", null);
        }

        @Operation(summary = "Delete project", description = "Permanently delete a project and all its data with explicit confirmation and idempotency (Manager only)")
        @DeleteMapping("/{projectId}")
        public ApiResponse<ProjectDeletionAuditDto> deleteProject(
                        @PathVariable Long projectId,
                        @RequestBody(required = false) DeleteProjectRequest request,
                        @RequestParam(required = false) String confirmationText,
                        @RequestParam(required = false) UUID requestId,
                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                        Authentication authentication) {
                String resolvedConfirmation = request != null && request.confirmationText() != null
                                ? request.confirmationText()
                                : confirmationText;
                UUID resolvedRequestId = request != null && request.requestId() != null
                                ? request.requestId()
                                : (requestId != null ? requestId : parseOptionalUuid(idempotencyKey));
                ProjectDeletionAuditDto audit = projectService.deleteProject(
                                projectId,
                                CurrentActorResolver.userId(authentication),
                                resolvedConfirmation,
                                resolvedRequestId);
                return ApiResponse.ok("Project deleted successfully", audit);
        }

        @Operation(summary = "List tombstoned tasks audit", description = "Restricted manager audit list of tombstoned tasks with preserved history")
        @GetMapping("/{projectId}/audit/deleted-tasks")
        public ApiResponse<List<TombstonedTaskAuditDto>> getDeletedTasksAudit(
                        @PathVariable Long projectId,
                        Authentication authentication) {
                return ApiResponse.ok("Deleted tasks audit retrieved",
                                taskService.getTombstonedTasksAudit(projectId, CurrentActorResolver.userId(authentication)));
        }

        @Operation(summary = "Inspect tombstoned task workflow audit", description = "Restricted manager audit detail of preserved workflow history for a tombstoned task")
        @GetMapping("/{projectId}/audit/deleted-tasks/{taskId}/workflow")
        public ApiResponse<TaskWorkflowHistoryDto> getDeletedTaskWorkflowAudit(
                        @PathVariable Long projectId,
                        @PathVariable Long taskId,
                        Authentication authentication) {
                return ApiResponse.ok("Deleted task workflow audit retrieved",
                                taskWorkflowService.getDeletedTaskWorkflowAudit(
                                                projectId, taskId, CurrentActorResolver.userId(authentication)));
        }

        private UUID parseOptionalUuid(String raw) {
                if (raw == null || raw.isBlank()) {
                        return null;
                }
                try {
                        return UUID.fromString(raw.trim());
                } catch (IllegalArgumentException ex) {
                        throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                                        "Idempotency-Key must be a valid UUID");
                }
        }
}
