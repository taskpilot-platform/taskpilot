package com.taskpilot.projects.tasks.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.projects.tasks.dto.CreateTaskRequest;
import com.taskpilot.projects.tasks.dto.KanbanMoveRequest;
import com.taskpilot.projects.tasks.dto.TaskDto;
import com.taskpilot.projects.tasks.dto.TaskDetailDto;
import com.taskpilot.projects.tasks.dto.UpdateTaskSprintRequest;
import com.taskpilot.projects.tasks.dto.UpdateTaskRequest;
import com.taskpilot.projects.tasks.service.TaskService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
@Validated
public class TaskController {

    private final TaskService taskService;

    @GetMapping
    public ApiResponse<List<TaskDto>> getTasksByProject(
            @RequestParam Long projectId,
            Authentication authentication) {
        return ApiResponse.ok("Tasks retrieved successfully",
                taskService.getTasksByProject(projectId, authentication.getName()));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<TaskDetailDto> getTaskById(
            @PathVariable Long taskId,
            Authentication authentication) {
        return ApiResponse.ok("Task retrieved successfully",
                taskService.getTaskById(taskId, authentication.getName()));
    }

    @GetMapping("/{taskId}/subtasks")
    public ApiResponse<List<TaskDto>> getSubtasks(
            @PathVariable Long taskId,
            Authentication authentication) {
        return ApiResponse.ok("Subtasks retrieved successfully",
                taskService.getSubtasks(taskId, authentication.getName()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TaskDto> createTask(
            @Valid @RequestBody CreateTaskRequest request,
            Authentication authentication) {
        return ApiResponse.created("Task created successfully",
                taskService.createTask(request, authentication.getName()));
    }

    @PutMapping("/{taskId}")
    public ApiResponse<TaskDto> updateTask(
            @PathVariable Long taskId,
            @Valid @RequestBody UpdateTaskRequest request,
            Authentication authentication) {
        return ApiResponse.ok("Task updated successfully",
                taskService.updateTask(taskId, request, authentication.getName()));
    }

    @DeleteMapping("/{taskId}")
    public ApiResponse<Void> deleteTask(
            @PathVariable Long taskId,
            Authentication authentication) {
        taskService.deleteTask(taskId, authentication.getName());
        return ApiResponse.ok("Task deleted successfully", null);
    }

    @PatchMapping("/{taskId}/kanban")
    public ApiResponse<TaskDto> moveTaskKanban(
            @PathVariable Long taskId,
            @Valid @RequestBody KanbanMoveRequest request,
            Authentication authentication) {
        return ApiResponse.ok("Task moved successfully",
                taskService.moveTaskKanban(taskId, request, authentication.getName()));
    }

    @PatchMapping("/{taskId}/sprint")
    public ApiResponse<TaskDto> updateTaskSprint(
            @PathVariable Long taskId,
            @RequestBody UpdateTaskSprintRequest request,
            Authentication authentication) {
        return ApiResponse.ok("Task sprint updated successfully",
                taskService.updateTaskSprint(taskId, request, authentication.getName()));
    }
}
