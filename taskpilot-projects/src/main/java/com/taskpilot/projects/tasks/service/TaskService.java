package com.taskpilot.projects.tasks.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskpilot.contracts.assignment.port.out.UserPort;
import com.taskpilot.contracts.skill.dto.SkillDto;
import com.taskpilot.contracts.skill.port.out.SkillPort;
import com.taskpilot.contracts.task.PatchTaskCommand;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.entity.TaskRequiredSkillEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.repository.TaskRepository;
import com.taskpilot.projects.common.repository.TaskRequiredSkillRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.tasks.dto.CreateSuccessorTaskRequest;
import com.taskpilot.projects.tasks.dto.CreateTaskRequest;
import com.taskpilot.projects.tasks.dto.KanbanMoveRequest;
import com.taskpilot.projects.tasks.dto.TaskDetailDto;
import com.taskpilot.projects.tasks.dto.TaskDto;
import com.taskpilot.projects.tasks.dto.TombstonedTaskAuditDto;
import com.taskpilot.projects.tasks.dto.UpdateTaskRequest;
import com.taskpilot.projects.tasks.dto.UpdateTaskSprintRequest;

@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ProjectSecurityService projectSecurityService;
    private final SkillPort skillPort;
    private final UserPort userPort;
    private final TaskRequiredSkillRepository taskRequiredSkillRepository;
    private final TaskDtoMapper taskDtoMapper;
    private final TaskCommandService taskCommandService;

    @Autowired
    public TaskService(
            TaskRepository taskRepository,
            ProjectSecurityService projectSecurityService,
            SkillPort skillPort,
            UserPort userPort,
            TaskRequiredSkillRepository taskRequiredSkillRepository,
            TaskDtoMapper taskDtoMapper,
            TaskCommandService taskCommandService) {
        this.taskRepository = taskRepository;
        this.projectSecurityService = projectSecurityService;
        this.skillPort = skillPort;
        this.userPort = userPort;
        this.taskRequiredSkillRepository = taskRequiredSkillRepository;
        this.taskDtoMapper = taskDtoMapper;
        this.taskCommandService = taskCommandService;
    }

    private List<TaskDto> mapToDtoWithLabels(List<TaskEntity> tasks) {
        return taskDtoMapper.mapToDtoWithLabels(tasks);
    }

    private TaskDto mapToDtoWithLabels(TaskEntity task) {
        return taskDtoMapper.mapToDtoWithLabels(task);
    }

    @Transactional(readOnly = true)
    public List<TaskDto> getTasksByProject(Long projectId, Long userId) {
        projectSecurityService.validateMember(projectId, userId);

        List<TaskEntity> tasks = taskRepository.findByProjectId(projectId);
        return mapToDtoWithLabels(tasks);
    }

    @Transactional(readOnly = true)
    public TaskDetailDto getTaskById(Long taskId, Long userId) {
        TaskEntity task = taskRepository.findActiveById(taskId).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found"));

        projectSecurityService.validateMember(task.getProjectId(), userId);

        var assignee = task.getAssigneeId() != null ? userPort.findById(task.getAssigneeId()).orElse(null)
                : null;
        var reporter = task.getReporterId() != null ? userPort.findById(task.getReporterId()).orElse(null)
                : null;
        List<TaskDto> subtasks = mapToDtoWithLabels(taskRepository.findByParentId(taskId));

        List<TaskRequiredSkillEntity> reqSkills = taskRequiredSkillRepository.findByTaskId(taskId);
        Set<Long> skillIds = reqSkills.stream().map(TaskRequiredSkillEntity::getSkillId).collect(Collectors.toSet());
        List<SkillDto> skills = skillIds.isEmpty() ? List.of() : skillPort.findByIds(skillIds);

        TaskDto taskDto = mapToDtoWithLabels(task);

        return TaskDetailDto.builder()
                .task(taskDto)
                .assignee(assignee)
                .reporter(reporter)
                .subtasks(subtasks)
                .requiredSkills(skills)
                .build();
    }

    @Transactional(readOnly = true)
    public List<TaskDto> getSubtasks(Long parentId, Long userId) {
        TaskEntity parentTask = taskRepository.findActiveById(parentId).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Parent task not found"));

        projectSecurityService.validateMember(parentTask.getProjectId(), userId);

        List<TaskEntity> subtasks = taskRepository.findByParentId(parentId);
        return mapToDtoWithLabels(subtasks);
    }

    @Transactional(readOnly = true)
    public List<TombstonedTaskAuditDto> getTombstonedTasksAudit(Long projectId, Long userId) {
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateManager(projectId, userId);
        return taskRepository.findTombstonedByProjectId(projectId).stream()
                .map(t -> new TombstonedTaskAuditDto(
                        t.getId(),
                        t.getProjectId(),
                        t.getParentId(),
                        t.getSprintId(),
                        t.getTitle(),
                        t.getStatus(),
                        t.getAssigneeId(),
                        t.getCompletionEvidenceStatus(),
                        t.getCurrentSubmissionId(),
                        t.getCurrentEvaluationId(),
                        t.getDeletedAt(),
                        t.getDeletedByUserId(),
                        t.getDeletionReason()))
                .toList();
    }

    @Transactional
    public TaskDto createSuccessorTask(Long sourceTaskId, CreateSuccessorTaskRequest request, Long userId, UUID requestId) {
        return taskCommandService.createSuccessorTask(sourceTaskId, request, userId, requestId);
    }

    @Transactional
    public TaskDto createTask(CreateTaskRequest request, Long userId, UUID requestId) {
        return taskCommandService.createTask(request, userId, requestId);
    }

    @Transactional
    public TaskDto patchTask(PatchTaskCommand command, Long userId) {
        return taskCommandService.patchTask(command, userId);
    }

    @Transactional
    public TaskDto updateTask(Long taskId, UpdateTaskRequest request, Long userId) {
        if (request == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Request body is required");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        if (request.title() != null)
            values.put("title", request.title());
        if (request.description() != null)
            values.put("description", request.description());
        if (request.status() != null)
            values.put("status", request.status());
        if (request.priority() != null)
            values.put("priority", request.priority());
        if (request.position() != null)
            values.put("position", request.position());
        if (request.labelIds() != null)
            values.put("labelIds", request.labelIds());
        if (request.difficultyLevel() != null)
            values.put("difficultyLevel", request.difficultyLevel());
        if (request.requiredSkillIds() != null)
            values.put("requiredSkillIds", request.requiredSkillIds());
        if (request.assigneeId() != null)
            values.put("assigneeId", request.assigneeId());
        if (request.startDate() != null)
            values.put("startDate", request.startDate());
        if (request.dueDate() != null)
            values.put("dueDate", request.dueDate());
        if (request.sprintId() != null)
            values.put("sprintId", request.sprintId());

        PatchTaskCommand command = PatchTaskCommand.fromValues(
                taskId,
                request.expectedTaskVersion(),
                request.requestId(),
                values);
        return taskCommandService.patchTask(command, userId);
    }

    @Transactional
    public void deleteTask(Long taskId, Long expectedTaskVersion, UUID requestId, Long userId) {
        taskCommandService.deleteTask(taskId, expectedTaskVersion, requestId, userId);
    }

    @Transactional
    public TaskDto moveTaskKanban(Long taskId, KanbanMoveRequest request, Long userId) {
        return taskCommandService.moveTaskKanban(taskId, request, userId);
    }

    @Transactional
    public TaskDto updateTaskSprint(Long taskId, UpdateTaskSprintRequest request, Long userId) {
        return taskCommandService.updateTaskSprint(taskId, request, userId);
    }
}
