package com.taskpilot.projects.tasks.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.taskpilot.contracts.assignment.port.out.UserPort;
import com.taskpilot.contracts.project.port.out.ProjectAiPurgePort;
import com.taskpilot.contracts.skill.dto.SkillDto;
import com.taskpilot.contracts.skill.port.out.SkillPort;
import com.taskpilot.contracts.task.PatchTaskCommand;
import com.taskpilot.contracts.task.TaskDateField;
import com.taskpilot.contracts.task.TaskDatePolicy;
import com.taskpilot.contracts.task.TaskEvaluationInvalidated;
import com.taskpilot.contracts.task.TaskWorkflowRequiredException;
import com.taskpilot.contracts.user.dto.AccountAuthStateDto;
import com.taskpilot.contracts.user.dto.NotificationTypeDto;
import com.taskpilot.contracts.user.dto.SystemNotificationCommandDto;
import com.taskpilot.contracts.user.event.TaskAssignedEvent;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.contracts.user.port.out.UserNotificationPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.entity.SprintEntity;
import com.taskpilot.projects.common.entity.TaskCommandIdempotencyEntity;
import com.taskpilot.projects.common.entity.TaskDeadlineRevisionEntity;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.entity.TaskEvaluationEntity;
import com.taskpilot.projects.common.entity.TaskEvaluationInvalidationEntity;
import com.taskpilot.projects.common.entity.TaskLabelEntity;
import com.taskpilot.projects.common.entity.TaskRequiredSkillEntity;
import com.taskpilot.projects.common.entity.TaskWorkflowJournalEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.PriorityLevel;
import com.taskpilot.projects.common.enums.ProjectStatus;
import com.taskpilot.projects.common.enums.SprintStatus;
import com.taskpilot.projects.common.enums.TaskStatus;
import com.taskpilot.projects.common.repository.LabelRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.common.repository.SprintRepository;
import com.taskpilot.projects.common.repository.TaskCommandIdempotencyRepository;
import com.taskpilot.projects.common.repository.TaskDeadlineRevisionRepository;
import com.taskpilot.projects.common.repository.TaskEvaluationInvalidationRepository;
import com.taskpilot.projects.common.repository.TaskEvaluationRepository;
import com.taskpilot.projects.common.repository.TaskLabelRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
import com.taskpilot.projects.common.repository.TaskRequiredSkillRepository;
import com.taskpilot.projects.common.repository.TaskReviewRepository;
import com.taskpilot.projects.common.repository.TaskSubmissionRepository;
import com.taskpilot.projects.common.repository.TaskWorkflowJournalRepository;
import com.taskpilot.projects.projects.service.ProjectAccountLifecycleAdapter;
import com.taskpilot.projects.tasks.dto.CreateSuccessorTaskRequest;
import com.taskpilot.projects.tasks.dto.CreateTaskRequest;
import com.taskpilot.projects.tasks.dto.KanbanMoveRequest;
import com.taskpilot.projects.tasks.dto.TaskDto;
import com.taskpilot.projects.tasks.dto.UpdateTaskSprintRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Single projects-owned mutation policy for all task create, patch, assign,
 * status,
 * kanban move, skill, sprint assignment, and delete writes across REST and AI.
 */
@Slf4j
@Service
public class TaskCommandService {

    private static final ObjectMapper CANONICAL_MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    private static final Set<String> REVIEW_FROZEN_FIELDS = Set.of(
            "title",
            "description",
            "assigneeId",
            "startDate",
            "dueDate",
            "difficultyLevel",
            "requiredSkillIds",
            "labelIds",
            "sprintId");

    private static final Set<String> DONE_FROZEN_FIELDS = Set.of(
            "title",
            "description",
            "priority",
            "difficultyLevel",
            "assigneeId",
            "startDate",
            "dueDate",
            "sprintId",
            "labelIds",
            "requiredSkillIds");

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final SprintRepository sprintRepository;
    private final LabelRepository labelRepository;
    private final TaskLabelRepository taskLabelRepository;
    private final TaskRequiredSkillRepository taskRequiredSkillRepository;
    private final TaskCommandIdempotencyRepository idempotencyRepository;
    private final AccountAuthStatePort accountAuthStatePort;
    private final SkillPort skillPort;
    private final UserNotificationPort notificationPort;
    private final ApplicationEventPublisher eventPublisher;
    private final TaskDtoMapper taskDtoMapper;
    private final Clock clock;
    private TaskDeadlineRevisionRepository deadlineRevisionRepository;
    private TaskSubmissionRepository submissionRepository;
    private TaskReviewRepository reviewRepository;
    private TaskEvaluationRepository evaluationRepository;
    private TaskEvaluationInvalidationRepository invalidationRepository;
    private TaskWorkflowJournalRepository journalRepository;
    private TaskWorkflowJournalDispatcher journalDispatcher;
    private ProjectAiPurgePort projectAiPurgePort;

    @Autowired(required = false)
    void setWorkflowHistoryRepositories(
            TaskDeadlineRevisionRepository deadlineRevisionRepository,
            TaskSubmissionRepository submissionRepository,
            TaskReviewRepository reviewRepository,
            TaskEvaluationRepository evaluationRepository,
            TaskEvaluationInvalidationRepository invalidationRepository,
            TaskWorkflowJournalRepository journalRepository,
            TaskWorkflowJournalDispatcher journalDispatcher,
            ProjectAiPurgePort projectAiPurgePort) {
        this.deadlineRevisionRepository = deadlineRevisionRepository;
        this.submissionRepository = submissionRepository;
        this.reviewRepository = reviewRepository;
        this.evaluationRepository = evaluationRepository;
        this.invalidationRepository = invalidationRepository;
        this.journalRepository = journalRepository;
        this.journalDispatcher = journalDispatcher;
        this.projectAiPurgePort = projectAiPurgePort;
    }

    @Autowired
    public TaskCommandService(
            TaskRepository taskRepository,
            ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository,
            SprintRepository sprintRepository,
            LabelRepository labelRepository,
            TaskLabelRepository taskLabelRepository,
            TaskRequiredSkillRepository taskRequiredSkillRepository,
            TaskCommandIdempotencyRepository idempotencyRepository,
            AccountAuthStatePort accountAuthStatePort,
            SkillPort skillPort,
            UserNotificationPort notificationPort,
            ApplicationEventPublisher eventPublisher,
            TaskDtoMapper taskDtoMapper) {
        this(taskRepository, projectRepository, projectMemberRepository, sprintRepository,
                labelRepository, taskLabelRepository, taskRequiredSkillRepository,
                idempotencyRepository, accountAuthStatePort, skillPort, notificationPort,
                eventPublisher, taskDtoMapper, Clock.systemUTC());
    }

