package com.taskpilot.projects.tasks.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.contracts.task.PatchTaskCommand;
import com.taskpilot.infrastructure.config.security.CurrentActorResolver;
import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.tasks.dto.CreateSuccessorTaskRequest;
import com.taskpilot.projects.tasks.dto.CreateTaskRequest;
import com.taskpilot.projects.tasks.dto.KanbanMoveRequest;
import com.taskpilot.projects.tasks.dto.TaskDetailDto;
import com.taskpilot.projects.tasks.dto.TaskDto;
import com.taskpilot.projects.tasks.dto.UpdateTaskSprintRequest;
import com.taskpilot.projects.tasks.service.TaskService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
@Validated
public class TaskController {

    private static final ObjectMapper PATCH_OBJECT_MAPPER = new ObjectMapper();

    private final TaskService taskService;

    @GetMapping
    public ApiResponse<List<TaskDto>> getTasksByProject(
            @RequestParam Long projectId,
            Authentication authentication) {
        return ApiResponse.ok("Tasks retrieved successfully",
                taskService.getTasksByProject(projectId, CurrentActorResolver.userId(authentication)));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<TaskDetailDto> getTaskById(
            @PathVariable Long taskId,
            Authentication authentication) {
        return ApiResponse.ok("Task retrieved successfully",
                taskService.getTaskById(taskId, CurrentActorResolver.userId(authentication)));
    }

    @GetMapping("/{taskId}/subtasks")
    public ApiResponse<List<TaskDto>> getSubtasks(
            @PathVariable Long taskId,
            Authentication authentication) {
        return ApiResponse.ok("Subtasks retrieved successfully",
                taskService.getSubtasks(taskId, CurrentActorResolver.userId(authentication)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TaskDto> createTask(
            @Valid @RequestBody CreateTaskRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        UUID requestId = request.requestId() != null
                ? request.requestId()
                : parseOptionalUuid(idempotencyKey, "Idempotency-Key");
        return ApiResponse.created("Task created successfully",
                taskService.createTask(request, CurrentActorResolver.userId(authentication), requestId));
    }

    @PostMapping("/{taskId}/successor")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TaskDto> createSuccessorTask(
            @PathVariable Long taskId,
            @RequestBody(required = false) CreateSuccessorTaskRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        UUID requestId = (request != null && request.requestId() != null)
                ? request.requestId()
                : parseOptionalUuid(idempotencyKey, "Idempotency-Key");
        return ApiResponse.created("Successor task created successfully",
                taskService.createSuccessorTask(taskId, request, CurrentActorResolver.userId(authentication), requestId));
    }

    @PutMapping("/{taskId}")
    public ApiResponse<TaskDto> updateTask(
            @PathVariable Long taskId,
            @RequestBody(required = false) String rawBody,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        PatchTaskCommand command = toPatchCommand(taskId, rawBody, ifMatch, idempotencyKey);
        return ApiResponse.ok("Task updated successfully",
                taskService.patchTask(command, CurrentActorResolver.userId(authentication)));
    }

    @PatchMapping("/{taskId}")
    public ApiResponse<TaskDto> patchTask(
            @PathVariable Long taskId,
            @RequestBody(required = false) String rawBody,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        PatchTaskCommand command = toPatchCommand(taskId, rawBody, ifMatch, idempotencyKey);
        return ApiResponse.ok("Task updated successfully",
                taskService.patchTask(command, CurrentActorResolver.userId(authentication)));
    }

    @DeleteMapping("/{taskId}")
    public ApiResponse<Void> deleteTask(
            @PathVariable Long taskId,
            @RequestParam(required = false) Long expectedTaskVersion,
            @RequestParam(required = false) UUID requestId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long resolvedVersion = expectedTaskVersion != null ? expectedTaskVersion : parseIfMatchVersion(ifMatch);
        UUID resolvedRequestId = requestId != null ? requestId : parseOptionalUuid(idempotencyKey, "Idempotency-Key");
        taskService.deleteTask(taskId, resolvedVersion, resolvedRequestId, CurrentActorResolver.userId(authentication));
        return ApiResponse.ok("Task deleted successfully", null);
    }

    @PatchMapping("/{taskId}/kanban")
    public ApiResponse<TaskDto> moveTaskKanban(
            @PathVariable Long taskId,
            @Valid @RequestBody KanbanMoveRequest request,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long resolvedVersion = request.expectedTaskVersion() != null
                ? request.expectedTaskVersion()
                : parseIfMatchVersion(ifMatch);
        UUID resolvedRequestId = request.requestId() != null
                ? request.requestId()
                : parseOptionalUuid(idempotencyKey, "Idempotency-Key");
        KanbanMoveRequest effectiveRequest = new KanbanMoveRequest(
                request.status(),
                request.position(),
                resolvedVersion,
                resolvedRequestId);
        return ApiResponse.ok("Task moved successfully",
                taskService.moveTaskKanban(taskId, effectiveRequest, CurrentActorResolver.userId(authentication)));
    }

    @PatchMapping("/{taskId}/sprint")
    public ApiResponse<TaskDto> updateTaskSprint(
            @PathVariable Long taskId,
            @RequestBody UpdateTaskSprintRequest request,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        if (request == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Request body is required");
        }
        Long resolvedVersion = request.expectedTaskVersion() != null
                ? request.expectedTaskVersion()
                : parseIfMatchVersion(ifMatch);
        UUID resolvedRequestId = request.requestId() != null
                ? request.requestId()
                : parseOptionalUuid(idempotencyKey, "Idempotency-Key");
        UpdateTaskSprintRequest effectiveRequest = new UpdateTaskSprintRequest(
                request.sprintId(),
                resolvedVersion,
                resolvedRequestId);
        return ApiResponse.ok("Task sprint updated successfully",
                taskService.updateTaskSprint(taskId, effectiveRequest, CurrentActorResolver.userId(authentication)));
    }

    private PatchTaskCommand toPatchCommand(long taskId, String rawBody, String ifMatch, String idempotencyKey) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Request body must be a JSON object");
        }
        JsonNode body;
        try {
            body = PATCH_OBJECT_MAPPER.readTree(rawBody);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Malformed JSON request body");
        }
        if (body == null || !body.isObject()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Request body must be a JSON object");
        }

        Long expectedVersion = null;
        if (body.has("expectedTaskVersion")) {
            JsonNode versionNode = body.get("expectedTaskVersion");
            if (!versionNode.isNull()) {
                if (!versionNode.isIntegralNumber()) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                            "expectedTaskVersion must be an integer");
                }
                expectedVersion = versionNode.asLong();
            }
        }
        if (expectedVersion == null) {
            expectedVersion = parseIfMatchVersion(ifMatch);
        }

        UUID requestId = null;
        if (body.has("requestId") && !body.get("requestId").isNull()) {
            JsonNode reqNode = body.get("requestId");
            if (!reqNode.isTextual()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "requestId must be a UUID string");
            }
            requestId = parseOptionalUuid(reqNode.asText(), "requestId");
        }
        if (requestId == null) {
            requestId = parseOptionalUuid(idempotencyKey, "Idempotency-Key");
        }

