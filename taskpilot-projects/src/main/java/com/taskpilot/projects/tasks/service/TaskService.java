package com.taskpilot.projects.tasks.service;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.common.repository.SprintRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
import com.taskpilot.projects.tasks.dto.CreateTaskRequest;
import com.taskpilot.projects.tasks.dto.KanbanMoveRequest;
import com.taskpilot.projects.tasks.dto.TaskDto;
import com.taskpilot.projects.tasks.dto.TaskDetailDto;
import com.taskpilot.projects.tasks.dto.UpdateTaskSprintRequest;
import com.taskpilot.projects.tasks.dto.UpdateTaskRequest;
import com.taskpilot.projects.tasks.dto.LabelDto;
import com.taskpilot.projects.common.repository.LabelRepository;
import com.taskpilot.projects.common.repository.TaskLabelRepository;
import com.taskpilot.projects.common.repository.TaskRequiredSkillRepository;
import com.taskpilot.projects.common.entity.TaskLabelEntity;
import com.taskpilot.projects.common.entity.TaskRequiredSkillEntity;
import com.taskpilot.projects.common.entity.LabelEntity;
import com.taskpilot.projects.common.entity.SprintEntity;
import com.taskpilot.contracts.skill.dto.SkillDto;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.contracts.skill.port.out.SkillPort;
import com.taskpilot.contracts.assignment.port.out.UserPort;
import com.taskpilot.contracts.user.port.out.NotificationPort;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserIdentityPort userIdentityPort;
    private final SkillPort skillPort;
    private final UserPort userPort;
    private final NotificationPort notificationPort;
    private final LabelRepository labelRepository;
    private final TaskLabelRepository taskLabelRepository;
    private final TaskRequiredSkillRepository taskRequiredSkillRepository;
    private final SprintRepository sprintRepository;
    private final TaskDtoMapper taskDtoMapper;

    private Long getCurrentUserIdByEmail(String email) {
        return userIdentityPort.findByEmail(email)
                .map(identity -> identity.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "User not found"));
    }

    private void validateUserIsMember(Long projectId, Long userId) {
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "You are not a member of this project");
        }
    }

    private void validateProjectNotArchived(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));
        if (project.getStatus() == ProjectEntity.ProjectStatus.ARCHIVED) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Project is archived");
        }
    }

    private List<TaskDto> mapToDtoWithLabels(List<TaskEntity> tasks) {
        return taskDtoMapper.mapToDtoWithLabels(tasks);
    }

    private TaskDto mapToDtoWithLabels(TaskEntity task) {
        return taskDtoMapper.mapToDtoWithLabels(task);
    }

    private void validateSprintForTask(Long projectId, Long sprintId) {
        if (sprintId == null) {
            return;
        }
        SprintEntity sprint = sprintRepository.findById(sprintId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Sprint not found"));
        if (!sprint.getProjectId().equals(projectId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Sprint must belong to the same project");
        }
        if (sprint.getStatus() == SprintEntity.SprintStatus.COMPLETED) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Completed sprint is readonly");
        }
    }

    private void validateTaskDateRange(Instant startDate, Instant dueDate) {
        if (startDate != null && dueDate != null && dueDate.isBefore(startDate)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Task due date cannot be earlier than start date");
        }
    }

    @Transactional(readOnly = true)
    public List<TaskDto> getTasksByProject(Long projectId, String email) {
        Long userId = getCurrentUserIdByEmail(email);
        validateUserIsMember(projectId, userId);

        List<TaskEntity> tasks = taskRepository.findByProjectId(projectId);
        return mapToDtoWithLabels(tasks);
    }

    @Transactional(readOnly = true)
    public TaskDetailDto getTaskById(Long taskId, String email) {
        TaskEntity task = taskRepository.findById(taskId).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found"));

        Long userId = getCurrentUserIdByEmail(email);
        validateUserIsMember(task.getProjectId(), userId);

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
    public List<TaskDto> getSubtasks(Long parentId, String email) {
        TaskEntity parentTask = taskRepository.findById(parentId).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Parent task not found"));

        Long userId = getCurrentUserIdByEmail(email);
        validateUserIsMember(parentTask.getProjectId(), userId);

        List<TaskEntity> subtasks = taskRepository.findByParentId(parentId);
        return mapToDtoWithLabels(subtasks);
    }

    @Transactional
    public TaskDto createTask(CreateTaskRequest request, String email) {
        Long userId = getCurrentUserIdByEmail(email);
        validateUserIsMember(request.projectId(), userId);
        validateProjectNotArchived(request.projectId());
        validateTaskDateRange(request.startDate(), request.dueDate());

        if (request.parentId() != null) {
            TaskEntity parentTask = taskRepository.findById(request.parentId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                            "Parent task not found"));
            if (!parentTask.getProjectId().equals(request.projectId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "Parent task must belong to the same project");
            }
        }
        validateSprintForTask(request.projectId(), request.sprintId());

        TaskEntity task = TaskEntity.builder()
                .projectId(request.projectId())
                .parentId(request.parentId())
                .sprintId(request.sprintId())
                .title(request.title())
                .description(request.description())
                .priority(request.priority() != null ? request.priority() : TaskEntity.PriorityLevel.MEDIUM)
                .position(request.position() != null ? request.position() : 0f)
                .difficultyLevel(request.difficultyLevel() != null ? request.difficultyLevel() : 1)
                .assigneeId(request.assigneeId())
                .reporterId(userId)
                .startDate(request.startDate())
                .dueDate(request.dueDate())
                .status(TaskEntity.TaskStatus.TODO).build();

        taskRepository.save(task);

        if (request.labelIds() != null && !request.labelIds().isEmpty()) {
            List<Long> distinctLabelIds = request.labelIds().stream().distinct().toList();
            long validCount = labelRepository.findAllById(distinctLabelIds).stream()
                    .filter(l -> l.getProjectId().equals(task.getProjectId()))
                    .count();
            if (validCount != distinctLabelIds.size()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "One or more labels are invalid or do not belong to this project");
            }

            List<TaskLabelEntity> taskLabels = distinctLabelIds.stream()
                    .map(labelId -> TaskLabelEntity.builder()
                            .id(new TaskLabelEntity.TaskLabelId(task.getId(), labelId))
                            .taskId(task.getId())
                            .labelId(labelId).build())
                    .toList();
            taskLabelRepository.saveAll(taskLabels);
        }

        if (request.requiredSkillIds() != null && !request.requiredSkillIds().isEmpty()) {
            List<Long> distinctSkillIds = request.requiredSkillIds().stream().distinct().toList();
            List<SkillDto> validSkills = skillPort.findByIds(new java.util.HashSet<>(distinctSkillIds));
            if (validSkills.size() != distinctSkillIds.size()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "One or more required skills are invalid or inactive");
            }

            List<TaskRequiredSkillEntity> taskSkills = distinctSkillIds.stream()
                    .map(skillId -> TaskRequiredSkillEntity.builder()
                            .id(new TaskRequiredSkillEntity.TaskRequiredSkillId(task.getId(), skillId))
                            .taskId(task.getId())
                            .skillId(skillId).build())
                    .toList();
            taskRequiredSkillRepository.saveAll(taskSkills);
        }

        return mapToDtoWithLabels(task);
    }

    @Transactional
    public TaskDto updateTask(Long taskId, UpdateTaskRequest request, String email) {
        TaskEntity task = taskRepository.findById(taskId).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found"));

        Long userId = getCurrentUserIdByEmail(email);
        validateUserIsMember(task.getProjectId(), userId);
        validateProjectNotArchived(task.getProjectId());

        if (request.title() != null)
            task.setTitle(request.title());
        if (request.description() != null)
            task.setDescription(request.description());
        if (request.status() != null)
            task.setStatus(request.status());
        if (request.priority() != null)
            task.setPriority(request.priority());
        if (request.position() != null)
            task.setPosition(request.position());
        if (request.difficultyLevel() != null)
            task.setDifficultyLevel(request.difficultyLevel());

        if (request.assigneeId() != null && !request.assigneeId().equals(task.getAssigneeId())) {
            task.setAssigneeId(request.assigneeId());
            notificationPort.sendSystemNotification(request.assigneeId(), "Task Assigned",
                    "You have been assigned to task: " + task.getTitle(),
                    "/tasks?taskId=" + task.getId());
        }
        if (request.startDate() != null || request.dueDate() != null) {
            Instant nextStartDate = request.startDate() != null ? request.startDate() : task.getStartDate();
            Instant nextDueDate = request.dueDate() != null ? request.dueDate() : task.getDueDate();
            validateTaskDateRange(nextStartDate, nextDueDate);
        }
        if (request.startDate() != null)
            task.setStartDate(request.startDate());
        if (request.dueDate() != null)
            task.setDueDate(request.dueDate());

        taskRepository.save(task);

        if (request.labelIds() != null) {
            List<Long> distinctLabelIds = request.labelIds().stream().distinct().toList();
            if (!distinctLabelIds.isEmpty()) {
                long validCount = labelRepository.findAllById(distinctLabelIds).stream()
                        .filter(l -> l.getProjectId().equals(task.getProjectId()))
                        .count();
                if (validCount != distinctLabelIds.size()) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                            "One or more labels are invalid or do not belong to this project");
                }
            }
            taskLabelRepository.deleteByTaskId(task.getId());
            if (!distinctLabelIds.isEmpty()) {
                List<TaskLabelEntity> taskLabels = distinctLabelIds.stream()
                        .map(labelId -> TaskLabelEntity.builder()
                                .id(new TaskLabelEntity.TaskLabelId(task.getId(), labelId))
                                .taskId(task.getId())
                                .labelId(labelId).build())
                        .toList();
                taskLabelRepository.saveAll(taskLabels);
            }
        }

        if (request.requiredSkillIds() != null) {
            List<Long> distinctSkillIds = request.requiredSkillIds().stream().distinct().toList();
            if (!distinctSkillIds.isEmpty()) {
                List<SkillDto> validSkills = skillPort.findByIds(new java.util.HashSet<>(distinctSkillIds));
                if (validSkills.size() != distinctSkillIds.size()) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                            "One or more required skills are invalid or inactive");
                }
            }
            taskRequiredSkillRepository.deleteByTaskId(task.getId());
            if (!distinctSkillIds.isEmpty()) {
                List<TaskRequiredSkillEntity> taskSkills = distinctSkillIds.stream()
                        .map(skillId -> TaskRequiredSkillEntity.builder()
                                .id(new TaskRequiredSkillEntity.TaskRequiredSkillId(task.getId(), skillId))
                                .taskId(task.getId())
                                .skillId(skillId).build())
                        .toList();
                taskRequiredSkillRepository.saveAll(taskSkills);
            }
        }

        return mapToDtoWithLabels(task);
    }

    @Transactional
    public void deleteTask(Long taskId, String email) {
        TaskEntity task = taskRepository.findById(taskId).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found"));

        Long userId = getCurrentUserIdByEmail(email);
        validateUserIsMember(task.getProjectId(), userId);
        validateProjectNotArchived(task.getProjectId());

        boolean canDelete = task.getReporterId().equals(userId);
        if (!canDelete) {
            String role = projectMemberRepository.findByProjectIdAndUserId(task.getProjectId(), userId)
                    .map(pm -> pm.getRole().name()).orElse("MEMBER");
            if ("MANAGER".equals(role)) {
                canDelete = true;
            }
        }
        if (!canDelete) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "You do not have permission to delete this task");
        }

        List<TaskEntity> subtasks = taskRepository.findByParentId(taskId);
        taskRepository.deleteAll(subtasks);

        taskLabelRepository.deleteByTaskId(task.getId());
        taskRequiredSkillRepository.deleteByTaskId(task.getId());
        taskRepository.delete(task);
    }

    @Transactional
    public TaskDto moveTaskKanban(Long taskId, KanbanMoveRequest request, String email) {
        TaskEntity task = taskRepository.findById(taskId).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found"));

        Long userId = getCurrentUserIdByEmail(email);
        validateUserIsMember(task.getProjectId(), userId);
        validateProjectNotArchived(task.getProjectId());

        task.setStatus(request.status());
        task.setPosition(request.position());

        taskRepository.save(task);
        return mapToDtoWithLabels(task);
    }

    @Transactional
    public TaskDto updateTaskSprint(Long taskId, UpdateTaskSprintRequest request, String email) {
        TaskEntity task = taskRepository.findById(taskId).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found"));

        Long userId = getCurrentUserIdByEmail(email);
        validateUserIsMember(task.getProjectId(), userId);
        validateProjectNotArchived(task.getProjectId());

        if (task.getSprintId() != null) {
            SprintEntity currentSprint = sprintRepository.findById(task.getSprintId())
                    .orElse(null);
            if (currentSprint != null && currentSprint.getStatus() == SprintEntity.SprintStatus.COMPLETED) {
                throw new BusinessException(HttpStatus.CONFLICT.value(),
                        "Cannot move a task out of a completed sprint");
            }
        }

        validateSprintForTask(task.getProjectId(), request.sprintId());
        task.setSprintId(request.sprintId());
        taskRepository.save(task);
        return mapToDtoWithLabels(task);
    }
}
