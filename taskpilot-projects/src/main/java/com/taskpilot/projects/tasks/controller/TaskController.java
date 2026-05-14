package com.taskpilot.projects.tasks.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
        List<TaskDto> tasks = taskService.getTasksByProject(projectId, authentication.getName());
        return ApiResponse.success(HttpStatus.OK.value(), "Tasks retrieved successfully", tasks);
    }

    @GetMapping("/{taskId}")
    public ApiResponse<TaskDetailDto> getTaskById(
            @PathVariable Long taskId,
            Authentication authentication) {
        TaskDetailDto task = taskService.getTaskById(taskId, authentication.getName());
        return ApiResponse.success(HttpStatus.OK.value(), "Task retrieved successfully", task);
    }

    @GetMapping("/{taskId}/subtasks")
    public ApiResponse<List<TaskDto>> getSubtasks(
            @PathVariable Long taskId,
            Authentication authentication) {
        List<TaskDto> tasks = taskService.getSubtasks(taskId, authentication.getName());
        return ApiResponse.success(HttpStatus.OK.value(), "Subtasks retrieved successfully", tasks);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TaskDto> createTask(
            @Valid @RequestBody CreateTaskRequest request,
            Authentication authentication) {
        TaskDto task = taskService.createTask(request, authentication.getName());
        return ApiResponse.success(HttpStatus.CREATED.value(), "Task created successfully", task);
    }

    @PutMapping("/{taskId}")
    public ApiResponse<TaskDto> updateTask(
            @PathVariable Long taskId,
            @Valid @RequestBody UpdateTaskRequest request,
            Authentication authentication) {
        TaskDto task = taskService.updateTask(taskId, request, authentication.getName());
        return ApiResponse.success(HttpStatus.OK.value(), "Task updated successfully", task);
    }

    @DeleteMapping("/{taskId}")
    public ApiResponse<Void> deleteTask(
            @PathVariable Long taskId,
            Authentication authentication) {
        taskService.deleteTask(taskId, authentication.getName());
        return ApiResponse.success(HttpStatus.OK.value(), "Task deleted successfully", null);
    }

    @PatchMapping("/{taskId}/kanban")
    public ApiResponse<TaskDto> moveTaskKanban(
            @PathVariable Long taskId,
            @Valid @RequestBody KanbanMoveRequest request,
            Authentication authentication) {
        TaskDto task = taskService.moveTaskKanban(taskId, request, authentication.getName());
        return ApiResponse.success(HttpStatus.OK.value(), "Task moved successfully", task);
    }

    @PatchMapping("/{taskId}/sprint")
    public ApiResponse<TaskDto> updateTaskSprint(
            @PathVariable Long taskId,
            @RequestBody UpdateTaskSprintRequest request,
            Authentication authentication) {
        TaskDto task = taskService.updateTaskSprint(taskId, request, authentication.getName());
        return ApiResponse.success(HttpStatus.OK.value(), "Task sprint updated successfully", task);
    }
}