        Set<String> presentFields = new LinkedHashSet<>();
        Map<String, Object> values = new LinkedHashMap<>();

        var entries = body.properties();
        for (Map.Entry<String, JsonNode> entry : entries) {
            String key = entry.getKey();
            if ("expectedTaskVersion".equals(key) || "requestId".equals(key)) {
                continue;
            }
            if (!PatchTaskCommand.ALLOWED_FIELDS.contains(key)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "Unsupported or forbidden task patch field: " + key);
            }
            presentFields.add(key);
            values.put(key, convertJsonValue(key, entry.getValue()));
        }

        try {
            return new PatchTaskCommand(taskId, expectedVersion, requestId, presentFields, values);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), ex.getMessage());
        }
    }

    private Object convertJsonValue(String key, JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) {
                if (item.isNull()) {
                    list.add(null);
                } else if (item.isIntegralNumber()) {
                    list.add(item.asLong());
                } else if (item.isTextual()) {
                    list.add(item.asText());
                } else {
                    throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                            "Unsupported array element type for field: " + key);
                }
            }
            return list;
        }
        throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                "Unsupported JSON value type for field: " + key);
    }

    private Long parseIfMatchVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        String normalized = ifMatch.trim();
        if (normalized.startsWith("W/")) {
            normalized = normalized.substring(2).trim();
        }
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(normalized);
        } catch (NumberFormatException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid If-Match version header");
        }
    }

    private UUID parseOptionalUuid(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), field + " must be a valid UUID");
        }
    }
}