    public TaskCommandService(
            TaskRepository taskRepository,
            ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository,
            SprintRepository sprintRepository,
            LabelRepository labelRepository,
            TaskLabelRepository taskLabelRepository,
            TaskRequiredSkillRepository taskRequiredSkillRepository,
            TaskCommandIdempotencyRepository idempotencyRepository,
            AccountAuthStatePort accountAuthStatePort,
            SkillPort skillPort,
            UserNotificationPort notificationPort,
            ApplicationEventPublisher eventPublisher,
            TaskDtoMapper taskDtoMapper,
            Clock clock) {
        this.taskRepository = taskRepository;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.sprintRepository = sprintRepository;
        this.labelRepository = labelRepository;
        this.taskLabelRepository = taskLabelRepository;
        this.taskRequiredSkillRepository = taskRequiredSkillRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.accountAuthStatePort = accountAuthStatePort;
        this.skillPort = skillPort;
        this.notificationPort = notificationPort;
        this.eventPublisher = eventPublisher;
        this.taskDtoMapper = taskDtoMapper;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Transactional
    public TaskDto createTask(CreateTaskRequest request, Long actorUserId, UUID requestId) {
        if (request == null || request.projectId() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Project ID is required");
        }
        if (request.title() == null || request.title().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Title is required");
        }
        UUID effectiveRequestId = requestId != null ? requestId : request.requestId();
        requireRequestIdPresent(effectiveRequestId);
        long projectId = request.projectId();

        ProjectEntity project = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));
        if (project.getStatus() == ProjectStatus.ARCHIVED) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Project is archived");
        }

        Set<Long> userIdsToLock = new TreeSet<>();
        if (actorUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Authentication required");
        }
        userIdsToLock.add(actorUserId);
        if (request.assigneeId() != null) {
            userIdsToLock.add(request.assigneeId());
        }
        Map<Long, AccountAuthStateDto> accounts = lockAndLoadAccounts(userIdsToLock);
        requireActiveProjectMember(projectId, actorUserId, accounts);

        if (idempotencyRepository != null) {
            String payloadHash = computeCreatePayloadHash(request);
            Optional<TaskCommandIdempotencyEntity> existing = idempotencyRepository
                    .findByActorUserIdAndOperationAndRequestId(actorUserId, "CREATE_TASK", effectiveRequestId);
            if (existing.isPresent()) {
                return replayStoredTask(existing.get(), payloadHash, null);
            }
        }

        if (request.assigneeId() != null) {
            requireEligibleTargetAssignee(projectId, request.assigneeId(), accounts);
        }

        if (request.parentId() != null) {
            TaskEntity parentTask = taskRepository.findByIdForUpdate(request.parentId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Parent task not found"));
            if (parentTask.isDeleted()) {
                throw new BusinessException(HttpStatus.NOT_FOUND.value(), "Parent task not found");
            }
            if (!Objects.equals(parentTask.getProjectId(), projectId)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "Parent task must belong to the same project");
            }
            if (parentTask.getSprintId() != null) {
                SprintEntity parentSprint = sprintRepository.findById(parentTask.getSprintId()).orElse(null);
                if (parentSprint != null && parentSprint.getStatus() == SprintStatus.COMPLETED) {
                    throw new BusinessException(HttpStatus.CONFLICT.value(),
                            "Cannot add subtask to a task in a completed sprint");
                }
            }
        }

        if (request.successorOfTaskId() != null) {
            TaskEntity predecessor = taskRepository.findByIdForUpdate(request.successorOfTaskId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Source task not found"));
            if (predecessor.isDeleted()) {
                throw new BusinessException(HttpStatus.NOT_FOUND.value(), "Source task not found");
            }
            if (!Objects.equals(predecessor.getProjectId(), projectId)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "Successor source task must belong to the same project");
            }
        }

        validateTargetSprint(projectId, request.sprintId());

        Instant startInstant = parseTaskDateOrThrow(request.startDate(), project.getTimezoneId(), TaskDateField.START);
        Instant dueInstant = parseTaskDateOrThrow(request.dueDate(), project.getTimezoneId(), TaskDateField.DUE);
        validateEffectiveDateRangeOrThrow(startInstant, dueInstant);

        Integer difficulty = normalizeDifficulty(request.difficultyLevel() != null ? request.difficultyLevel() : 1);
        Float position = validatePosition(request.position() != null ? request.position() : 0f);
        Instant txNow = txNow();

        TaskEntity task = TaskEntity.builder()
                .projectId(projectId)
                .parentId(request.parentId())
                .sprintId(request.sprintId())
                .successorOfTaskId(request.successorOfTaskId())
                .title(request.title().trim())
                .description(request.description())
                .priority(request.priority() != null ? request.priority() : PriorityLevel.MEDIUM)
                .position(position)
                .difficultyLevel(difficulty)
                .assigneeId(request.assigneeId())
                .reporterId(actorUserId)
                .startDate(startInstant)
                .dueDate(dueInstant)
                .status(TaskStatus.TODO)
                .updatedAt(txNow)
                .updatedAtLegacy(Boolean.FALSE)
                .version(0L)
                .assignmentVersion(0L)
                .criteriaRevision(0L)
                .dueCommitmentRevision(dueInstant != null ? 1L : 0L)
                .build();

        task = taskRepository.saveAndFlush(task);

        if (dueInstant != null && deadlineRevisionRepository != null) {
            deadlineRevisionRepository.saveAndFlush(TaskDeadlineRevisionEntity.builder()
                    .taskId(task.getId())
                    .projectId(projectId)
                    .revisionNo(1L)
                    .previousDueAt(null)
                    .newDueAt(dueInstant)
                    .changedByUserId(actorUserId)
                    .reason("INITIAL_DEADLINE")
                    .changedAt(txNow)
                    .build());
        }

        if (request.labelIds() != null && !request.labelIds().isEmpty()) {
            replaceLabels(task.getId(), projectId, request.labelIds());
        }
        if (request.requiredSkillIds() != null && !request.requiredSkillIds().isEmpty()) {
            replaceRequiredSkills(task.getId(), request.requiredSkillIds());
        }

        TaskDto dto = taskDtoMapper.mapToDtoWithLabels(task);
        if (idempotencyRepository != null) {
            recordIdempotency(actorUserId, "CREATE_TASK", effectiveRequestId, projectId, task.getId(),
                    computeCreatePayloadHash(request), HttpStatus.CREATED.value(), dto);
        }
        return dto;
    }

    @Transactional
    public TaskDto createSuccessorTask(
            Long sourceTaskId,
            CreateSuccessorTaskRequest request,
            Long actorUserId,
            UUID headerRequestId) {
        if (sourceTaskId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Source task ID is required");
        }
        if (actorUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Authentication required");
        }
        UUID effectiveRequestId = headerRequestId != null
                ? headerRequestId
                : (request != null ? request.requestId() : null);
        requireRequestIdPresent(effectiveRequestId);

        TaskEntity sourcePreCheck = taskRepository.findActiveById(sourceTaskId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Source task not found"));
        long projectId = sourcePreCheck.getProjectId();

        ProjectEntity project = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));
        if (project.getStatus() == ProjectStatus.ARCHIVED) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Project is archived");
        }

        Set<Long> userIdsToLock = new TreeSet<>();
        userIdsToLock.add(actorUserId);
        if (request != null && request.assigneeId() != null) {
            userIdsToLock.add(request.assigneeId());
        }
        Map<Long, AccountAuthStateDto> accounts = lockAndLoadAccounts(userIdsToLock);
        requireActiveProjectMember(projectId, actorUserId, accounts);

        TaskEntity sourceTask = taskRepository.findByIdForUpdate(sourceTaskId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Source task not found"));
        if (sourceTask.isDeleted()) {
            throw new BusinessException(HttpStatus.NOT_FOUND.value(), "Source task not found");
        }

        String title = (request != null && request.title() != null && !request.title().isBlank())
                ? request.title().trim()
                : "Follow-up: " + sourceTask.getTitle();
        String description = request != null ? request.description() : null;
        PriorityLevel priority = request != null && request.priority() != null
                ? request.priority()
                : (sourceTask.getPriority() != null ? sourceTask.getPriority() : PriorityLevel.MEDIUM);
        Integer difficulty = normalizeDifficulty(request != null && request.difficultyLevel() != null
                ? request.difficultyLevel()
                : (sourceTask.getDifficultyLevel() != null ? sourceTask.getDifficultyLevel() : 1));
        Long targetSprintId = request != null ? request.sprintId() : null;
        Long assigneeId = request != null ? request.assigneeId() : null;
        String rawStartDate = request != null ? request.startDate() : null;
        String rawDueDate = request != null ? request.dueDate() : null;
        List<Long> labelIds = request != null ? request.labelIds() : null;
        List<Long> skillIds = request != null ? request.requiredSkillIds() : null;

        if (idempotencyRepository != null) {
            String payloadHash = computeSuccessorPayloadHash(sourceTaskId, title, description, priority,
                    targetSprintId, difficulty, assigneeId, rawStartDate, rawDueDate, labelIds, skillIds);
            Optional<TaskCommandIdempotencyEntity> existing = idempotencyRepository
                    .findByActorUserIdAndOperationAndRequestId(actorUserId, "CREATE_SUCCESSOR_TASK", effectiveRequestId);
            if (existing.isPresent()) {
                return replayStoredTask(existing.get(), payloadHash, null);
            }
        }

        if (assigneeId != null) {
            requireEligibleTargetAssignee(projectId, assigneeId, accounts);
        }
        validateTargetSprint(projectId, targetSprintId);

        Instant startInstant = parseTaskDateOrThrow(rawStartDate, project.getTimezoneId(), TaskDateField.START);
        Instant dueInstant = parseTaskDateOrThrow(rawDueDate, project.getTimezoneId(), TaskDateField.DUE);
        validateEffectiveDateRangeOrThrow(startInstant, dueInstant);
        Instant txNow = txNow();

        TaskEntity successor = TaskEntity.builder()
                .projectId(projectId)
                .parentId(null)
                .sprintId(targetSprintId)
                .successorOfTaskId(sourceTask.getId())
                .title(title)
                .description(description)
                .priority(priority)
                .position(0f)
                .difficultyLevel(difficulty)
                .assigneeId(assigneeId)
                .reporterId(actorUserId)
                .startDate(startInstant)
                .dueDate(dueInstant)
                .status(TaskStatus.TODO)
                .updatedAt(txNow)
                .updatedAtLegacy(Boolean.FALSE)
                .version(0L)
                .assignmentVersion(0L)
                .criteriaRevision(0L)
                .dueCommitmentRevision(dueInstant != null ? 1L : 0L)
                .build();

        successor = taskRepository.saveAndFlush(successor);

        if (dueInstant != null && deadlineRevisionRepository != null) {
            deadlineRevisionRepository.saveAndFlush(TaskDeadlineRevisionEntity.builder()
                    .taskId(successor.getId())
                    .projectId(projectId)
                    .revisionNo(1L)
                    .previousDueAt(null)
                    .newDueAt(dueInstant)
                    .changedByUserId(actorUserId)
                    .reason("INITIAL_DEADLINE")
                    .changedAt(txNow)
                    .build());
        }
        if (labelIds != null && !labelIds.isEmpty()) {
            replaceLabels(successor.getId(), projectId, labelIds);
        }
        if (skillIds != null && !skillIds.isEmpty()) {
            replaceRequiredSkills(successor.getId(), skillIds);
        }
        if (assigneeId != null) {
            sendAssignmentNotification(successor, assigneeId);
        }

        TaskDto dto = taskDtoMapper.mapToDtoWithLabels(successor);
        if (idempotencyRepository != null) {
            String payloadHash = computeSuccessorPayloadHash(sourceTaskId, title, description, priority,
                    targetSprintId, difficulty, assigneeId, rawStartDate, rawDueDate, labelIds, skillIds);
            recordIdempotency(actorUserId, "CREATE_SUCCESSOR_TASK", effectiveRequestId, projectId,
                    successor.getId(), payloadHash, HttpStatus.CREATED.value(), dto);
        }
        return dto;
    }

    @Transactional
    public TaskDto patchTask(PatchTaskCommand command, Long actorUserId) {
        return patchTaskInternal(command, actorUserId, "PATCH_TASK");
    }

    @Transactional
    public TaskDto moveTaskKanban(Long taskId, KanbanMoveRequest request, Long actorUserId) {
        if (request == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Request body is required");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", request.status());
        values.put("position", request.position());
        PatchTaskCommand cmd = PatchTaskCommand.fromValues(
                taskId,
                request.expectedTaskVersion(),
                request.requestId(),
                values);
        return patchTaskInternal(cmd, actorUserId, "MOVE_KANBAN");
    }

    @Transactional
    public TaskDto updateTaskSprint(Long taskId, UpdateTaskSprintRequest request, Long actorUserId) {
        if (request == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Request body is required");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sprintId", request.sprintId());
        PatchTaskCommand cmd = PatchTaskCommand.fromValues(
                taskId,
                request.expectedTaskVersion(),
                request.requestId(),
                values);
        return patchTaskInternal(cmd, actorUserId, "UPDATE_TASK_SPRINT");
    }

    @Transactional
    public TaskDto updateTaskRequiredSkillsByNames(
            Long taskId,
            String skills,
            Long expectedTaskVersion,
            UUID requestId,
            Long actorUserId) {
        List<Long> skillIds = resolveSkillIdsByNames(skills);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("requiredSkillIds", skillIds);
        PatchTaskCommand cmd = PatchTaskCommand.fromValues(
                taskId,
                expectedTaskVersion,
                requestId,
                values);
        return patchTaskInternal(cmd, actorUserId, "UPDATE_TASK_SKILLS");
    }

    @Transactional
    public TaskDto assignTaskToMember(
            Long taskId,
            Long memberId,
            Long expectedTaskVersion,
            UUID requestId,
            Long actorUserId,
            boolean simulate) {
        return assignTaskToMember(taskId, memberId, expectedTaskVersion, requestId, actorUserId, simulate, null);
    }

    @Transactional
    public TaskDto assignTaskToMember(
            Long taskId,
            Long memberId,
            Long expectedTaskVersion,
            UUID requestId,
            Long actorUserId,
            boolean simulate,
            String assignmentDecisionId) {
        if (!simulate) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("assigneeId", memberId);
            PatchTaskCommand cmd = PatchTaskCommand.fromValues(
                    taskId,
                    expectedTaskVersion,
                    requestId,
                    values);
            return patchTaskInternal(cmd, actorUserId, "ASSIGN_TASK", assignmentDecisionId);
        }

        // Simulation: validate same locking, version, sprint, REVIEW freeze, and active
        // membership without writing.
        requireExpectedTaskVersionPresent(expectedTaskVersion);
        TaskContext ctx = lockAndAuthorizeTaskWrite(taskId, actorUserId, memberId);
        TaskEntity task = ctx.task();
        checkExpectedVersion(expectedTaskVersion, task);
        assertTaskSprintWritable(task);
        if (task.getStatus() == TaskStatus.DONE) {
            throw new TaskWorkflowRequiredException(
                    "REOPEN_TASK",
                    "Task is DONE and cannot be reassigned without an explicit reopen action",
                    List.of("REOPEN_TASK"));
        }
        if (task.getStatus() == TaskStatus.REVIEW) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Cannot reassign task in status " + task.getStatus());
        }
        if (memberId != null) {
            requireEligibleTargetAssignee(ctx.project().getId(), memberId, ctx.accounts());
        }
        return taskDtoMapper.mapToDtoWithLabels(task);
    }

    @Transactional
    public void deleteTask(Long taskId, Long expectedTaskVersion, UUID requestId, Long actorUserId) {
        if (actorUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Authentication required");
        }
        requireExpectedTaskVersionPresent(expectedTaskVersion);
        requireRequestIdPresent(requestId);

        String payloadHash = computeDeletePayloadHash(taskId, expectedTaskVersion);
        if (idempotencyRepository != null) {
            Optional<TaskCommandIdempotencyEntity> existing = idempotencyRepository
                    .findByActorUserIdAndOperationAndRequestId(actorUserId, "DELETE_TASK", requestId);
            if (existing.isPresent()) {
                TaskCommandIdempotencyEntity row = existing.get();
                lockProjectAndRequireActiveActor(row.getProjectId(), actorUserId);
                if (!Objects.equals(row.getPayloadHash(), payloadHash)
                        || (row.getTaskId() != null && !Objects.equals(row.getTaskId(), taskId))) {
                    throw new BusinessException(HttpStatus.CONFLICT.value(),
                            "Idempotency key already used with a different request payload");
                }
                return;
            }
        }

        TaskContext ctx = lockAndAuthorizeTaskWrite(taskId, actorUserId, null);
        TaskEntity task = ctx.task();
        long projectId = ctx.project().getId();

        checkExpectedVersion(expectedTaskVersion, task);

        boolean canDelete = Objects.equals(task.getReporterId(), actorUserId)
                || ctx.actorMember().getRole() == MemberRole.MANAGER;
        if (!canDelete) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "You do not have permission to delete this task");
        }

        assertTaskSprintWritable(task);
        List<TaskEntity> descendants = collectDescendantsLocked(task.getId());
        for (TaskEntity descendant : descendants) {
            if (!descendant.isDeleted()) {
                assertTaskSprintWritable(descendant);
            }
        }

        List<TaskEntity> fullSubtree = new ArrayList<>(1 + descendants.size());
        fullSubtree.add(task);
        fullSubtree.addAll(descendants);

        boolean subtreeHasHistory = fullSubtree.stream().anyMatch(this::hasTaskHistory);
        Instant txNow = txNow();

        if (subtreeHasHistory) {
            List<TaskEntity> tombstonedNodes = new ArrayList<>();
            List<Long> journalIds = new ArrayList<>();

            for (TaskEntity node : fullSubtree) {
                if (node.isDeleted()) {
                    continue;
                }
                boolean isRoot = Objects.equals(node.getId(), task.getId());
                node.setDeletedAt(txNow);
                node.setDeletedByUserId(actorUserId);
                node.setDeletionReason(isRoot ? "TASK_DELETED" : "SUBTREE_TOMBSTONED");
                node.markMutated(txNow);
                tombstonedNodes.add(node);

                if (submissionRepository != null
                        && node.getStatus() == TaskStatus.REVIEW
                        && node.getCurrentSubmissionId() != null) {
                    submissionRepository.findByIdForUpdate(node.getCurrentSubmissionId()).ifPresent(sub -> {
                        if ("PENDING".equals(sub.getStatus())) {
                            sub.setStatus("INVALIDATED");
                            sub.setInvalidatedAt(txNow);
                            sub.setInvalidationReason("TASK_DELETED");
                            submissionRepository.saveAndFlush(sub);
                        }
                    });
                }

                if (evaluationRepository != null) {
                    List<TaskEvaluationEntity> activeEvals = evaluationRepository
                            .findActiveByTaskIdForUpdate(node.getId());
                    for (TaskEvaluationEntity eval : activeEvals) {
                        eval.setInvalidated(Boolean.TRUE);
                        evaluationRepository.saveAndFlush(eval);

                        long nextRev = Math.max(
                                evaluationRepository.findMaxEvidenceRevisionByTaskId(node.getId()),
                                eval.getEvidenceRevision()) + 1L;

                        if (invalidationRepository != null) {
                            invalidationRepository.saveAndFlush(TaskEvaluationInvalidationEntity.builder()
                                    .taskId(node.getId())
                                    .projectId(projectId)
                                    .evaluationId(eval.getId())
                                    .evaluationVersion(eval.getEvaluationVersion())
                                    .reason("TASK_DELETED")
                                    .detailReason(isRoot
                                            ? "Task deleted with preserved workflow history"
                                            : "Ancestor task subtree deleted with preserved workflow history")
                                    .actorUserId(actorUserId)
                                    .occurredAt(txNow)
                                    .evidenceRevision(nextRev)
                                    .build());
                        }
                        if (journalRepository != null) {
                            UUID eventId = UUID.randomUUID();
                            TaskEvaluationInvalidated payload = new TaskEvaluationInvalidated(
                                    eventId,
                                    node.getId(),
                                    projectId,
                                    eval.getId(),
                                    eval.getEvaluationVersion().longValue(),
                                    "TASK_DELETED",
                                    txNow,
                                    nextRev);
                            TaskWorkflowJournalEntity entry = journalRepository.saveAndFlush(
                                    TaskWorkflowJournalEntity.builder()
                                            .eventId(eventId)
                                            .eventType(TaskWorkflowJournalDispatcher.EVENT_EVALUATION_INVALIDATED)
                                            .aggregateVersion(node.effectiveVersion() + 1L)
                                            .evidenceRevision(nextRev)
                                            .projectId(projectId)
                                            .taskId(node.getId())
                                            .submissionId(eval.getSubmissionId())
                                            .evaluationId(eval.getId())
                                            .payloadJson(toJsonString(payload))
                                            .status("PENDING")
                                            .attempts(0)
                                            .nextAttemptAt(txNow)
                                            .createdAt(txNow)
                                            .build());
                            journalIds.add(entry.getId());
                        }
                    }
                }
            }

            if (!tombstonedNodes.isEmpty()) {
                taskRepository.saveAllAndFlush(tombstonedNodes);
            }
            if (journalDispatcher != null && !journalIds.isEmpty()) {
                journalDispatcher.scheduleAfterCommit(journalIds);
            }
        } else {
            // Wholly history-free subtree: delete bottom-up (children before parents).
            for (int i = descendants.size() - 1; i >= 0; i--) {
                TaskEntity descendant = descendants.get(i);
                if (descendant.isDeleted()) {
                    continue;
                }
                taskLabelRepository.deleteByTaskId(descendant.getId());
                taskRequiredSkillRepository.deleteByTaskId(descendant.getId());
                if (deadlineRevisionRepository != null) {
                    deadlineRevisionRepository.deleteByTaskId(descendant.getId());
                }
                taskRepository.delete(descendant);
            }
            taskLabelRepository.deleteByTaskId(task.getId());
            taskRequiredSkillRepository.deleteByTaskId(task.getId());
            if (deadlineRevisionRepository != null) {
                deadlineRevisionRepository.deleteByTaskId(task.getId());
            }
            taskRepository.delete(task);
            taskRepository.flush();
        }

        if (idempotencyRepository != null) {
            recordIdempotency(actorUserId, "DELETE_TASK", requestId, projectId, taskId,
                    payloadHash, HttpStatus.OK.value(), null);
        }
    }

    private TaskDto patchTaskInternal(PatchTaskCommand command, Long actorUserId, String operation) {
        return patchTaskInternal(command, actorUserId, operation, null);
    }

    private TaskDto patchTaskInternal(
            PatchTaskCommand command,
            Long actorUserId,
            String operation,
            String explicitAssignmentDecisionId) {
        if (command == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Patch command is required");
        }
        requireExpectedTaskVersionPresent(command.expectedTaskVersion());
        requireRequestIdPresent(command.requestId());

        String normalizedDecisionId = (explicitAssignmentDecisionId == null || explicitAssignmentDecisionId.isBlank())
                ? null
                : explicitAssignmentDecisionId.trim();

        Long targetAssigneeId = parseOptionalAssigneeId(command);
        TaskContext ctx = lockAndAuthorizeTaskWrite(command.taskId(), actorUserId, targetAssigneeId);
        TaskEntity task = ctx.task();
        ProjectEntity project = ctx.project();
        long projectId = project.getId();

        String payloadHash = command.requestId() != null
                ? computePatchPayloadHash(operation, command, normalizedDecisionId)
                : null;
        if (command.requestId() != null && idempotencyRepository != null) {
            Optional<TaskCommandIdempotencyEntity> existing = idempotencyRepository
                    .findByActorUserIdAndOperationAndRequestId(actorUserId, operation, command.requestId());
            if (existing.isPresent()) {
                return replayStoredTask(existing.get(), payloadHash, command.taskId());
            }
        }

        checkExpectedVersion(command.expectedTaskVersion(), task);
        assertTaskSprintWritable(task);

        // Validate non-nullable fields before any writes
        for (String nonNullable : PatchTaskCommand.NON_NULLABLE_FIELDS) {
            if (command.isPresent(nonNullable) && command.valueOf(nonNullable) == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        nonNullable + " cannot be null");
            }
        }
        if (command.isPresent("labelIds") && command.valueOf("labelIds") == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "labelIds cannot be null; omit to preserve or pass [] to clear");
        }
        if (command.isPresent("requiredSkillIds") && command.valueOf("requiredSkillIds") == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "requiredSkillIds cannot be null; omit to preserve or pass [] to clear");
        }

        TaskStatus currentStatus = task.getStatus();
        TaskStatus nextStatus = currentStatus;
        if (command.isPresent("status")) {
            nextStatus = parseTaskStatus(command.valueOf("status"));
        }

        // Enforce workflow transition gate before metadata mutation
        validateStatusTransition(
                task,
                currentStatus,
                nextStatus,
                command,
                targetAssigneeId,
                actorUserId,
                ctx.actorMember(),
                ctx.accounts(),
                projectId);

        // REVIEW substantive-edit freeze (position-only reorder allowed)
        if (currentStatus == TaskStatus.REVIEW) {
            for (String frozenField : REVIEW_FROZEN_FIELDS) {
                if (command.isPresent(frozenField)) {
                    throw new BusinessException(HttpStatus.CONFLICT.value(),
                            "Task in REVIEW is frozen for substantive edits; withdraw or return the submission first");
                }
            }
        }

        // DONE metadata/assignment/deadline freeze until explicit reopen (R1)
        if (currentStatus == TaskStatus.DONE) {
            for (String frozenField : DONE_FROZEN_FIELDS) {
                if (command.isPresent(frozenField)) {
                    throw new TaskWorkflowRequiredException(
                            "REOPEN_TASK",
                            "Task is DONE and cannot modify '" + frozenField + "' without an explicit reopen action",
                            List.of("REOPEN_TASK"));
                }
            }
        }

        // Parse & validate all fields before mutating persistence state
        String nextTitle = null;
        if (command.isPresent("title")) {
            nextTitle = parseNonBlankString(command.valueOf("title"), "title");
        }
        String nextDescription = null;
        if (command.isPresent("description")) {
            nextDescription = parseNullableString(command.valueOf("description"));
        }
        PriorityLevel nextPriority = null;
        if (command.isPresent("priority")) {
            nextPriority = parsePriority(command.valueOf("priority"));
        }
        Float nextPosition = null;
        if (command.isPresent("position")) {
            nextPosition = validatePosition(parseFloat(command.valueOf("position"), "position"));
        }
        Integer nextDifficulty = null;
        if (command.isPresent("difficultyLevel")) {
            nextDifficulty = normalizeDifficulty(parseInteger(command.valueOf("difficultyLevel"), "difficultyLevel"));
        }

        Long nextSprintId = task.getSprintId();
        if (command.isPresent("sprintId")) {
            nextSprintId = parseNullableLong(command.valueOf("sprintId"), "sprintId");
            validateTargetSprint(projectId, nextSprintId);
        }

        Instant nextStartDate = task.getStartDate();
        if (command.isPresent("startDate")) {
            nextStartDate = parseTaskDateOrThrow(command.valueOf("startDate"), project.getTimezoneId(),
                    TaskDateField.START);
        }
        Instant nextDueDate = task.getDueDate();
        if (command.isPresent("dueDate")) {
            nextDueDate = parseTaskDateOrThrow(command.valueOf("dueDate"), project.getTimezoneId(), TaskDateField.DUE);
        }
        validateEffectiveDateRangeOrThrow(nextStartDate, nextDueDate);

        if (command.isPresent("assigneeId") && targetAssigneeId != null) {
            requireEligibleTargetAssignee(projectId, targetAssigneeId, ctx.accounts());
        }

        List<Long> validatedLabelIds = null;
        if (command.isPresent("labelIds")) {
            validatedLabelIds = parseDistinctIdList(command.valueOf("labelIds"), "labelIds");
            validateLabelsBelongToProject(projectId, validatedLabelIds);
        }

        List<Long> validatedSkillIds = null;
        if (command.isPresent("requiredSkillIds")) {
            validatedSkillIds = parseDistinctIdList(command.valueOf("requiredSkillIds"), "requiredSkillIds");
            validateActiveSkills(validatedSkillIds);
        }

        // Apply mutations atomically under one controlled transaction timestamp
        Instant txNow = txNow();
        boolean criteriaChanged = false;
        Instant previousDueDate = task.getDueDate();
        boolean dueDateChanged = false;

        if (command.isPresent("title")) {
            if (!Objects.equals(task.getTitle(), nextTitle)) {
                criteriaChanged = true;
            }
            task.setTitle(nextTitle);
        }
        if (command.isPresent("description")) {
            if (!Objects.equals(task.getDescription(), nextDescription)) {
                criteriaChanged = true;
            }
            task.setDescription(nextDescription);
        }
        if (command.isPresent("status")) {
            task.setStatus(nextStatus);
        }
        if (command.isPresent("priority")) {
            task.setPriority(nextPriority);
        }
        if (command.isPresent("position")) {
            task.setPosition(nextPosition);
        }
        if (command.isPresent("difficultyLevel")) {
            if (!Objects.equals(task.getDifficultyLevel(), nextDifficulty)) {
                criteriaChanged = true;
            }
            task.setDifficultyLevel(nextDifficulty);
        }
        if (command.isPresent("sprintId")) {
            task.setSprintId(nextSprintId);
        }
        if (command.isPresent("startDate")) {
            task.setStartDate(nextStartDate);
        }
        if (command.isPresent("dueDate")) {
            if (!Objects.equals(previousDueDate, nextDueDate)) {
                dueDateChanged = true;
                criteriaChanged = true;
                task.bumpDueCommitmentRevision();
            }
            task.setDueDate(nextDueDate);
        }
        if (validatedSkillIds != null) {
            criteriaChanged = true;
        }
        if (criteriaChanged) {
            task.bumpCriteriaRevision();
        }

        Long notifyAssignedUserId = null;
        if (command.isPresent("assigneeId")) {
            if (task.updateAssignee(targetAssigneeId)) {
                task.setAssignmentDecisionId(normalizedDecisionId);
                if (targetAssigneeId != null) {
                    notifyAssignedUserId = targetAssigneeId;
                }
            } else if (normalizedDecisionId != null) {
                task.bumpAssignmentVersion();
                task.setAssignmentDecisionId(normalizedDecisionId);
                if (targetAssigneeId != null) {
                    notifyAssignedUserId = targetAssigneeId;
                }
            }
        }

        task.markMutated(txNow);
        TaskEntity saved = taskRepository.saveAndFlush(task);

        if (dueDateChanged && deadlineRevisionRepository != null) {
            deadlineRevisionRepository.saveAndFlush(TaskDeadlineRevisionEntity.builder()
                    .taskId(saved.getId())
                    .projectId(projectId)
                    .revisionNo(saved.effectiveDueCommitmentRevision())
                    .previousDueAt(previousDueDate)
                    .newDueAt(nextDueDate)
                    .changedByUserId(actorUserId)
                    .reason("TASK_PATCHED")
                    .changedAt(txNow)
                    .build());
        }

        if (validatedLabelIds != null) {
            replaceLabels(saved.getId(), projectId, validatedLabelIds);
        }
        if (validatedSkillIds != null) {
            replaceRequiredSkills(saved.getId(), validatedSkillIds);
        }

        if (notifyAssignedUserId != null) {
            sendAssignmentNotification(saved, notifyAssignedUserId);
        }

        TaskDto dto = taskDtoMapper.mapToDtoWithLabels(saved);
        if (command.requestId() != null && idempotencyRepository != null) {
            recordIdempotency(actorUserId, operation, command.requestId(), projectId, saved.getId(),
                    payloadHash, HttpStatus.OK.value(), dto);
        }
        return dto;
    }

    private void validateStatusTransition(
            TaskEntity task,
            TaskStatus currentStatus,
            TaskStatus nextStatus,
            PatchTaskCommand command,
            Long targetAssigneeId,
            Long actorUserId,
            ProjectMemberEntity actorMember,
            Map<Long, AccountAuthStateDto> accounts,
            long projectId) {
        if (nextStatus == currentStatus) {
            return;
        }
        if (nextStatus == TaskStatus.REVIEW) {
            throw new TaskWorkflowRequiredException(
                    "SUBMIT_TASK",
                    "Moving a task to REVIEW requires an explicit task submission",
                    List.of("SUBMIT_TASK"));
        }
        if (nextStatus == TaskStatus.DONE) {
            throw new TaskWorkflowRequiredException(
                    "REVIEW_TASK",
                    "Moving a task to DONE requires manager review or explicit self-attestation",
                    List.of("REVIEW_TASK", "SELF_ATTEST_TASK"));
        }
        if (currentStatus == TaskStatus.DONE) {
            throw new TaskWorkflowRequiredException(
                    "REOPEN_TASK",
                    "Reopening a DONE task requires an explicit reopen action",
                    List.of("REOPEN_TASK"));
        }
        if (currentStatus == TaskStatus.REVIEW) {
            throw new TaskWorkflowRequiredException(
                    "WITHDRAW_OR_RETURN_TASK",
                    "Leaving REVIEW requires submission withdrawal or manager return",
                    List.of("WITHDRAW_SUBMISSION", "RETURN_TASK"));
        }

        // Only TODO <-> IN_PROGRESS remains
        if (currentStatus == TaskStatus.TODO && nextStatus == TaskStatus.IN_PROGRESS) {
            Long effectiveAssigneeId = command.isPresent("assigneeId") ? targetAssigneeId : task.getAssigneeId();
            if (effectiveAssigneeId == null) {
                throw new BusinessException(HttpStatus.CONFLICT.value(),
                        "Task must be assigned to an active member before starting");
            }
            requireEligibleTargetAssignee(projectId, effectiveAssigneeId, accounts);
            boolean isAssignee = Objects.equals(effectiveAssigneeId, actorUserId);
            boolean isManager = actorMember.getRole() == MemberRole.MANAGER;
            if (!isAssignee && !isManager) {
                throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                        "Only the current active assignee or an active project manager can start this task");
            }
            if (isManager && !isAssignee) {
                log.info("[TaskAudit] Manager {} explicitly started task {} assigned to {}",
                        actorUserId, task.getId(), effectiveAssigneeId);
            }
        }
    }

    private TaskContext lockAndAuthorizeTaskWrite(long taskId, Long actorUserId, Long extraTargetUserId) {
        if (actorUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Authentication required");
        }
        TaskEntity preCheck = taskRepository.findActiveById(taskId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found"));
        long projectId = preCheck.getProjectId();

        // Canonical lock order: project -> accounts (asc) -> task
        ProjectEntity project = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));
        if (project.getStatus() == ProjectStatus.ARCHIVED) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Project is archived");
        }

        Set<Long> userIdsToLock = new TreeSet<>();
        userIdsToLock.add(actorUserId);
        if (preCheck.getAssigneeId() != null) {
            userIdsToLock.add(preCheck.getAssigneeId());
        }
        if (extraTargetUserId != null) {
            userIdsToLock.add(extraTargetUserId);
        }
        Map<Long, AccountAuthStateDto> accounts = lockAndLoadAccounts(userIdsToLock);
        ProjectMemberEntity actorMember = requireActiveProjectMember(projectId, actorUserId, accounts);

        TaskEntity lockedTask = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found"));
        if (lockedTask.isDeleted()) {
            throw new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found");
        }
        if (!Objects.equals(lockedTask.getProjectId(), projectId)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Task project scope changed");
        }
        return new TaskContext(project, lockedTask, actorMember, accounts);
    }

    private Map<Long, AccountAuthStateDto> lockAndLoadAccounts(Set<Long> sortedUserIds) {
        if (accountAuthStatePort == null) {
            return Map.of();
        }
        return accountAuthStatePort.lockAndLoadByIds(sortedUserIds);
    }

    private ProjectMemberEntity requireActiveProjectMember(
            long projectId,
            long actorUserId,
            Map<Long, AccountAuthStateDto> accounts) {
        if (accountAuthStatePort != null) {
            AccountAuthStateDto actorAccount = accounts.get(actorUserId);
            if (actorAccount == null) {
                throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Account not found");
            }
            if (!ProjectAccountLifecycleAdapter.isEffectiveActive(actorAccount)) {
                throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                        "Account is not active and verified for this operation");
            }
        }
        return projectMemberRepository.findByProjectIdAndUserId(projectId, actorUserId)
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN.value(),
                        "You are not a member of this project"));
    }

    private void requireEligibleTargetAssignee(
            long projectId,
            long targetUserId,
            Map<Long, AccountAuthStateDto> accounts) {
        if (accountAuthStatePort != null) {
            AccountAuthStateDto targetAccount = accounts.get(targetUserId);
            if (targetAccount == null) {
                throw new BusinessException(HttpStatus.NOT_FOUND.value(), "Target assignee account not found");
            }
            if (!ProjectAccountLifecycleAdapter.isEffectiveActive(targetAccount)) {
                throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                        "Target assignee account is not active and verified");
            }
        }
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, targetUserId)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Target assignee is not a member of this project");
        }
    }

    private void lockProjectAndRequireActiveActor(long projectId, long actorUserId) {
        ProjectEntity project = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));
        if (project.getStatus() == ProjectStatus.ARCHIVED) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Project is archived");
        }
        Map<Long, AccountAuthStateDto> accounts = lockAndLoadAccounts(Set.of(actorUserId));
        requireActiveProjectMember(projectId, actorUserId, accounts);
    }

    private void requireExpectedTaskVersionPresent(Long expectedTaskVersion) {
        if (expectedTaskVersion == null) {
            throw new BusinessException(HttpStatus.PRECONDITION_REQUIRED.value(),
                    "expectedTaskVersion is required for task mutations");
        }
        if (expectedTaskVersion < 0L) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "expectedTaskVersion must be non-negative");
        }
    }

    private void requireRequestIdPresent(UUID requestId) {
        if (requestId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "requestId / Idempotency-Key is required for task mutations");
        }
    }

    private void checkExpectedVersion(Long expectedTaskVersion, TaskEntity task) {
        if (!Objects.equals(expectedTaskVersion, task.effectiveVersion())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Task has been modified (expected version " + expectedTaskVersion
                            + ", actual " + task.effectiveVersion() + "); refresh and retry");
        }
    }

    private void assertTaskSprintWritable(TaskEntity task) {
        if (task.getSprintId() == null) {
            return;
        }
        SprintEntity currentSprint = sprintRepository.findById(task.getSprintId()).orElse(null);
        if (currentSprint != null && currentSprint.getStatus() == SprintStatus.COMPLETED) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Tasks in a completed sprint are read-only");
        }
    }

    private boolean hasTaskHistory(TaskEntity node) {
        long id = node.getId();
        boolean hasSubmissions = submissionRepository != null && submissionRepository.existsByTaskId(id);
        boolean hasReviews = reviewRepository != null && reviewRepository.existsByTaskId(id);
        boolean hasEvaluations = evaluationRepository != null && evaluationRepository.existsByTaskId(id);
        boolean hasEvidenceStatus = node.getCompletionEvidenceStatus() != null;
        boolean hasSuccessorRef = taskRepository.existsBySuccessorOfTaskId(id);
        boolean hasOutcomes = projectAiPurgePort != null && projectAiPurgePort.hasTaskRecommendationOutcomes(id);
        return hasSubmissions || hasReviews || hasEvaluations || hasEvidenceStatus || hasSuccessorRef || hasOutcomes;
    }

    private void validateTargetSprint(long projectId, Long sprintId) {
        if (sprintId == null) {
            return;
        }
        SprintEntity sprint = sprintRepository.findById(sprintId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Sprint not found"));
        if (!Objects.equals(sprint.getProjectId(), projectId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Sprint must belong to the same project");
        }
        if (sprint.getStatus() == SprintStatus.COMPLETED) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Completed sprint is readonly");
        }
    }

    private List<TaskEntity> collectDescendantsLocked(long rootTaskId) {
        List<TaskEntity> result = new ArrayList<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        queue.add(rootTaskId);
        Set<Long> visited = new HashSet<>();
        visited.add(rootTaskId);
        while (!queue.isEmpty()) {
            Long currentParent = queue.poll();
            List<TaskEntity> children = taskRepository.findAllByParentIdIncludingDeleted(currentParent).stream()
                    .sorted(Comparator.comparing(TaskEntity::getId))
                    .toList();
            for (TaskEntity child : children) {
                if (visited.add(child.getId())) {
                    TaskEntity lockedChild = taskRepository.findByIdForUpdate(child.getId()).orElse(child);
                    result.add(lockedChild);
                    queue.add(lockedChild.getId());
                }
            }
        }
        return result;
    }

    private void validateLabelsBelongToProject(long projectId, List<Long> distinctLabelIds) {
        if (distinctLabelIds.isEmpty()) {
            return;
        }
        long validCount = labelRepository.findAllById(distinctLabelIds).stream()
                .filter(l -> Objects.equals(l.getProjectId(), projectId))
                .count();
        if (validCount != distinctLabelIds.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "One or more labels are invalid or do not belong to this project");
        }
    }

    private void replaceLabels(Long taskId, long projectId, List<Long> labelIds) {
        List<Long> distinctLabelIds = labelIds.stream().distinct().toList();
        validateLabelsBelongToProject(projectId, distinctLabelIds);
        taskLabelRepository.deleteByTaskId(taskId);
        taskLabelRepository.flush();
        if (!distinctLabelIds.isEmpty()) {
            List<TaskLabelEntity> taskLabels = distinctLabelIds.stream()
                    .map(labelId -> TaskLabelEntity.builder()
                            .id(new TaskLabelEntity.TaskLabelId(taskId, labelId))
                            .taskId(taskId)
                            .labelId(labelId)
                            .build())
                    .toList();
            taskLabelRepository.saveAll(taskLabels);
            taskLabelRepository.flush();
        }
    }

    private void validateActiveSkills(List<Long> distinctSkillIds) {
        if (distinctSkillIds.isEmpty()) {
            return;
        }
        List<SkillDto> validSkills = skillPort.findByIds(new HashSet<>(distinctSkillIds));
        if (validSkills.size() != distinctSkillIds.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "One or more required skills are invalid or inactive");
        }
    }

    private void replaceRequiredSkills(Long taskId, List<Long> skillIds) {
        List<Long> distinctSkillIds = skillIds.stream().distinct().toList();
        validateActiveSkills(distinctSkillIds);
        taskRequiredSkillRepository.deleteByTaskId(taskId);
        taskRequiredSkillRepository.flush();
        if (!distinctSkillIds.isEmpty()) {
            List<TaskRequiredSkillEntity> taskSkills = distinctSkillIds.stream()
                    .map(skillId -> TaskRequiredSkillEntity.builder()
                            .id(new TaskRequiredSkillEntity.TaskRequiredSkillId(taskId, skillId))
                            .taskId(taskId)
                            .skillId(skillId)
                            .build())
                    .toList();
            taskRequiredSkillRepository.saveAll(taskSkills);
            taskRequiredSkillRepository.flush();
        }
    }

    private List<Long> resolveSkillIdsByNames(String skills) {
        if (skills == null || skills.isBlank()) {
            return List.of();
        }
        Set<String> requestedNames = Arrays.stream(skills.split(","))
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

    private void sendAssignmentNotification(TaskEntity saved, Long targetAssigneeId) {
        if (notificationPort != null) {
            notificationPort.createNotification(new SystemNotificationCommandDto(
                    targetAssigneeId,
                    "Task Assigned",
                    "You have been assigned to task: " + saved.getTitle(),
                    "/tasks?taskId=" + saved.getId(),
                    NotificationTypeDto.ASSIGNED,
                    saved.getProjectId(),
                    saved.getId()));
        } else if (eventPublisher != null) {
            eventPublisher.publishEvent(new TaskAssignedEvent(
                    targetAssigneeId,
                    saved.getId(),
                    saved.getTitle(),
                    "/tasks?taskId=" + saved.getId(),
                    saved.getProjectId()));
        }
    }

    private Instant parseTaskDateOrThrow(Object raw, String timezoneId, TaskDateField field) {
        try {
            return TaskDatePolicy.parseTaskDate(raw, timezoneId, field);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), ex.getMessage());
        }
    }

    private void validateEffectiveDateRangeOrThrow(Instant startDate, Instant dueDate) {
        try {
            TaskDatePolicy.validateEffectiveRange(startDate, dueDate);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), ex.getMessage());
        }
    }

    private Instant txNow() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private Long parseOptionalAssigneeId(PatchTaskCommand command) {
        if (!command.isPresent("assigneeId")) {
            return null;
        }
        return parseNullableLong(command.valueOf("assigneeId"), "assigneeId");
    }

    private Long parseNullableLong(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            long l = number.longValue();
            if (number instanceof Double || number instanceof Float) {
                if (number.doubleValue() != (double) l) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid integer for " + field);
                }
            }
            return l;
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid ID for " + field);
            }
            try {
                return Long.valueOf(trimmed);
            } catch (NumberFormatException ex) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid ID for " + field);
            }
        }
        throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid ID for " + field);
    }

    private String parseNonBlankString(Object value, String field) {
        if (value == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), field + " cannot be null");
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), field + " cannot be blank");
        }
        return text;
    }

    private String parseNullableString(Object value) {
        return value == null ? null : value.toString();
    }

    private TaskStatus parseTaskStatus(Object value) {
        if (value instanceof TaskStatus status) {
            return status;
        }
        try {
            String normalized = value.toString().trim().replaceAll("[\\p{Pd}\\s]+", "_").toUpperCase(Locale.ROOT);
            return TaskStatus.valueOf(normalized);
        } catch (Exception ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Invalid task status. Allowed values: TODO, IN_PROGRESS, REVIEW, DONE");
        }
    }

    private PriorityLevel parsePriority(Object value) {
        if (value instanceof PriorityLevel priority) {
            return priority;
        }
        try {
            String normalized = value.toString().trim().replaceAll("[\\p{Pd}\\s]+", "_").toUpperCase(Locale.ROOT);
            return PriorityLevel.valueOf(normalized);
        } catch (Exception ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Invalid task priority. Allowed values: LOW, MEDIUM, HIGH, URGENT");
        }
    }

    private Float parseFloat(Object value, String field) {
        if (value instanceof Number number) {
            return number.floatValue();
        }
        try {
            return Float.valueOf(value.toString().trim());
        } catch (Exception ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid number for " + field);
        }
    }

    private Float validatePosition(Float pos) {
        if (pos == null || pos.isNaN() || pos.isInfinite()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid kanban position");
        }
        return pos;
    }

    private Integer parseInteger(Object value, String field) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(value.toString().trim());
        } catch (Exception ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid integer for " + field);
        }
    }

    private Integer normalizeDifficulty(Integer level) {
        if (level == null || level < 1 || level > 10) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "difficultyLevel must be between 1 and 10");
        }
        return level;
    }

    private List<Long> parseDistinctIdList(Object value, String field) {
        if (!(value instanceof Collection<?> col)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), field + " must be a list of IDs");
        }
        List<Long> result = new ArrayList<>();
        for (Object item : col) {
            Long id = parseNullableLong(item, field);
            if (id == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(), field + " cannot contain null elements");
            }
            if (!result.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    private TaskDto replayStoredTask(
            TaskCommandIdempotencyEntity existing,
            String payloadHash,
            Long expectedTaskId) {
        if (!Objects.equals(existing.getPayloadHash(), payloadHash)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Idempotency key already used with a different request payload");
        }
        if (expectedTaskId != null && existing.getTaskId() != null
                && !Objects.equals(existing.getTaskId(), expectedTaskId)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Idempotency key already used for a different task");
        }
        try {
            return CANONICAL_MAPPER.readValue(existing.getResponseJson(), TaskDto.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize stored task idempotency response", ex);
        }
    }

    private void recordIdempotency(
            long actorUserId,
            String operation,
            UUID requestId,
            long projectId,
            Long taskId,
            String payloadHash,
            int httpStatus,
            Object response) {
        try {
            String responseJson = response == null ? null : CANONICAL_MAPPER.writeValueAsString(response);
            idempotencyRepository.saveAndFlush(TaskCommandIdempotencyEntity.builder()
                    .actorUserId(actorUserId)
                    .operation(operation)
                    .requestId(requestId)
                    .projectId(projectId)
                    .taskId(taskId)
                    .payloadHash(payloadHash)
                    .httpStatus(httpStatus)
                    .responseJson(responseJson)
                    .createdAt(txNow())
                    .build());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize task idempotency response", ex);
        }
    }

    private String computePatchPayloadHash(
            String operation,
            PatchTaskCommand command,
            String explicitAssignmentDecisionId) {
        Map<String, Object> sortedValues = new TreeMap<>();
        for (String key : command.presentFields()) {
            sortedValues.put(key, command.valueOf(key));
        }
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("operation", operation);
        canonical.put("taskId", command.taskId());
        canonical.put("expectedTaskVersion", command.expectedTaskVersion());
        if (explicitAssignmentDecisionId != null) {
            canonical.put("assignmentDecisionId", explicitAssignmentDecisionId);
        }
        canonical.put("values", sortedValues);
        return sha256Json(canonical);
    }

    private String computeCreatePayloadHash(CreateTaskRequest request) {
        Map<String, Object> normalizedRequest = new LinkedHashMap<>();
        normalizedRequest.put("projectId", request.projectId());
        normalizedRequest.put("parentId", request.parentId());
        normalizedRequest.put("sprintId", request.sprintId());
        normalizedRequest.put("title", request.title());
        normalizedRequest.put("description", request.description());
        normalizedRequest.put("priority", request.priority());
        normalizedRequest.put("position", request.position());
        normalizedRequest.put("labelIds", request.labelIds());
        normalizedRequest.put("difficultyLevel", request.difficultyLevel());
        normalizedRequest.put("requiredSkillIds", request.requiredSkillIds());
        normalizedRequest.put("assigneeId", request.assigneeId());
        normalizedRequest.put("startDate", request.startDate());
        normalizedRequest.put("dueDate", request.dueDate());
        normalizedRequest.put("successorOfTaskId", request.successorOfTaskId());
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("operation", "CREATE_TASK");
        canonical.put("request", normalizedRequest);
        return sha256Json(canonical);
    }

    private String computeSuccessorPayloadHash(
            long sourceTaskId,
            String title,
            String description,
            PriorityLevel priority,
            Long targetSprintId,
            Integer difficulty,
            Long assigneeId,
            String startDate,
            String dueDate,
            List<Long> labelIds,
            List<Long> skillIds) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("sourceTaskId", sourceTaskId);
        normalized.put("title", title);
        normalized.put("description", description);
        normalized.put("priority", priority);
        normalized.put("sprintId", targetSprintId);
        normalized.put("difficultyLevel", difficulty);
        normalized.put("assigneeId", assigneeId);
        normalized.put("startDate", startDate);
        normalized.put("dueDate", dueDate);
        normalized.put("labelIds", labelIds);
        normalized.put("requiredSkillIds", skillIds);
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("operation", "CREATE_SUCCESSOR_TASK");
        canonical.put("request", normalized);
        return sha256Json(canonical);
    }

    private String toJsonString(Object value) {
        try {
            return CANONICAL_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize JSON payload", ex);
        }
    }

    private String computeDeletePayloadHash(long taskId, long expectedTaskVersion) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("operation", "DELETE_TASK");
        canonical.put("taskId", taskId);
        canonical.put("expectedTaskVersion", expectedTaskVersion);
        return sha256Json(canonical);
    }

    private String sha256Json(Object value) {
        try {
            byte[] bytes = CANONICAL_MAPPER.writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash task command payload", ex);
        }
    }

    private record TaskContext(
            ProjectEntity project,
            TaskEntity task,
            ProjectMemberEntity actorMember,
            Map<Long, AccountAuthStateDto> accounts) {
    }
}
