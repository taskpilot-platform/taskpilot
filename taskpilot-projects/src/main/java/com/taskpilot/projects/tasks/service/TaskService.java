package com.taskpilot.projects.tasks.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
import com.taskpilot.projects.tasks.dto.CreateTaskRequest;
import com.taskpilot.projects.tasks.dto.KanbanMoveRequest;
import com.taskpilot.projects.tasks.dto.TaskDto;
import com.taskpilot.projects.tasks.dto.TaskDetailDto;
import com.taskpilot.projects.tasks.dto.UpdateTaskRequest;
import com.taskpilot.users.repository.UserRepository;
import com.taskpilot.contracts.assignment.port.out.UserPort;
import com.taskpilot.contracts.user.port.out.NotificationPort;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final UserPort userPort;
    private final NotificationPort notificationPort;

    private Long getCurrentUserIdByEmail(String email) {
        return userRepository.findByEmail(email)
                .map(user -> user.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "User not found"));
    }

    private void validateUserIsMember(Long projectId, Long userId) {
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "You are not a member of this project");
        }
    }

    @Transactional(readOnly = true)
    public List<TaskDto> getTasksByProject(Long projectId, String email) {
        Long userId = getCurrentUserIdByEmail(email);
        validateUserIsMember(projectId, userId);

        return taskRepository.findByProjectId(projectId).stream().map(TaskDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TaskDetailDto getTaskById(Long taskId, String email) {
        TaskEntity task = taskRepository.findById(taskId).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found"));

        Long userId = getCurrentUserIdByEmail(email);
        validateUserIsMember(task.getProjectId(), userId);

        var assignee =
                task.getAssigneeId() != null ? userPort.findById(task.getAssigneeId()).orElse(null)
                        : null;
        var reporter =
                task.getReporterId() != null ? userPort.findById(task.getReporterId()).orElse(null)
                        : null;
        var subtasks = taskRepository.findByParentId(taskId).stream().map(TaskDto::fromEntity)
                .collect(Collectors.toList());

        return TaskDetailDto.from(task, assignee, reporter, subtasks);
    }

    @Transactional(readOnly = true)
    public List<TaskDto> getSubtasks(Long parentId, String email) {
        TaskEntity parentTask = taskRepository.findById(parentId).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Parent task not found"));

        Long userId = getCurrentUserIdByEmail(email);
        validateUserIsMember(parentTask.getProjectId(), userId);

        return taskRepository.findByParentId(parentId).stream().map(TaskDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public TaskDto createTask(CreateTaskRequest request, String email) {
        Long userId = getCurrentUserIdByEmail(email);
        validateUserIsMember(request.projectId(), userId);

        if (request.parentId() != null) {
            TaskEntity parentTask = taskRepository.findById(request.parentId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                            "Parent task not found"));
            if (!parentTask.getProjectId().equals(request.projectId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "Parent task must belong to the same project");
            }
        }

        TaskEntity task = TaskEntity.builder().projectId(request.projectId())
                .parentId(request.parentId()).sprintId(request.sprintId()).title(request.title())
                .description(request.description())
                .priority(request.priority() != null ? request.priority()
                        : TaskEntity.PriorityLevel.MEDIUM)
                .position(request.position() != null ? request.position() : 0f).tags(request.tags())
                .difficultyLevel(request.difficultyLevel() != null ? request.difficultyLevel() : 1)
                .requiredSkills(request.requiredSkills()).assigneeId(request.assigneeId())
                .reporterId(userId).startDate(request.startDate()).dueDate(request.dueDate())
                .status(TaskEntity.TaskStatus.TODO).build();

        taskRepository.save(task);
        return TaskDto.fromEntity(task);
    }

    @Transactional
    public TaskDto updateTask(Long taskId, UpdateTaskRequest request, String email) {
        TaskEntity task = taskRepository.findById(taskId).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found"));

        Long userId = getCurrentUserIdByEmail(email);
        validateUserIsMember(task.getProjectId(), userId);

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
        if (request.tags() != null)
            task.setTags(request.tags());
        if (request.difficultyLevel() != null)
            task.setDifficultyLevel(request.difficultyLevel());
        if (request.requiredSkills() != null)
            task.setRequiredSkills(request.requiredSkills());
        if (request.assigneeId() != null && !request.assigneeId().equals(task.getAssigneeId())) {
            task.setAssigneeId(request.assigneeId());
            notificationPort.sendSystemNotification(request.assigneeId(), "Task Assigned",
                    "You have been assigned to task: " + task.getTitle(),
                    "/tasks?taskId=" + task.getId());
        }
        if (request.startDate() != null)
            task.setStartDate(request.startDate());
        if (request.dueDate() != null)
            task.setDueDate(request.dueDate());

        taskRepository.save(task);
        return TaskDto.fromEntity(task);
    }

    @Transactional
    public void deleteTask(Long taskId, String email) {
        TaskEntity task = taskRepository.findById(taskId).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found"));

        Long userId = getCurrentUserIdByEmail(email);
        validateUserIsMember(task.getProjectId(), userId);

        boolean canDelete = task.getReporterId().equals(userId);
        if (!canDelete) {
            String role =
                    projectMemberRepository.findByProjectIdAndUserId(task.getProjectId(), userId)
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

        taskRepository.delete(task);
    }

    @Transactional
    public TaskDto moveTaskKanban(Long taskId, KanbanMoveRequest request, String email) {
        TaskEntity task = taskRepository.findById(taskId).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found"));

        Long userId = getCurrentUserIdByEmail(email);
        validateUserIsMember(task.getProjectId(), userId);

        task.setStatus(request.status());
        task.setPosition(request.position());

        taskRepository.save(task);
        return TaskDto.fromEntity(task);
    }
}
