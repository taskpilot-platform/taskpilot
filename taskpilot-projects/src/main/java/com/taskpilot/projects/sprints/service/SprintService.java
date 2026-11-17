package com.taskpilot.projects.sprints.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.taskpilot.contracts.user.dto.AccountAuthStateDto;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.util.ValidationUtils;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.entity.SprintClosureSnapshotEntity;
import com.taskpilot.projects.common.entity.SprintEntity;
import com.taskpilot.projects.common.entity.TaskCommandIdempotencyEntity;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.ProjectStatus;
import com.taskpilot.projects.common.enums.SprintStatus;
import com.taskpilot.projects.common.enums.TaskStatus;
import com.taskpilot.projects.common.enums.WorkflowMode;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.common.repository.SprintClosureSnapshotRepository;
import com.taskpilot.projects.common.repository.SprintRepository;
import com.taskpilot.projects.common.repository.TaskCommandIdempotencyRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.projects.service.ProjectAccountLifecycleAdapter;
import com.taskpilot.projects.sprints.dto.BacklogResponse;
import com.taskpilot.projects.sprints.dto.BoardResponse;
import com.taskpilot.projects.sprints.dto.CompleteSprintRequest;
import com.taskpilot.projects.sprints.dto.CreateSprintRequest;
import com.taskpilot.projects.sprints.dto.SprintBacklogSection;
import com.taskpilot.projects.sprints.dto.SprintClosureSnapshotDto;
import com.taskpilot.projects.sprints.dto.SprintCompletePreviewResponse;
import com.taskpilot.projects.sprints.dto.SprintDto;
import com.taskpilot.projects.sprints.dto.UpdateSprintRequest;
import com.taskpilot.projects.tasks.service.TaskDtoMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SprintService {

    private static final ObjectMapper CANONICAL_MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();
    private static final String OP_COMPLETE_SPRINT = "COMPLETE_SPRINT";

    private final SprintRepository sprintRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectSecurityService projectSecurityService;
    private final TaskRepository taskRepository;
    private final TaskDtoMapper taskDtoMapper;
    private final SprintClosureSnapshotRepository closureSnapshotRepository;
    private final TaskCommandIdempotencyRepository idempotencyRepository;
    private final AccountAuthStatePort accountAuthStatePort;
    private final Clock clock;

    @Autowired
    public SprintService(
            SprintRepository sprintRepository,
            ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository,
            ProjectSecurityService projectSecurityService,
            TaskRepository taskRepository,
            TaskDtoMapper taskDtoMapper,
            SprintClosureSnapshotRepository closureSnapshotRepository,
            TaskCommandIdempotencyRepository idempotencyRepository,
            AccountAuthStatePort accountAuthStatePort) {
        this(sprintRepository, projectRepository, projectMemberRepository, projectSecurityService,
                taskRepository, taskDtoMapper, closureSnapshotRepository, idempotencyRepository,
                accountAuthStatePort, Clock.systemUTC());
    }

    public SprintService(
            SprintRepository sprintRepository,
            ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository,
            ProjectSecurityService projectSecurityService,
            TaskRepository taskRepository,
            TaskDtoMapper taskDtoMapper,
            SprintClosureSnapshotRepository closureSnapshotRepository,
            TaskCommandIdempotencyRepository idempotencyRepository,
            AccountAuthStatePort accountAuthStatePort,
            Clock clock) {
        this.sprintRepository = sprintRepository;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.projectSecurityService = projectSecurityService;
        this.taskRepository = taskRepository;
        this.taskDtoMapper = taskDtoMapper;
        this.closureSnapshotRepository = closureSnapshotRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.accountAuthStatePort = accountAuthStatePort;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Transactional
    public SprintDto createSprint(Long projectId, CreateSprintRequest request, Long userId) {
        lockProjectAndRequireActiveManager(projectId, userId);
        ValidationUtils.validateDateRange(request.startDate(), request.endDate(),
                "Sprint end date must be greater than or equal to start date");

        SprintEntity sprint = SprintEntity.builder()
                .projectId(projectId)
                .name(request.name())
                .goal(request.goal())
                .status(SprintStatus.PLANNING)
                .startDate(request.startDate())
                .endDate(request.endDate())
                .version(0L)
                .build();
        return SprintDto.fromEntity(sprintRepository.saveAndFlush(sprint));
    }

    @Transactional(readOnly = true)
    public List<SprintDto> listSprints(Long projectId, Long userId) {
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);
        List<SprintEntity> sprints = sortedSprints(projectId);
        List<Long> sprintIds = sprints.stream().map(SprintEntity::getId).toList();
        Map<Long, SprintClosureSnapshotDto> snapshots = sprintIds.isEmpty() || closureSnapshotRepository == null
                ? Map.of()
                : closureSnapshotRepository.findBySprintIdIn(sprintIds).stream()
                        .collect(Collectors.toMap(
                                SprintClosureSnapshotEntity::getSprintId,
                                SprintClosureSnapshotDto::fromEntity,
                                (a, b) -> a));
        return sprints.stream()
                .map(s -> SprintDto.fromEntity(s, snapshots.get(s.getId())))
                .toList();
    }

    @Transactional
    public SprintDto updateSprint(Long projectId, Long sprintId, UpdateSprintRequest request, Long userId) {
        lockProjectAndRequireActiveManager(projectId, userId);
        SprintEntity sprint = findSprintInProjectForUpdate(projectId, sprintId);

        if (sprint.getStatus() == SprintStatus.COMPLETED) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Completed sprint is readonly");
        }

        LocalDate nextStart = request.startDate() != null ? request.startDate() : sprint.getStartDate();
        LocalDate nextEnd = request.endDate() != null ? request.endDate() : sprint.getEndDate();
        ValidationUtils.validateDateRange(nextStart, nextEnd,
                "Sprint end date must be greater than or equal to start date");

        if (request.name() != null && !request.name().isBlank()) {
            sprint.setName(request.name());
        }
        if (request.goal() != null) {
            sprint.setGoal(request.goal());
        }
        if (request.startDate() != null) {
            sprint.setStartDate(request.startDate());
        }
        if (request.endDate() != null) {
            sprint.setEndDate(request.endDate());
        }
        return SprintDto.fromEntity(sprintRepository.saveAndFlush(sprint));
    }

    @Transactional
    public void deleteSprint(Long projectId, Long sprintId, Long userId) {
        lockProjectAndRequireActiveManager(projectId, userId);
        SprintEntity sprint = findSprintInProjectForUpdate(projectId, sprintId);

        if (sprint.getStatus() != SprintStatus.PLANNING) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Only planning sprint can be deleted");
        }

        taskRepository.clearSprintId(sprintId, txNow());
        sprintRepository.delete(sprint);
        sprintRepository.flush();
    }

    @Transactional
    public SprintDto startSprint(Long projectId, Long sprintId, Long userId) {
        lockProjectAndRequireActiveManager(projectId, userId);
        SprintEntity sprint = findSprintInProjectForUpdate(projectId, sprintId);

        if (sprint.getStatus() != SprintStatus.PLANNING) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Only planning sprint can be started");
        }
        long activeCount = sprintRepository.countByProjectIdAndStatus(projectId, SprintStatus.ACTIVE);
        if (activeCount > 1L) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Project has duplicate active sprints requiring repair before starting another sprint");
        }
        if (activeCount == 1L) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Project already has an active sprint");
        }

        sprint.setStatus(SprintStatus.ACTIVE);
        return SprintDto.fromEntity(sprintRepository.saveAndFlush(sprint));
    }

    @Transactional(readOnly = true)
    public SprintCompletePreviewResponse getCompletePreview(Long projectId, Long sprintId, Long userId) {
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateManager(projectId, userId);
        SprintEntity sprint = findSprintInProject(projectId, sprintId);
        if (sprint.getStatus() != SprintStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Only active sprint can be completed");
        }

        List<TaskEntity> activeTasks = taskRepository.findBySprintId(sprintId).stream()
                .sorted(Comparator.comparing(TaskEntity::getId))
                .toList();

        int doneCount = 0;
        int todoCount = 0;
        int inProgressCount = 0;
        int reviewCount = 0;
        List<Long> unfinishedIds = new ArrayList<>();
        List<SprintCompletePreviewResponse.UnfinishedTaskItem> unfinishedItems = new ArrayList<>();

        for (TaskEntity task : activeTasks) {
            if (task.getStatus() == TaskStatus.DONE) {
                doneCount++;
            } else {
                switch (task.getStatus()) {
                    case TODO -> todoCount++;
                    case IN_PROGRESS -> inProgressCount++;
                    case REVIEW -> reviewCount++;
                    default -> {
                    }
                }
                unfinishedIds.add(task.getId());
                unfinishedItems.add(new SprintCompletePreviewResponse.UnfinishedTaskItem(
                        task.getId(),
                        task.getTitle(),
                        task.getStatus(),
                        task.getAssigneeId(),
                        task.effectiveVersion(),
                        task.getStatus() == TaskStatus.REVIEW && task.getCurrentSubmissionId() != null));
            }
        }

        List<SprintCompletePreviewResponse.TargetSprintOption> targetOptions = sprintRepository
                .findByProjectIdAndStatusInOrderByStartDateAscIdAsc(
                        projectId, List.of(SprintStatus.PLANNING, SprintStatus.ACTIVE))
                .stream()
                .filter(s -> !Objects.equals(s.getId(), sprintId))
                .map(s -> new SprintCompletePreviewResponse.TargetSprintOption(
                        s.getId(), s.getName(), s.getStatus(), s.effectiveVersion(), s.getStartDate(), s.getEndDate()))
                .toList();

        return new SprintCompletePreviewResponse(
                sprint.getId(),
                sprint.getName(),
                sprint.effectiveVersion(),
                activeTasks.size(),
                doneCount,
                unfinishedItems.size(),
                todoCount,
                inProgressCount,
                reviewCount,
                unfinishedIds,
                unfinishedItems,
                targetOptions);
    }

    @Transactional(readOnly = true)
    public SprintClosureSnapshotDto getClosureSnapshot(Long projectId, Long sprintId, Long userId) {
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);
        findSprintInProject(projectId, sprintId);
        SprintClosureSnapshotEntity snapshot = closureSnapshotRepository.findBySprintId(sprintId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                        "Closure snapshot not found for sprint"));
        return SprintClosureSnapshotDto.fromEntity(snapshot);
    }

    @Transactional
    public SprintDto completeSprint(
            Long projectId,
            Long sprintId,
            CompleteSprintRequest request,
            Long userId,
            UUID headerRequestId) {
        if (userId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Authentication required");
        }
        if (request == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Sprint completion request with expectedSprintVersion, requestId, and carryover is required");
        }
        UUID effectiveRequestId = headerRequestId != null ? headerRequestId : request.requestId();
        if (effectiveRequestId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "requestId / Idempotency-Key is required for sprint completion");
        }
        if (request.expectedSprintVersion() == null) {
            throw new BusinessException(HttpStatus.PRECONDITION_REQUIRED.value(),
                    "expectedSprintVersion is required to complete a sprint");
        }
        if (request.expectedSprintVersion() < 0L) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "expectedSprintVersion must be non-negative");
        }
        if (request.carryover() == null || request.carryover().destination() == null
                || request.carryover().destination().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Explicit carryover destination (BACKLOG or SPRINT) is required");
        }

        String destination = request.carryover().destination().trim().toUpperCase(Locale.ROOT);
        Long targetSprintId = request.carryover().targetSprintId();
        if ("BACKLOG".equals(destination)) {
            if (targetSprintId != null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "targetSprintId must be null when carryover destination is BACKLOG");
            }
        } else if ("SPRINT".equals(destination)) {
            if (targetSprintId == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "targetSprintId is required when carryover destination is SPRINT");
            }
            if (Objects.equals(targetSprintId, sprintId)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "Carryover target sprint cannot be the sprint being completed");
            }
        } else {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Invalid carryover destination. Allowed: BACKLOG, SPRINT");
        }

        String payloadHash = computeCompletePayloadHash(projectId, sprintId, request.expectedSprintVersion(),
                destination, targetSprintId, request.expectedUnfinishedTaskIds());

        // Canonical lock order: project -> actor account -> sprint(s) -> active tasks in sprint ascending
        lockProjectAndRequireActiveManager(projectId, userId);

        if (idempotencyRepository != null) {
            Optional<TaskCommandIdempotencyEntity> existing = idempotencyRepository
                    .findByActorUserIdAndOperationAndRequestId(userId, OP_COMPLETE_SPRINT, effectiveRequestId);
            if (existing.isPresent()) {
                TaskCommandIdempotencyEntity row = existing.get();
                if (!Objects.equals(row.getProjectId(), projectId)
                        || !Objects.equals(row.getPayloadHash(), payloadHash)) {
                    throw new BusinessException(HttpStatus.CONFLICT.value(),
                            "Idempotency key already used with a different sprint completion payload");
                }
                try {
                    return CANONICAL_MAPPER.readValue(row.getResponseJson(), SprintDto.class);
                } catch (JsonProcessingException ex) {
                    throw new IllegalStateException("Failed to deserialize stored sprint completion response", ex);
                }
            }
        }

        SprintEntity sprint = findSprintInProjectForUpdate(projectId, sprintId);
        if (!Objects.equals(request.expectedSprintVersion(), sprint.effectiveVersion())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Sprint has been modified (expected version " + request.expectedSprintVersion()
                            + ", actual " + sprint.effectiveVersion() + "); refresh and retry");
        }
        if (sprint.getStatus() != SprintStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Only active sprint can be completed");
        }

        SprintEntity targetSprint = null;
        if ("SPRINT".equals(destination)) {
            targetSprint = sprintRepository.findByIdForUpdate(targetSprintId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Target sprint not found"));
            if (!Objects.equals(targetSprint.getProjectId(), projectId)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "Target sprint must belong to the same project");
            }
            if (targetSprint.getStatus() == SprintStatus.COMPLETED) {
                throw new BusinessException(HttpStatus.CONFLICT.value(),
                        "Cannot carry over tasks into a completed sprint");
            }
        }

        List<TaskEntity> activeTasks = taskRepository.findActiveBySprintIdOrderByIdAscForUpdate(sprintId);
        List<TaskEntity> doneTasks = new ArrayList<>();
        List<TaskEntity> unfinishedTasks = new ArrayList<>();
        int todoCount = 0;
        int inProgressCount = 0;
        int reviewCount = 0;

        for (TaskEntity task : activeTasks) {
            if (task.getStatus() == TaskStatus.DONE) {
                doneTasks.add(task);
            } else {
                unfinishedTasks.add(task);
                switch (task.getStatus()) {
                    case TODO -> todoCount++;
                    case IN_PROGRESS -> inProgressCount++;
                    case REVIEW -> reviewCount++;
                    default -> {
                    }
                }
            }
        }

        if (request.expectedUnfinishedTaskIds() != null) {
            Set<Long> actualUnfinished = unfinishedTasks.stream().map(TaskEntity::getId).collect(Collectors.toSet());
            Set<Long> expectedUnfinished = new HashSet<>(request.expectedUnfinishedTaskIds());
            if (!actualUnfinished.equals(expectedUnfinished)
                    || expectedUnfinished.size() != request.expectedUnfinishedTaskIds().size()) {
                throw new BusinessException(HttpStatus.CONFLICT.value(),
                        "Sprint tasks changed since preview; refresh and retry");
            }
        }

        Instant txNow = txNow();
        Long carryoverSprintId = targetSprint != null ? targetSprint.getId() : null;

        List<Map<String, Object>> snapshotItems = new ArrayList<>(activeTasks.size());
        for (TaskEntity task : activeTasks) {
            boolean isDone = task.getStatus() == TaskStatus.DONE;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskId", task.getId());
            item.put("title", task.getTitle());
            item.put("status", task.getStatus().name());
            item.put("priority", task.getPriority() != null ? task.getPriority().name() : null);
            item.put("difficultyLevel", task.getDifficultyLevel());
            item.put("assigneeId", task.getAssigneeId());
            item.put("dueDate", task.getDueDate());
            item.put("completedAt", task.getCompletedAt());
            item.put("completionEvidenceStatus",
                    task.getCompletionEvidenceStatus() != null ? task.getCompletionEvidenceStatus().name() : null);
            item.put("currentSubmissionId", task.getCurrentSubmissionId());
            item.put("currentEvaluationId", task.getCurrentEvaluationId());
            item.put("closureAction", isDone ? "RETAINED_DONE" : "CARRIED_OVER");
            item.put("destinationSprintId", isDone ? sprintId : carryoverSprintId);
            snapshotItems.add(item);
        }

        if (!unfinishedTasks.isEmpty()) {
            for (TaskEntity task : unfinishedTasks) {
                task.setSprintId(carryoverSprintId);
                task.markMutated(txNow);
            }
            taskRepository.saveAllAndFlush(unfinishedTasks);
        }

        SprintClosureSnapshotEntity snapshot = closureSnapshotRepository.saveAndFlush(
                SprintClosureSnapshotEntity.builder()
                        .sprintId(sprint.getId())
                        .projectId(projectId)
                        .sprintName(sprint.getName())
                        .sprintVersionAtClose(sprint.effectiveVersion())
                        .carryoverDestination(destination)
                        .targetSprintId(carryoverSprintId)
                        .totalTasksAtClose(activeTasks.size())
                        .doneTasksRetained(doneTasks.size())
                        .carriedOverTotal(unfinishedTasks.size())
                        .carriedOverTodo(todoCount)
                        .carriedOverInProgress(inProgressCount)
                        .carriedOverReview(reviewCount)
                        .snapshotPayloadJson(toJson(snapshotItems))
                        .closedByUserId(userId)
                        .closedAt(txNow)
                        .requestId(effectiveRequestId)
                        .build());

        sprint.setStatus(SprintStatus.COMPLETED);
        sprint.setCompletedAt(txNow);
        sprint.setCompletedByUserId(userId);
        SprintEntity savedSprint = sprintRepository.saveAndFlush(sprint);

        SprintDto result = SprintDto.fromEntity(savedSprint, SprintClosureSnapshotDto.fromEntity(snapshot));
        if (idempotencyRepository != null) {
            idempotencyRepository.saveAndFlush(TaskCommandIdempotencyEntity.builder()
                    .actorUserId(userId)
                    .operation(OP_COMPLETE_SPRINT)
                    .requestId(effectiveRequestId)
                    .projectId(projectId)
                    .taskId(null)
                    .payloadHash(payloadHash)
                    .httpStatus(HttpStatus.OK.value())
                    .responseJson(toJson(result))
                    .createdAt(txNow)
                    .build());
        }
        return result;
    }

    @Transactional(readOnly = true)
    public BacklogResponse getBacklog(Long projectId, Long userId) {
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        var unscheduled = taskDtoMapper.mapToDtoWithLabels(
                taskRepository.findByProjectIdAndSprintIdIsNullOrderByPositionAsc(projectId));

        List<SprintEntity> sprints = sortedSprints(projectId);
        List<Long> sprintIds = sprints.stream().map(SprintEntity::getId).toList();
        Map<Long, SprintClosureSnapshotDto> snapshots = sprintIds.isEmpty() || closureSnapshotRepository == null
                ? Map.of()
                : closureSnapshotRepository.findBySprintIdIn(sprintIds).stream()
                        .collect(Collectors.toMap(
                                SprintClosureSnapshotEntity::getSprintId,
                                SprintClosureSnapshotDto::fromEntity,
                                (a, b) -> a));

        List<SprintBacklogSection> sections = sprints.stream()
                .map(sprint -> new SprintBacklogSection(
                        SprintDto.fromEntity(sprint, snapshots.get(sprint.getId())),
                        taskDtoMapper.mapToDtoWithLabels(taskRepository.findBySprintId(sprint.getId()).stream()
                                .sorted(Comparator.comparing(TaskEntity::getPosition,
                                        Comparator.nullsLast(Float::compareTo)))
                                .toList())))
                .toList();

        return new BacklogResponse(unscheduled, sections);
    }

    @Transactional(readOnly = true)
    public BoardResponse getBoard(Long projectId, Long userId) {
        ProjectEntity project = projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        if (project.getWorkflowMode() == WorkflowMode.SCRUM) {
            List<SprintEntity> activeSprints = sprintRepository.findByProjectIdAndStatusOrderByIdAsc(
                    projectId, SprintStatus.ACTIVE);
            if (activeSprints.size() > 1) {
                throw new BusinessException(HttpStatus.CONFLICT.value(),
                        "Project has duplicate active sprints requiring repair");
            }
            if (activeSprints.size() == 1) {
                SprintEntity active = activeSprints.getFirst();
                return new BoardResponse(
                        project.getWorkflowMode(),
                        SprintDto.fromEntity(active),
                        taskDtoMapper.mapToDtoWithLabels(taskRepository.findBySprintId(active.getId())));
            }
            return new BoardResponse(project.getWorkflowMode(), null, List.of());
        }

        return new BoardResponse(
                project.getWorkflowMode(),
                null,
                taskDtoMapper.mapToDtoWithLabels(
                        taskRepository.findByProjectIdAndSprintIdIsNullOrderByPositionAsc(projectId)));
    }

    private void lockProjectAndRequireActiveManager(Long projectId, Long userId) {
        if (userId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Authentication required");
        }
        ProjectEntity project = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));
        if (project.getStatus() == ProjectStatus.ARCHIVED) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Project is archived");
        }
        if (accountAuthStatePort != null) {
            Map<Long, AccountAuthStateDto> accounts = accountAuthStatePort.lockAndLoadByIds(Set.of(userId));
            AccountAuthStateDto actor = accounts.get(userId);
            if (actor == null || !ProjectAccountLifecycleAdapter.isEffectiveActive(actor)) {
                throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                        "Account is not active and verified for this operation");
            }
        }
        ProjectMemberEntity member = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN.value(),
                        "You are not a member of this project"));
        if (member.getRole() != MemberRole.MANAGER) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Only an active Project Manager can perform this sprint operation");
        }
    }

    private List<SprintEntity> sortedSprints(Long projectId) {
        return sprintRepository.findByProjectIdOrderByStartDateAscIdAsc(projectId);
    }

    private SprintEntity findSprintInProject(Long projectId, Long sprintId) {
        SprintEntity sprint = sprintRepository.findById(sprintId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Sprint not found"));
        if (!Objects.equals(sprint.getProjectId(), projectId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Sprint does not belong to this project");
        }
        return sprint;
    }

    private SprintEntity findSprintInProjectForUpdate(Long projectId, Long sprintId) {
        SprintEntity sprint = sprintRepository.findByIdForUpdate(sprintId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Sprint not found"));
        if (!Objects.equals(sprint.getProjectId(), projectId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Sprint does not belong to this project");
        }
        return sprint;
    }

    private Instant txNow() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private String computeCompletePayloadHash(
            long projectId,
            long sprintId,
            long expectedSprintVersion,
            String destination,
            Long targetSprintId,
            List<Long> expectedUnfinishedTaskIds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", OP_COMPLETE_SPRINT);
        m.put("projectId", projectId);
        m.put("sprintId", sprintId);
        m.put("expectedSprintVersion", expectedSprintVersion);
        m.put("destination", destination);
        m.put("targetSprintId", targetSprintId);
        m.put("expectedUnfinishedTaskIds", expectedUnfinishedTaskIds);
        return sha256String(toJson(m));
    }

    private String toJson(Object value) {
        try {
            return CANONICAL_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize sprint JSON", ex);
        }
    }

    private String sha256String(String text) {
        try {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash sprint payload", ex);
        }
    }
}
