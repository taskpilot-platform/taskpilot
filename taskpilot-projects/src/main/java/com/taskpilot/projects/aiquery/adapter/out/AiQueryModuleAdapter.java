package com.taskpilot.projects.aiquery.adapter.out;

import com.taskpilot.contracts.aiquery.dto.MemberWorkloadDto;
import com.taskpilot.contracts.aiquery.dto.LabelSummaryDto;
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
import com.taskpilot.contracts.task.PatchTaskCommand;
import com.taskpilot.contracts.task.TaskDatePolicy;
import com.taskpilot.contracts.user.dto.NotificationTypeDto;
import com.taskpilot.contracts.user.dto.SystemNotificationCommandDto;
import com.taskpilot.contracts.user.port.out.UserNotificationPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.enums.HeuristicMode;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.PriorityLevel;
import com.taskpilot.projects.common.enums.ProjectStatus;
import com.taskpilot.projects.common.enums.TaskStatus;
import com.taskpilot.projects.common.enums.WorkflowMode;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.entity.SprintEntity;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.entity.TaskRequiredSkillEntity;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.common.repository.SprintRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
import com.taskpilot.projects.common.repository.TaskRequiredSkillRepository;
import com.taskpilot.projects.sprints.service.SprintService;
import com.taskpilot.projects.tasks.service.LabelService;
import com.taskpilot.projects.tasks.service.TaskCommandService;
import com.taskpilot.projects.tasks.service.TaskService;
import com.taskpilot.projects.sprints.dto.CompleteSprintRequest;
import com.taskpilot.projects.sprints.dto.CreateSprintRequest;
import com.taskpilot.projects.sprints.dto.UpdateSprintRequest;
import com.taskpilot.projects.tasks.dto.CreateLabelRequest;
import com.taskpilot.projects.tasks.dto.CreateSuccessorTaskRequest;
import com.taskpilot.projects.tasks.dto.CreateTaskRequest;
import com.taskpilot.projects.tasks.dto.KanbanMoveRequest;
import com.taskpilot.projects.tasks.dto.LabelDto;
import com.taskpilot.projects.tasks.dto.TaskDto;
import com.taskpilot.projects.tasks.dto.UpdateTaskRequest;
import com.taskpilot.projects.tasks.dto.UpdateTaskSprintRequest;
import com.taskpilot.projects.projects.service.ProjectServiceImpl;
import com.taskpilot.projects.projects.dto.CreateProjectRequest;
import com.taskpilot.projects.projects.dto.ProjectResponse;
import com.taskpilot.projects.projects.dto.UpdateProjectRequest;
import com.taskpilot.projects.projects.dto.JoinProjectRequest;
import com.taskpilot.projects.projects.dto.ProjectMemberResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Primary
@Component
public class AiQueryModuleAdapter
        implements TaskCommandPort, ProjectInsightsPort, MemberAnalyticsPort, SprintQueryPort {

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final SprintRepository sprintRepository;
    private final TaskRequiredSkillRepository taskRequiredSkillRepository;
    private final UserPort userPort;
    private final UserSkillPort userSkillPort;
    private final SkillPort skillPort;
    private final SprintService sprintService;
    private final TaskService taskService;
    private final TaskCommandService taskCommandService;
    private final LabelService labelService;
    private final ProjectServiceImpl projectService;
    private final Clock clock;

    @Autowired
    public AiQueryModuleAdapter(
            TaskRepository taskRepository,
            ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository,
            SprintRepository sprintRepository,
            TaskRequiredSkillRepository taskRequiredSkillRepository,
            UserPort userPort,
            UserSkillPort userSkillPort,
            SkillPort skillPort,
            UserNotificationPort notificationPort,
            SprintService sprintService,
            TaskService taskService,
            TaskCommandService taskCommandService,
            LabelService labelService,
            ProjectServiceImpl projectService) {
        this(taskRepository, projectRepository, projectMemberRepository, sprintRepository,
                taskRequiredSkillRepository, userPort, userSkillPort, skillPort,
                sprintService, taskService, taskCommandService, labelService, projectService, Clock.systemUTC());
    }

    public AiQueryModuleAdapter(
            TaskRepository taskRepository,
            ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository,
            SprintRepository sprintRepository,
            TaskRequiredSkillRepository taskRequiredSkillRepository,
            UserPort userPort,
            UserSkillPort userSkillPort,
            SkillPort skillPort,
            SprintService sprintService,
            TaskService taskService,
            TaskCommandService taskCommandService,
            LabelService labelService,
            ProjectServiceImpl projectService,
            Clock clock) {
        this.taskRepository = taskRepository;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.sprintRepository = sprintRepository;
        this.taskRequiredSkillRepository = taskRequiredSkillRepository;
        this.userPort = userPort;
        this.userSkillPort = userSkillPort;
        this.skillPort = skillPort;
        this.sprintService = sprintService;
        this.taskService = taskService;
        this.taskCommandService = taskCommandService;
        this.labelService = labelService;
        this.projectService = projectService;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

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
    public TaskSummaryDto patchTask(PatchTaskCommand command, Long requesterUserId) {
        return toTaskSummary(taskCommandService.patchTask(command, requesterUserId));
    }

    @Override
    @Transactional
    public TaskSummaryDto updateTaskRequiredSkills(Long taskId, String skills, Long expectedTaskVersion, UUID requestId,
            Long requesterUserId) {
        return toTaskSummary(taskCommandService.updateTaskRequiredSkillsByNames(
                taskId, skills, expectedTaskVersion, requestId, requesterUserId));
    }

    @Override
    @Transactional
    public TaskAssignmentResultDto assignTaskToMember(Long taskId, Long memberId, String reason,
            Long expectedTaskVersion,
            UUID requestId, Long requesterUserId, boolean simulate) {
        taskCommandService.assignTaskToMember(
                taskId, memberId, expectedTaskVersion, requestId, requesterUserId, simulate);
        return TaskAssignmentResultDto.success(taskId, memberId,
                normalizeReason(reason, simulate ? "Assignment simulated successfully" : "Task assigned by AI tool"));
    }

    @Override
    @Transactional
    public TaskSummaryDto updateTaskStatus(Long taskId, String status, Long expectedTaskVersion, UUID requestId,
            Long requesterUserId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", parseStatus(status));
        PatchTaskCommand command = PatchTaskCommand.fromValues(taskId, expectedTaskVersion, requestId, values);
        return toTaskSummary(taskCommandService.patchTask(command, requesterUserId));
    }

    @Override
    @Transactional
    public TaskSummaryDto updateTask(Long taskId, String title, String description, String status, String priority,
            Float position, List<Long> labelIds, Integer difficultyLevel, List<Long> requiredSkillIds,
            Long assigneeId, String startDate, String dueDate, Long expectedTaskVersion, UUID requestId,
            Long requesterUserId) {
        UpdateTaskRequest request = new UpdateTaskRequest(
                expectedTaskVersion,
                requestId,
                title,
                description,
                parseOptionalStatus(status),
                parseOptionalPriority(priority),
                position,
                labelIds,
                difficultyLevel,
                requiredSkillIds,
                assigneeId,
                startDate,
                dueDate,
                null);
        return toTaskSummary(taskService.updateTask(taskId, request, requesterUserId));
    }

    @Override
    @Transactional
    public TaskSummaryDto createTask(Long projectId, String title, String description, String priority, Float position,
            Long parentTaskId, Long sprintId, Integer difficultyLevel, List<Long> labelIds,
            List<Long> requiredSkillIds, Long assigneeId, String startDate, String dueDate, UUID requestId,
            Long requesterUserId) {
        CreateTaskRequest request = new CreateTaskRequest(
                projectId,
                parentTaskId,
                sprintId,
                title,
                description,
                parsePriority(priority),
                position,
                labelIds,
                difficultyLevel,
                requiredSkillIds,
                assigneeId,
                startDate,
                dueDate,
                requestId);
        return toTaskSummary(taskCommandService.createTask(request, requesterUserId, requestId));
    }

    @Override
    @Transactional
    public void deleteTask(Long taskId, Long expectedTaskVersion, UUID requestId, Long requesterUserId) {
        taskCommandService.deleteTask(taskId, expectedTaskVersion, requestId, requesterUserId);
    }

    @Override
    @Transactional
    public TaskSummaryDto moveTaskKanban(Long taskId, String status, Float position, Long expectedTaskVersion,
            UUID requestId, Long requesterUserId) {
        KanbanMoveRequest request = new KanbanMoveRequest(
                parseStatus(status), position, expectedTaskVersion, requestId);
        return toTaskSummary(taskCommandService.moveTaskKanban(taskId, request, requesterUserId));
    }

    @Override
    @Transactional
    public TaskSummaryDto createSuccessorTask(Long sourceTaskId, String title, String description, String priority,
            Long sprintId, Integer difficultyLevel, Long assigneeId, String startDate, String dueDate,
            UUID requestId, Long requesterUserId) {
        CreateSuccessorTaskRequest request = new CreateSuccessorTaskRequest(
                title,
                description,
                parseOptionalPriority(priority),
                sprintId,
                difficultyLevel,
                null,
                null,
                assigneeId,
                startDate,
                dueDate,
                requestId);
        return toTaskSummary(taskCommandService.createSuccessorTask(sourceTaskId, request, requesterUserId, requestId));
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
                            member.getJoinedAt() != null ? member.getJoinedAt().toString() : null,
                            project.getTimezoneId());
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
        long done = tasks.stream().filter(task -> task.getStatus() == TaskStatus.DONE).count();
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
    public List<LabelSummaryDto> getProjectLabels(Long projectId, Long requesterUserId) {
        return labelService.getLabelsByProject(projectId, requesterUserId).stream()
                .map(this::toLabelSummary)
                .toList();
    }

    @Override
    @Transactional
    public LabelSummaryDto createProjectLabel(Long projectId, String name, String color, Long requesterUserId) {
        return toLabelSummary(labelService.createLabel(projectId, new CreateLabelRequest(name, color),
                requesterUserId));
    }

    @Override
    @Transactional
    public void deleteProjectLabel(Long projectId, Long labelId, Long requesterUserId) {
        labelService.deleteLabel(projectId, labelId, requesterUserId);
    }

    @Override
    @Transactional
    public ProjectOverviewDto createProject(String name, String description, String startDate, String endDate,
            Long requesterUserId) {
        LocalDate start = parseLocalDate(startDate);
        LocalDate end = parseLocalDate(endDate);

        CreateProjectRequest request = new CreateProjectRequest(
                name,
                description,
                null,
                start,
                end);

        ProjectResponse response = projectService.createProject(request, requesterUserId);
        return new ProjectOverviewDto(
                response.id(),
                response.name(),
                response.description(),
                response.status() != null ? response.status().name() : null,
                "MANAGER",
                response.startDate() != null ? response.startDate().toString() : null,
                response.endDate() != null ? response.endDate().toString() : null,
                response.createdAt() != null ? response.createdAt().toString() : null,
                response.timezoneId());
    }

    @Override
    @Transactional
    public ProjectOverviewDto updateProject(Long projectId, String name, String description, String status,
            String heuristicMode, String workflowMode, String startDate, String endDate, Long requesterUserId) {
        LocalDate start = parseLocalDate(startDate);
        LocalDate end = parseLocalDate(endDate);

        UpdateProjectRequest request = new UpdateProjectRequest(
                name,
                description,
                parseProjectStatus(status),
                parseHeuristicMode(heuristicMode),
                parseWorkflowMode(workflowMode),
                start,
                end);

        ProjectResponse response = projectService.updateProject(projectId, request, requesterUserId);
        return new ProjectOverviewDto(
                response.id(),
                response.name(),
                response.description(),
                response.status() != null ? response.status().name() : null,
                "MANAGER",
                response.startDate() != null ? response.startDate().toString() : null,
                response.endDate() != null ? response.endDate().toString() : null,
                response.createdAt() != null ? response.createdAt().toString() : null,
                response.timezoneId());
    }

    @Override
    @Transactional
    public Object joinProject(String projectCode, Long requesterUserId) {
        JoinProjectRequest request = new JoinProjectRequest(projectCode);
        ProjectMemberResponse response = projectService.joinProject(request, requesterUserId);
        return response;
    }

    @Override
    @Transactional
    public void leaveProject(Long projectId, Long requesterUserId) {
        projectService.leaveProject(projectId, requesterUserId);
    }

    @Override
    @Transactional
    public void updateMemberRole(Long projectId, Long targetUserId, String role, Long requesterUserId) {
        MemberRole parsedRole = parseMemberRole(role);
        projectService.updateMemberRole(projectId, targetUserId, parsedRole, requesterUserId);
    }

    @Override
    @Transactional
    public void removeMember(Long projectId, Long targetUserId, Long requesterUserId) {
        projectService.removeMember(projectId, targetUserId, requesterUserId);
    }

    @Override
    @Transactional
    public void archiveProject(Long projectId, Long requesterUserId) {
        projectService.archiveProject(projectId, requesterUserId);
    }

    @Override
    @Transactional
    public void restoreProject(Long projectId, Long requesterUserId) {
        projectService.restoreProject(projectId, requesterUserId);
    }

    @Override
    @Transactional
    public void deleteProject(Long projectId, Long requesterUserId) {
        projectService.deleteProject(projectId, requesterUserId);
    }

    @Override
    @Transactional
    public void deleteProject(Long projectId, String confirmationText, UUID requestId, Long requesterUserId) {
        projectService.deleteProject(projectId, requesterUserId, confirmationText, requestId);
    }

    private ProjectStatus parseProjectStatus(String status) {
        if (status == null || status.isBlank())
            return null;
        try {
            return ProjectStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Invalid status. Use ACTIVE, COMPLETED, ARCHIVED");
        }
    }

    private HeuristicMode parseHeuristicMode(String mode) {
        if (mode == null || mode.isBlank())
            return null;
        try {
            return HeuristicMode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Invalid heuristic mode. Use BALANCED, SKILL_FIT_ONLY, WORKLOAD_ONLY");
        }
    }

    private WorkflowMode parseWorkflowMode(String mode) {
        if (mode == null || mode.isBlank())
            return null;
        try {
            return WorkflowMode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Invalid workflow mode. Use STANDARD, SCRUM, KANBAN");
        }
    }

    private MemberRole parseMemberRole(String role) {
        if (role == null || role.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Role cannot be blank");
        }
        try {
            return MemberRole.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid role. Use MANAGER or MEMBER");
        }
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
                .anyMatch(member -> projectMemberRepository.existsByProjectIdAndUserId(member.getProjectId(),
                        requesterUserId));
        if (!hasSharedProject) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "You do not share a project with this member");
        }

        List<TaskEntity> visibleTasks = taskRepository.findByAssigneeId(memberId).stream()
                .filter(task -> projectMemberRepository.existsByProjectIdAndUserId(task.getProjectId(),
                        requesterUserId))
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

    @Override
    @Transactional(readOnly = true)
    public Object getSprintBacklog(Long projectId, Long requesterUserId) {
        validateProjectMember(projectId, requesterUserId);
        return sprintService.getBacklog(projectId, requesterUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public Object getSprintBoard(Long projectId, Long requesterUserId) {
        validateProjectMember(projectId, requesterUserId);
        return sprintService.getBoard(projectId, requesterUserId);
    }

    @Override
    @Transactional
    public SprintSummaryDto createSprint(Long projectId, String name, String startDate, String endDate, String goal,
            Long requesterUserId) {
        validateProjectMember(projectId, requesterUserId);
        LocalDate start = parseLocalDate(startDate);
        LocalDate end = parseLocalDate(endDate);
        CreateSprintRequest request = new CreateSprintRequest(name, goal, start, end);
        return toSprintSummary(sprintService.createSprint(projectId, request, requesterUserId));
    }

    @Override
    @Transactional
    public SprintSummaryDto updateSprint(Long projectId, Long sprintId, String name, String startDate, String endDate,
            String goal, Long requesterUserId) {
        UpdateSprintRequest request = new UpdateSprintRequest(name, goal, parseLocalDate(startDate),
                parseLocalDate(endDate));
        return toSprintSummary(sprintService.updateSprint(projectId, sprintId, request, requesterUserId));
    }

    @Override
    @Transactional
    public void deleteSprint(Long projectId, Long sprintId, Long requesterUserId) {
        sprintService.deleteSprint(projectId, sprintId, requesterUserId);
    }

    @Override
    @Transactional
    public SprintSummaryDto startSprint(Long projectId, Long sprintId, Long requesterUserId) {
        validateProjectMember(projectId, requesterUserId);
        return toSprintSummary(sprintService.startSprint(projectId, sprintId, requesterUserId));
    }

    @Override
    @Transactional
    public SprintSummaryDto completeSprint(Long projectId, Long sprintId, Long expectedSprintVersion,
            String carryoverDestination, Long targetSprintId, UUID requestId, Long requesterUserId) {
        validateProjectMember(projectId, requesterUserId);
        CompleteSprintRequest request = new CompleteSprintRequest(
                expectedSprintVersion,
                requestId,
                new CompleteSprintRequest.CarryoverSelection(carryoverDestination, targetSprintId),
                null);
        return toSprintSummary(sprintService.completeSprint(projectId, sprintId, request, requesterUserId, requestId));
    }

    @Override
    @Transactional
    public Object assignTaskToSprint(Long taskId, Long sprintId, Long expectedTaskVersion, UUID requestId,
            Long requesterUserId) {
        UpdateTaskSprintRequest request = new UpdateTaskSprintRequest(sprintId, expectedTaskVersion, requestId);
        return taskCommandService.updateTaskSprint(taskId, request, requesterUserId);
    }

    private LocalDate parseLocalDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Invalid date. Use YYYY-MM-DD");
        }
    }

    private TaskEntity findTask(Long taskId) {
        return taskRepository.findActiveById(taskId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found"));
    }

    private ProjectEntity findProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));
    }

    private void validateProjectMember(Long projectId, Long userId) {
        if (projectId == null || userId == null
                || !projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "You are not a member of this project");
        }
    }

    private void validateProjectNotArchived(Long projectId) {
        ProjectEntity project = findProject(projectId);
        if (project.getStatus() == ProjectStatus.ARCHIVED) {
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
        String projectTimezoneId = resolveProjectTimezone(task.getProjectId());
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
                task.getStartDate() != null ? task.getStartDate().toString() : null,
                task.getDueDate() != null ? task.getDueDate().toString() : null,
                task.getCreatedAt() != null ? task.getCreatedAt().toString() : null,
                task.getUpdatedAt() != null ? task.getUpdatedAt().toString() : null,
                task.effectiveVersion(),
                projectTimezoneId);
    }

    private TaskSummaryDto toTaskSummary(TaskDto task) {
        return toTaskSummary(task, task.projectId());
    }

    private TaskSummaryDto toTaskSummary(TaskDto task, Long fallbackProjectId) {
        UserProfileDto assignee = task.assigneeId() == null ? null
                : userPort.findById(task.assigneeId()).orElse(null);
        Long resolvedProjectId = task.projectId() != null ? task.projectId() : fallbackProjectId;
        String projectTimezoneId = task.projectTimezoneId() != null
                ? task.projectTimezoneId()
                : resolveProjectTimezone(resolvedProjectId);
        return new TaskSummaryDto(
                task.id(),
                resolvedProjectId,
                task.parentId(),
                task.sprintId(),
                task.title(),
                task.description(),
                task.status() != null ? task.status().name() : null,
                task.priority() != null ? task.priority().name() : null,
                task.difficultyLevel(),
                task.assigneeId(),
                assignee != null ? assignee.fullName() : null,
                task.startDate() != null ? task.startDate().toString() : null,
                task.dueDate() != null ? task.dueDate().toString() : null,
                task.createdAt() != null ? task.createdAt().toString() : null,
                task.updatedAt() != null ? task.updatedAt().toString() : null,
                task.version(),
                projectTimezoneId);
    }

    private LabelSummaryDto toLabelSummary(LabelDto label) {
        return new LabelSummaryDto(label.id(), label.name(), label.color());
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
                task.getStartDate() != null ? task.getStartDate().toString() : null,
                task.getDueDate() != null ? task.getDueDate().toString() : null,
                assignee != null ? assignee.fullName() : null,
                task.getAssigneeId(),
                task.effectiveVersion(),
                resolveProjectTimezone(task.getProjectId()));
    }

    private String resolveProjectTimezone(Long projectId) {
        if (projectId == null) {
            return TaskDatePolicy.DEFAULT_PROJECT_TIMEZONE_ID;
        }
        return projectRepository.findById(projectId)
                .map(ProjectEntity::getTimezoneId)
                .orElse(TaskDatePolicy.DEFAULT_PROJECT_TIMEZONE_ID);
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
                sprint.getHeuristicMode() != null ? sprint.getHeuristicMode().name() : null,
                sprint.effectiveVersion());
    }

    private SprintSummaryDto toSprintSummary(com.taskpilot.projects.sprints.dto.SprintDto sprint) {
        return new SprintSummaryDto(
                sprint.id(),
                sprint.projectId(),
                sprint.name(),
                sprint.goal(),
                sprint.status() != null ? sprint.status().name() : null,
                sprint.startDate() != null ? sprint.startDate().toString() : null,
                sprint.endDate() != null ? sprint.endDate().toString() : null,
                null,
                sprint.version() != null ? sprint.version() : 0L);
    }

    private MemberWorkloadDto toMemberWorkload(Long memberId, List<TaskEntity> assignedTasks) {
        UserProfileDto profile = userPort.findById(memberId).orElse(null);
        int openTasks = (int) assignedTasks.stream()
                .filter(task -> task.getStatus() != TaskStatus.DONE)
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

    private List<Long> resolveSkillIdsByNames(String skills) {
        if (skills == null || skills.isBlank()) {
            return List.of();
        }

        Set<String> requestedNames = java.util.Arrays.stream(skills.split(","))
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return requestedNames.stream()
                .map(this::resolveSkillIdByName)
                .toList();
    }

    private Long resolveSkillIdByName(String skillName) {
        String normalizedName = normalizeSkillName(skillName);
        return skillPort.search(skillName).stream()
                .filter(skill -> normalizeSkillName(skill.name()).equals(normalizedName))
                .findFirst()
                .map(SkillDto::id)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "Skill not found in active system directory: " + skillName));
    }

    private String normalizeSkillName(String text) {
        if (text == null) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT).replace('đ', 'd');
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .trim();
    }

    private boolean isOverdue(TaskEntity task) {
        return TaskDatePolicy.isOverdue(
                task.getDueDate(),
                task.getStatus() != null ? task.getStatus().name() : null,
                clock);
    }

    private TaskStatus parseStatus(String status) {
        try {
            return TaskStatus.valueOf(normalizeEnum(status));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Invalid task status. Allowed values: TODO, IN_PROGRESS, REVIEW, DONE");
        }
    }

    private PriorityLevel parsePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return PriorityLevel.MEDIUM;
        }
        try {
            return PriorityLevel.valueOf(normalizeEnum(priority));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Invalid task priority. Allowed values: LOW, MEDIUM, HIGH, URGENT");
        }
    }

    private TaskStatus parseOptionalStatus(String status) {
        return status == null || status.isBlank() ? null : parseStatus(status);
    }

    private PriorityLevel parseOptionalPriority(String priority) {
        return priority == null || priority.isBlank() ? null : parsePriority(priority);
    }

    private String normalizeEnum(String value) {
        return value == null ? ""
                : value.trim()
                        .replaceAll("[\\p{Pd}\\s]+", "_")
                        .toUpperCase(Locale.ROOT);
    }

    private String normalizeReason(String reason, String fallback) {
        String normalized = reason == null ? "" : reason.trim();
        return normalized.isBlank() ? fallback : normalized;
    }
}
