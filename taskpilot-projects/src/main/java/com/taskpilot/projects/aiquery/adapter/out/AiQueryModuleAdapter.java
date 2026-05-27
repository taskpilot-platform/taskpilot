package com.taskpilot.projects.aiquery.adapter.out;

import com.taskpilot.contracts.aiquery.dto.MemberWorkloadDto;
import com.taskpilot.contracts.aiquery.dto.ProjectMemberDto;
import com.taskpilot.contracts.aiquery.dto.ProjectOverviewDto;
import com.taskpilot.contracts.aiquery.dto.ProjectStatusDto;
import com.taskpilot.contracts.aiquery.dto.SprintSummaryDto;
import com.taskpilot.contracts.aiquery.dto.TaskAssignmentResultDto;
import com.taskpilot.contracts.aiquery.dto.TaskDetailDto;
import com.taskpilot.contracts.aiquery.dto.TaskSummaryDto;
import com.taskpilot.contracts.aiquery.port.out.MemberAnalyticsPort;
import com.taskpilot.contracts.aiquery.port.out.ProjectInsightsPort;
import com.taskpilot.contracts.aiquery.port.out.SprintQueryPort;
import com.taskpilot.contracts.aiquery.port.out.TaskCommandPort;
import com.taskpilot.contracts.assignment.dto.UserProfileDto;
import com.taskpilot.contracts.assignment.dto.UserSkillDto;
import com.taskpilot.contracts.assignment.port.out.UserPort;
import com.taskpilot.contracts.assignment.port.out.UserSkillPort;
import com.taskpilot.contracts.skill.dto.SkillDto;
import com.taskpilot.contracts.skill.port.out.SkillPort;
import com.taskpilot.contracts.user.port.out.NotificationPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.entity.SprintEntity;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.entity.TaskRequiredSkillEntity;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.common.repository.SprintRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
import com.taskpilot.projects.common.repository.TaskRequiredSkillRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Primary
@Component
@RequiredArgsConstructor
public class AiQueryModuleAdapter implements TaskCommandPort, ProjectInsightsPort, MemberAnalyticsPort, SprintQueryPort {

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final SprintRepository sprintRepository;
    private final TaskRequiredSkillRepository taskRequiredSkillRepository;
    private final UserPort userPort;
    private final UserSkillPort userSkillPort;
    private final SkillPort skillPort;
    private final NotificationPort notificationPort;

    @Override
    @Transactional(readOnly = true)
    public List<TaskSummaryDto> getTasksByProject(Long projectId, Long requesterUserId) {
        validateProjectMember(projectId, requesterUserId);
        return taskRepository.findByProjectId(projectId).stream()
                .sorted(Comparator.comparing(TaskEntity::getStatus)
                        .thenComparing(task -> task.getPosition() == null ? 0f : task.getPosition())
                        .thenComparing(TaskEntity::getId))
                .map(this::toTaskSummary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskDetailDto> getUnassignedTasksByProject(Long projectId, Long requesterUserId) {
        validateProjectMember(projectId, requesterUserId);
        return taskRepository.findByProjectId(projectId).stream()
                .filter(task -> task.getAssigneeId() == null)
                .sorted(Comparator.comparing(TaskEntity::getStatus)
                        .thenComparing(task -> task.getPosition() == null ? 0f : task.getPosition())
                        .thenComparing(TaskEntity::getId))
                .map(this::toTaskDetail)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskSummaryDto> getSubtasks(Long parentTaskId, Long requesterUserId) {
        TaskEntity parent = findTask(parentTaskId);
        validateProjectMember(parent.getProjectId(), requesterUserId);
        return taskRepository.findByParentId(parentTaskId).stream()
                .sorted(Comparator.comparing(task -> task.getPosition() == null ? 0f : task.getPosition()))
                .map(this::toTaskSummary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TaskDetailDto getTaskDetails(Long taskId, Long requesterUserId) {
        TaskEntity task = findTask(taskId);
        validateProjectMember(task.getProjectId(), requesterUserId);

        return toTaskDetail(task);
    }

    @Override
    @Transactional
    public TaskAssignmentResultDto assignTaskToMember(Long taskId, Long memberId, String reason, Long requesterUserId,
            boolean simulate) {
        TaskEntity task = findTask(taskId);
        validateProjectMember(task.getProjectId(), requesterUserId);
        validateProjectMember(task.getProjectId(), memberId);
        validateProjectNotArchived(task.getProjectId());

        if (!simulate) {
            task.setAssigneeId(memberId);
            taskRepository.save(task);
            notificationPort.sendSystemNotification(memberId, "Task Assigned",
                    "You have been assigned to task: " + task.getTitle(),
                    "/tasks?taskId=" + task.getId());
        }

        return TaskAssignmentResultDto.success(taskId, memberId,
                normalizeReason(reason, simulate ? "Assignment simulated successfully" : "Task assigned by AI tool"));
    }

    @Override
    @Transactional
    public TaskSummaryDto updateTaskStatus(Long taskId, String status, Long requesterUserId) {
        TaskEntity task = findTask(taskId);
        validateProjectMember(task.getProjectId(), requesterUserId);
        validateProjectNotArchived(task.getProjectId());

        TaskEntity.TaskStatus nextStatus = parseStatus(status);
        task.setStatus(nextStatus);
        taskRepository.save(task);
        return toTaskSummary(task);
    }

    @Override
    @Transactional
    public TaskSummaryDto createTask(Long projectId, String title, String description, String priority,
            Long parentTaskId, Long sprintId, Integer difficultyLevel, Long assigneeId, String dueDate,
            Long requesterUserId) {
        validateProjectMember(projectId, requesterUserId);
        validateProjectNotArchived(projectId);
        validateParentTask(projectId, parentTaskId);
        validateSprint(projectId, sprintId);
        if (assigneeId != null) {
            validateProjectMember(projectId, assigneeId);
        }

        String safeTitle = title == null ? "" : title.trim();
        if (safeTitle.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Task title is required");
        }

        TaskEntity task = TaskEntity.builder()
                .projectId(projectId)
                .parentId(parentTaskId)
                .sprintId(sprintId)
                .title(safeTitle)
                .description(description)
                .priority(parsePriority(priority))
                .position(0f)
                .difficultyLevel(safeDifficulty(difficultyLevel))
                .assigneeId(assigneeId)
                .reporterId(requesterUserId)
                .dueDate(parseInstant(dueDate))
                .status(TaskEntity.TaskStatus.TODO)
                .build();

        taskRepository.save(task);
        if (assigneeId != null) {
            notificationPort.sendSystemNotification(assigneeId, "Task Assigned",
                    "You have been assigned to task: " + task.getTitle(),
                    "/tasks?taskId=" + task.getId());
        }

        return toTaskSummary(task);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProjectOverviewDto> getMyProjects(Long requesterUserId) {
        return projectMemberRepository.findByUserId(requesterUserId).stream()
                .filter(member -> member.getProject() != null)
                .map(member -> {
                    ProjectEntity project = member.getProject();
                    return new ProjectOverviewDto(
                            project.getId(),
                            project.getName(),
                            project.getDescription(),
                            project.getStatus() != null ? project.getStatus().name() : null,
                            member.getRole() != null ? member.getRole().name() : null,
                            project.getStartDate() != null ? project.getStartDate().toString() : null,
                            project.getEndDate() != null ? project.getEndDate().toString() : null,
                            member.getJoinedAt() != null ? member.getJoinedAt().toString() : null);
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectStatusDto getProjectStatus(Long projectId, Long requesterUserId) {
        ProjectEntity project = findProject(projectId);
        validateProjectMember(projectId, requesterUserId);

        List<TaskEntity> tasks = taskRepository.findByProjectId(projectId);
        long total = tasks.size();
        long done = tasks.stream().filter(task -> task.getStatus() == TaskEntity.TaskStatus.DONE).count();
        long overdue = tasks.stream().filter(this::isOverdue).count();
        long completion = total == 0 ? 0 : Math.round(done * 100.0 / total);

        return new ProjectStatusDto(projectId, project.getName(),
                project.getStatus() != null ? project.getStatus().name() : null,
                total, done, overdue, completion);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProjectMemberDto> getProjectMembers(Long projectId, Long requesterUserId) {
        validateProjectMember(projectId, requesterUserId);
        return projectMemberRepository.findMembers(projectId).stream()
                .map(member -> {
                    UserProfileDto profile = userPort.findById(member.getUserId()).orElse(null);
                    String skills = userSkillPort.findByUserIdWithSkill(member.getUserId()).stream()
                            .map(skill -> skill.skillName() + " (" + skill.level() + "/5)")
                            .collect(Collectors.joining(", "));
                    return new ProjectMemberDto(
                            member.getUserId(),
                            profile != null ? profile.fullName() : "Unknown User",
                            member.getRole() != null ? member.getRole().name() : null,
                            member.getPerformanceScore(),
                            skills);
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemberWorkloadDto> getMemberWorkloadForProject(Long projectId, Long requesterUserId) {
        validateProjectMember(projectId, requesterUserId);
        List<TaskEntity> projectTasks = taskRepository.findByProjectId(projectId);
        return projectMemberRepository.findMembers(projectId).stream()
                .map(member -> toMemberWorkload(member.getUserId(), projectTasks.stream()
                        .filter(task -> member.getUserId().equals(task.getAssigneeId()))
                        .toList()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public MemberWorkloadDto getMemberWorkload(Long memberId, Long requesterUserId) {
        boolean hasSharedProject = projectMemberRepository.findByUserId(memberId).stream()
                .anyMatch(member -> projectMemberRepository.existsByProjectIdAndUserId(member.getProjectId(), requesterUserId));
        if (!hasSharedProject) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "You do not share a project with this member");
        }

        List<TaskEntity> visibleTasks = taskRepository.findByAssigneeId(memberId).stream()
                .filter(task -> projectMemberRepository.existsByProjectIdAndUserId(task.getProjectId(), requesterUserId))
                .toList();
        return toMemberWorkload(memberId, visibleTasks);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SprintSummaryDto> getSprintsByProject(Long projectId, Long requesterUserId) {
        validateProjectMember(projectId, requesterUserId);
        return sprintRepository.findByProjectIdOrderByStartDateAscIdAsc(projectId).stream()
                .map(this::toSprintSummary)
                .toList();
    }

    private TaskEntity findTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found"));
    }

    private ProjectEntity findProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));
    }

    private void validateProjectMember(Long projectId, Long userId) {
        if (projectId == null || userId == null || !projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "You are not a member of this project");
        }
    }

    private void validateProjectNotArchived(Long projectId) {
        ProjectEntity project = findProject(projectId);
        if (project.getStatus() == ProjectEntity.ProjectStatus.ARCHIVED) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Project is archived");
        }
    }

    private void validateParentTask(Long projectId, Long parentTaskId) {
        if (parentTaskId == null) {
            return;
        }
        TaskEntity parent = findTask(parentTaskId);
        if (!projectId.equals(parent.getProjectId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Parent task must belong to the same project");
        }
    }

    private void validateSprint(Long projectId, Long sprintId) {
        if (sprintId == null) {
            return;
        }
        SprintEntity sprint = sprintRepository.findById(sprintId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Sprint not found"));
        if (!projectId.equals(sprint.getProjectId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Sprint must belong to the same project");
        }
    }

    private TaskSummaryDto toTaskSummary(TaskEntity task) {
        UserProfileDto assignee = task.getAssigneeId() == null ? null
                : userPort.findById(task.getAssigneeId()).orElse(null);
        return new TaskSummaryDto(
                task.getId(),
                task.getProjectId(),
                task.getParentId(),
                task.getSprintId(),
                task.getTitle(),
                task.getDescription(),
                task.getStatus() != null ? task.getStatus().name() : null,
                task.getPriority() != null ? task.getPriority().name() : null,
                task.getDifficultyLevel(),
                task.getAssigneeId(),
                assignee != null ? assignee.fullName() : null,
                task.getDueDate() != null ? task.getDueDate().toString() : null,
                task.getCreatedAt() != null ? task.getCreatedAt().toString() : null,
                task.getUpdatedAt() != null ? task.getUpdatedAt().toString() : null);
    }

    private TaskDetailDto toTaskDetail(TaskEntity task) {
        String requiredSkills = taskRequiredSkillRepository.findByTaskId(task.getId()).stream()
                .map(TaskRequiredSkillEntity::getSkillId)
                .collect(Collectors.collectingAndThen(Collectors.toSet(), this::resolveSkillNames));

        UserProfileDto assignee = task.getAssigneeId() == null ? null
                : userPort.findById(task.getAssigneeId()).orElse(null);

        return new TaskDetailDto(
                task.getId(),
                task.getProjectId(),
                task.getTitle(),
                task.getDescription(),
                task.getStatus() != null ? task.getStatus().name() : null,
                task.getPriority() != null ? task.getPriority().name() : null,
                task.getDifficultyLevel(),
                requiredSkills,
                task.getDueDate() != null ? task.getDueDate().toString() : null,
                assignee != null ? assignee.fullName() : null,
                task.getAssigneeId());
    }

    private SprintSummaryDto toSprintSummary(SprintEntity sprint) {
        return new SprintSummaryDto(
                sprint.getId(),
                sprint.getProjectId(),
                sprint.getName(),
                sprint.getGoal(),
                sprint.getStatus() != null ? sprint.getStatus().name() : null,
                sprint.getStartDate() != null ? sprint.getStartDate().toString() : null,
                sprint.getEndDate() != null ? sprint.getEndDate().toString() : null,
                sprint.getHeuristicMode() != null ? sprint.getHeuristicMode().name() : null);
    }

    private MemberWorkloadDto toMemberWorkload(Long memberId, List<TaskEntity> assignedTasks) {
        UserProfileDto profile = userPort.findById(memberId).orElse(null);
        int openTasks = (int) assignedTasks.stream()
                .filter(task -> task.getStatus() != TaskEntity.TaskStatus.DONE)
                .count();
        int overdueTasks = (int) assignedTasks.stream().filter(this::isOverdue).count();
        int workloadScore = profile != null ? profile.currentWorkload() : Math.min(100, openTasks * 10);
        return new MemberWorkloadDto(memberId, profile != null ? profile.fullName() : "Unknown User",
                openTasks, overdueTasks, workloadScore);
    }

    private String resolveSkillNames(Set<Long> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return "";
        }
        return skillPort.findByIds(skillIds).stream()
                .map(SkillDto::name)
                .collect(Collectors.joining(", "));
    }

    private boolean isOverdue(TaskEntity task) {
        return task.getDueDate() != null
                && task.getDueDate().isBefore(Instant.now())
                && task.getStatus() != TaskEntity.TaskStatus.DONE;
    }

    private TaskEntity.TaskStatus parseStatus(String status) {
        try {
            return TaskEntity.TaskStatus.valueOf(normalizeEnum(status));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Invalid task status. Allowed values: TODO, IN_PROGRESS, REVIEW, DONE");
        }
    }

    private TaskEntity.PriorityLevel parsePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return TaskEntity.PriorityLevel.MEDIUM;
        }
        try {
            return TaskEntity.PriorityLevel.valueOf(normalizeEnum(priority));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Invalid task priority. Allowed values: LOW, MEDIUM, HIGH, URGENT");
        }
    }

    private String normalizeEnum(String value) {
        return value == null ? "" : value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
    }

    private Integer safeDifficulty(Integer difficultyLevel) {
        if (difficultyLevel == null) {
            return 1;
        }
        return Math.max(1, Math.min(10, difficultyLevel));
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(trimmed).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException ex) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "Invalid dueDate. Use ISO-8601 instant or YYYY-MM-DD");
            }
        }
    }

    private String normalizeReason(String reason, String fallback) {
        String normalized = reason == null ? "" : reason.trim();
        return normalized.isBlank() ? fallback : normalized;
    }
}
