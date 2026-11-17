package com.taskpilot.projects.tasks.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.taskpilot.contracts.assignment.dto.UserProfileDto;
import com.taskpilot.contracts.assignment.port.out.UserPort;
import com.taskpilot.contracts.task.EvidenceStatus;
import com.taskpilot.contracts.task.RecoverLegacyReviewCommand;
import com.taskpilot.contracts.task.ReopenTaskCommand;
import com.taskpilot.contracts.task.ReviewDecision;
import com.taskpilot.contracts.task.ReviewTaskCommand;
import com.taskpilot.contracts.task.SubmitTaskCommand;
import com.taskpilot.contracts.task.SupersedeEvaluationCommand;
import com.taskpilot.contracts.task.TaskEvaluationAccepted;
import com.taskpilot.contracts.task.TaskEvaluationInvalidated;
import com.taskpilot.contracts.task.TaskQualityRubric;
import com.taskpilot.contracts.task.TaskWorkflowCommandPort;
import com.taskpilot.contracts.task.TaskWorkflowResult;
import com.taskpilot.contracts.task.TaskWorkflowState;
import com.taskpilot.contracts.task.TimelinessStatus;
import com.taskpilot.contracts.task.WithdrawTaskCommand;
import com.taskpilot.contracts.user.dto.AccountAuthStateDto;
import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.contracts.user.dto.NotificationTypeDto;
import com.taskpilot.contracts.user.dto.SystemNotificationCommandDto;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.contracts.user.port.out.UserNotificationPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.entity.SprintClosureCorrectionEntity;
import com.taskpilot.projects.common.entity.SprintEntity;
import com.taskpilot.projects.common.entity.TaskCommandIdempotencyEntity;
import com.taskpilot.projects.common.entity.TaskDeadlineRevisionEntity;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.entity.TaskEvaluationEntity;
import com.taskpilot.projects.common.entity.TaskEvaluationInvalidationEntity;
import com.taskpilot.projects.common.entity.TaskEvidenceAvailabilityEventEntity;
import com.taskpilot.projects.common.entity.TaskRequiredSkillEntity;
import com.taskpilot.projects.common.entity.TaskReviewEntity;
import com.taskpilot.projects.common.entity.TaskSubmissionEntity;
import com.taskpilot.projects.common.entity.TaskSubmissionEvidenceFileEntity;
import com.taskpilot.projects.common.entity.TaskWorkflowJournalEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.ProjectStatus;
import com.taskpilot.projects.common.enums.SprintStatus;
import com.taskpilot.projects.common.enums.TaskStatus;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.common.repository.SprintClosureCorrectionRepository;
import com.taskpilot.projects.common.repository.SprintRepository;
import com.taskpilot.projects.common.repository.TaskCommandIdempotencyRepository;
import com.taskpilot.projects.common.repository.TaskDeadlineRevisionRepository;
import com.taskpilot.projects.common.repository.TaskEvaluationInvalidationRepository;
import com.taskpilot.projects.common.repository.TaskEvaluationRepository;
import com.taskpilot.projects.common.repository.TaskEvidenceAvailabilityEventRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
import com.taskpilot.projects.common.repository.TaskRequiredSkillRepository;
import com.taskpilot.projects.common.repository.TaskReviewRepository;
import com.taskpilot.projects.common.repository.TaskSubmissionEvidenceFileRepository;
import com.taskpilot.projects.common.repository.TaskSubmissionRepository;
import com.taskpilot.projects.common.repository.TaskWorkflowJournalRepository;
import com.taskpilot.projects.projects.service.ProjectAccountLifecycleAdapter;
import com.taskpilot.projects.tasks.dto.TaskWorkflowHistoryDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TaskWorkflowService implements TaskWorkflowCommandPort {

    private static final ObjectMapper CANONICAL_MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    private static final String OP_SUBMIT = "WORKFLOW_SUBMIT";
    private static final String OP_WITHDRAW = "WORKFLOW_WITHDRAW";
    private static final String OP_REVIEW = "WORKFLOW_REVIEW";
    private static final String OP_REOPEN = "WORKFLOW_REOPEN";
    private static final String OP_SUPERSEDE = "WORKFLOW_SUPERSEDE";
    private static final String OP_RECOVER_LEGACY_REVIEW = "WORKFLOW_RECOVER_LEGACY_REVIEW";

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final SprintRepository sprintRepository;
    private final ProjectFileRepository projectFileRepository;
    private final TaskRequiredSkillRepository taskRequiredSkillRepository;
    private final TaskDeadlineRevisionRepository deadlineRevisionRepository;
    private final TaskSubmissionRepository submissionRepository;
    private final TaskSubmissionEvidenceFileRepository evidenceFileRepository;
    private final TaskEvidenceAvailabilityEventRepository availabilityEventRepository;
    private final TaskReviewRepository reviewRepository;
    private final TaskEvaluationRepository evaluationRepository;
    private final TaskEvaluationInvalidationRepository invalidationRepository;
    private final TaskWorkflowJournalRepository journalRepository;
    private final TaskCommandIdempotencyRepository idempotencyRepository;
    private final AccountAuthStatePort accountAuthStatePort;
    private final UserPort userPort;
    private final UserNotificationPort notificationPort;
    private final TaskWorkflowJournalDispatcher journalDispatcher;
    private final Clock clock;
    private SprintClosureCorrectionRepository sprintClosureCorrectionRepository;

    @Autowired(required = false)
    void setSprintClosureCorrectionRepository(SprintClosureCorrectionRepository sprintClosureCorrectionRepository) {
        this.sprintClosureCorrectionRepository = sprintClosureCorrectionRepository;
    }

    @Autowired
    public TaskWorkflowService(
            TaskRepository taskRepository,
            ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository,
            SprintRepository sprintRepository,
            ProjectFileRepository projectFileRepository,
            TaskRequiredSkillRepository taskRequiredSkillRepository,
            TaskDeadlineRevisionRepository deadlineRevisionRepository,
            TaskSubmissionRepository submissionRepository,
            TaskSubmissionEvidenceFileRepository evidenceFileRepository,
            TaskEvidenceAvailabilityEventRepository availabilityEventRepository,
            TaskReviewRepository reviewRepository,
            TaskEvaluationRepository evaluationRepository,
            TaskEvaluationInvalidationRepository invalidationRepository,
            TaskWorkflowJournalRepository journalRepository,
            TaskCommandIdempotencyRepository idempotencyRepository,
            AccountAuthStatePort accountAuthStatePort,
            UserPort userPort,
            UserNotificationPort notificationPort,
            TaskWorkflowJournalDispatcher journalDispatcher,
            Optional<Clock> clock) {
        this(taskRepository, projectRepository, projectMemberRepository, sprintRepository,
                projectFileRepository, taskRequiredSkillRepository, deadlineRevisionRepository,
                submissionRepository, evidenceFileRepository, availabilityEventRepository,
                reviewRepository, evaluationRepository, invalidationRepository, journalRepository,
                idempotencyRepository, accountAuthStatePort, userPort, notificationPort,
                journalDispatcher, clock != null ? clock.orElse(Clock.systemUTC()) : Clock.systemUTC());
    }

    public TaskWorkflowService(
            TaskRepository taskRepository,
            ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository,
            SprintRepository sprintRepository,
            ProjectFileRepository projectFileRepository,
            TaskRequiredSkillRepository taskRequiredSkillRepository,
            TaskDeadlineRevisionRepository deadlineRevisionRepository,
            TaskSubmissionRepository submissionRepository,
            TaskSubmissionEvidenceFileRepository evidenceFileRepository,
            TaskEvidenceAvailabilityEventRepository availabilityEventRepository,
            TaskReviewRepository reviewRepository,
            TaskEvaluationRepository evaluationRepository,
            TaskEvaluationInvalidationRepository invalidationRepository,
            TaskWorkflowJournalRepository journalRepository,
            TaskCommandIdempotencyRepository idempotencyRepository,
            AccountAuthStatePort accountAuthStatePort,
            UserPort userPort,
            UserNotificationPort notificationPort,
            TaskWorkflowJournalDispatcher journalDispatcher,
            Clock clock) {
        this.taskRepository = taskRepository;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.sprintRepository = sprintRepository;
        this.projectFileRepository = projectFileRepository;
        this.taskRequiredSkillRepository = taskRequiredSkillRepository;
        this.deadlineRevisionRepository = deadlineRevisionRepository;
        this.submissionRepository = submissionRepository;
        this.evidenceFileRepository = evidenceFileRepository;
        this.availabilityEventRepository = availabilityEventRepository;
        this.reviewRepository = reviewRepository;
        this.evaluationRepository = evaluationRepository;
        this.invalidationRepository = invalidationRepository;
        this.journalRepository = journalRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.accountAuthStatePort = accountAuthStatePort;
        this.userPort = userPort;
        this.notificationPort = notificationPort;
        this.journalDispatcher = journalDispatcher;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Transactional(readOnly = true)
    public TaskWorkflowHistoryDto getWorkflowHistory(long taskId, long actorUserId) {
        TaskEntity task = taskRepository.findActiveById(taskId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found"));
        return buildWorkflowHistoryDto(task, actorUserId, false);
    }

    private TaskWorkflowHistoryDto buildWorkflowHistoryDto(TaskEntity task, long actorUserId, boolean requireManager) {
        long taskId = task.getId();
        long projectId = task.getProjectId();
        List<ProjectMemberEntity> members = projectMemberRepository.findMembers(projectId);
        ProjectMemberEntity actorMember = members.stream()
                .filter(m -> Objects.equals(m.getUserId(), actorUserId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN.value(),
                        "You are not a member of this project"));
        if (requireManager && actorMember.getRole() != MemberRole.MANAGER) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Only an active Project Manager can inspect deleted task audit history");
        }

        Set<Long> managerIds = members.stream()
                .filter(m -> m.getRole() == MemberRole.MANAGER)
                .map(ProjectMemberEntity::getUserId)
                .collect(Collectors.toSet());
        Map<Long, AccountAuthStateDto> managerAccounts = managerIds.isEmpty() || accountAuthStatePort == null
                ? Map.of()
                : accountAuthStatePort.findByIds(managerIds);

        boolean otherActiveManagersExist = members.stream()
                .filter(m -> m.getRole() == MemberRole.MANAGER && !Objects.equals(m.getUserId(), actorUserId))
                .anyMatch(m -> isAccountEffectiveActive(m.getUserId(), managerAccounts));

        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));
        boolean projectActive = project.getStatus() != ProjectStatus.ARCHIVED && !task.isDeleted();
        boolean sprintWritable = isTaskSprintWritable(task);
        boolean isManager = actorMember.getRole() == MemberRole.MANAGER;
        boolean isAssignee = Objects.equals(task.getAssigneeId(), actorUserId);
        boolean legacyMissingSub = task.getStatus() == TaskStatus.REVIEW && task.getCurrentSubmissionId() == null;

        boolean canSubmit = projectActive && sprintWritable && task.getStatus() == TaskStatus.IN_PROGRESS && isAssignee;
        boolean canWithdraw = projectActive && sprintWritable && task.getStatus() == TaskStatus.REVIEW
                && task.getCurrentSubmissionId() != null && isAssignee;
        boolean canReview = projectActive && sprintWritable && task.getStatus() == TaskStatus.REVIEW
                && task.getCurrentSubmissionId() != null && isManager && !isAssignee;
        boolean canSelfAttest = projectActive && sprintWritable && task.getStatus() == TaskStatus.REVIEW
                && task.getCurrentSubmissionId() != null && isManager && isAssignee && !otherActiveManagersExist;
        boolean canReopen = projectActive && sprintWritable && task.getStatus() == TaskStatus.DONE && isManager;
        boolean canSupersede = projectActive && task.getStatus() == TaskStatus.DONE
                && task.getCurrentSubmissionId() != null && task.getCurrentEvaluationId() != null
                && isManager && !isAssignee;
        boolean canRecoverLegacyReview = projectActive && sprintWritable && legacyMissingSub
                && (isManager || isAssignee);

        List<TaskSubmissionEntity> submissions = submissionRepository.findByTaskIdOrderByCycleNoDesc(taskId);
        List<TaskReviewEntity> reviews = reviewRepository.findByTaskIdOrderByReviewedAtDescIdDesc(taskId);
        List<TaskEvaluationEntity> evaluations = evaluationRepository.findByTaskIdOrderByEvaluationVersionDescIdDesc(taskId);
        List<TaskEvaluationInvalidationEntity> invalidations = invalidationRepository.findByTaskIdOrderByOccurredAtDescIdDesc(taskId);
        List<TaskDeadlineRevisionEntity> deadlineRevisions = deadlineRevisionRepository.findByTaskIdOrderByRevisionNoDesc(taskId);

        List<Long> subIds = submissions.stream().map(TaskSubmissionEntity::getId).toList();
        List<TaskSubmissionEvidenceFileEntity> evidenceFiles = subIds.isEmpty()
                ? List.of()
                : evidenceFileRepository.findBySubmissionIdInOrderByIdAsc(subIds);
        List<Long> evidenceRefIds = evidenceFiles.stream().map(TaskSubmissionEvidenceFileEntity::getId).toList();
        List<TaskEvidenceAvailabilityEventEntity> availEvents = evidenceRefIds.isEmpty()
                ? List.of()
                : availabilityEventRepository.findByEvidenceRefIdInOrderByOccurredAtDescIdDesc(evidenceRefIds);

        Set<Long> userIdsToResolve = new LinkedHashSet<>();
        submissions.forEach(s -> userIdsToResolve.add(s.getSubmitterId()));
        reviews.forEach(r -> userIdsToResolve.add(r.getReviewerId()));
        evaluations.forEach(e -> {
            userIdsToResolve.add(e.getAssigneeId());
            userIdsToResolve.add(e.getReviewerId());
        });
        Map<Long, String> userNames = resolveUserNames(userIdsToResolve);

        Map<Long, List<TaskWorkflowHistoryDto.EvidenceAvailabilityEventItem>> availByRef = availEvents.stream()
                .collect(Collectors.groupingBy(
                        TaskEvidenceAvailabilityEventEntity::getEvidenceRefId,
                        Collectors.mapping(ev -> new TaskWorkflowHistoryDto.EvidenceAvailabilityEventItem(
                                ev.getId(), ev.getEvidenceRefId(), ev.getOriginalFileId(),
                                Boolean.TRUE.equals(ev.getAvailable()), ev.getReason(),
                                ev.getOccurredAt(), ev.getActorUserId()), Collectors.toList())));

        Map<Long, List<TaskWorkflowHistoryDto.EvidenceFileItem>> filesBySub = evidenceFiles.stream()
                .collect(Collectors.groupingBy(
                        TaskSubmissionEvidenceFileEntity::getSubmissionId,
                        Collectors.mapping(f -> {
                            List<TaskWorkflowHistoryDto.EvidenceAvailabilityEventItem> events =
                                    availByRef.getOrDefault(f.getId(), List.of());
                            boolean avail = Boolean.TRUE.equals(f.getAvailable()) && f.getLiveSourceFileId() != null;
                            String unavailReason = avail ? null
                                    : (events.isEmpty() ? "FILE_UNAVAILABLE" : events.getFirst().reason());
                            return new TaskWorkflowHistoryDto.EvidenceFileItem(
                                    f.getId(),
                                    f.getLiveSourceFileId(),
                                    f.getOriginalFileIdSnapshot(),
                                    f.getOriginalName(),
                                    f.getContentType(),
                                    f.getFileSize(),
                                    f.getContentIdentity(),
                                    f.getCapturedAt(),
                                    avail,
                                    unavailReason,
                                    events);
                        }, Collectors.toList())));

        Map<Long, TaskEvaluationInvalidationEntity> invByEval = invalidations.stream()
                .collect(Collectors.toMap(TaskEvaluationInvalidationEntity::getEvaluationId, i -> i, (a, b) -> a));

        List<TaskWorkflowHistoryDto.SubmissionHistoryItem> subDtos = submissions.stream()
                .map(s -> new TaskWorkflowHistoryDto.SubmissionHistoryItem(
                        s.getId(),
                        s.getCycleNo(),
                        s.getAssignmentVersion(),
                        s.getSubmitterId(),
                        userNames.getOrDefault(s.getSubmitterId(), "User #" + s.getSubmitterId()),
                        s.getSummary(),
                        s.getSubmittedAt(),
                        s.getCommitmentDueAt(),
                        s.getDueCommitmentRevision(),
                        s.getCriteriaRevision(),
                        s.getProjectTimezoneId(),
                        s.getStatus(),
                        s.getWithdrawnAt(),
                        s.getWithdrawalReason(),
                        s.getWithdrawnByUserId(),
                        s.getInvalidatedAt(),
                        s.getInvalidationReason(),
                        filesBySub.getOrDefault(s.getId(), List.of())))
                .toList();

        List<TaskWorkflowHistoryDto.ReviewHistoryItem> reviewDtos = reviews.stream()
                .map(r -> new TaskWorkflowHistoryDto.ReviewHistoryItem(
                        r.getId(),
                        r.getSubmissionId(),
                        r.getReviewerId(),
                        userNames.getOrDefault(r.getReviewerId(), "User #" + r.getReviewerId()),
                        r.getDecision(),
                        r.getQualityRating(),
                        r.getRubricVersion(),
                        r.getFeedback(),
                        r.getEvidenceSummary(),
                        Boolean.TRUE.equals(r.getSelfAttested()),
                        r.getSupersedesReviewId(),
                        r.getReviewedAt()))
                .toList();

        List<TaskWorkflowHistoryDto.EvaluationHistoryItem> evalDtos = evaluations.stream()
                .map(e -> {
                    TaskEvaluationInvalidationEntity inv = invByEval.get(e.getId());
                    return new TaskWorkflowHistoryDto.EvaluationHistoryItem(
                            e.getId(),
                            e.getSubmissionId(),
                            e.getReviewId(),
                            e.getAssigneeId(),
                            userNames.getOrDefault(e.getAssigneeId(), "User #" + e.getAssigneeId()),
                            e.getReviewerId(),
                            userNames.getOrDefault(e.getReviewerId(), "User #" + e.getReviewerId()),
                            e.getAssignmentVersion(),
                            e.getAssignmentDecisionId(),
                            e.getQualityRating(),
                            e.getEvidenceStatus(),
                            e.getRubricVersion(),
                            e.getAcceptedSubmittedAt(),
                            e.getReviewedAt(),
                            e.getOriginalCompletedAt(),
                            e.getCommitmentDueAt(),
                            e.getOnTime(),
                            e.getTimelinessStatus(),
                            e.getEvaluationVersion(),
                            e.getSupersedesEvaluationId(),
                            Boolean.TRUE.equals(e.getInvalidated()),
                            inv != null ? inv.getReason() : null,
                            inv != null ? inv.getDetailReason() : null,
                            inv != null ? inv.getOccurredAt() : null,
                            inv != null ? inv.getActorUserId() : null,
                            e.getEvidenceRevision());
                })
                .toList();

        List<TaskWorkflowHistoryDto.DeadlineRevisionItem> revDtos = deadlineRevisions.stream()
                .map(d -> new TaskWorkflowHistoryDto.DeadlineRevisionItem(
                        d.getId(), d.getRevisionNo(), d.getPreviousDueAt(), d.getNewDueAt(),
                        d.getChangedByUserId(), d.getReason(), d.getChangedAt()))
                .toList();

        return new TaskWorkflowHistoryDto(
                task.getId(),
                projectId,
                task.effectiveVersion(),
                task.effectiveAssignmentVersion(),
                task.effectiveCriteriaRevision(),
                task.effectiveDueCommitmentRevision(),
                task.getStatus(),
                task.getCompletionEvidenceStatus(),
                task.getCompletedAt(),
                task.getCurrentSubmissionId(),
                task.getCurrentEvaluationId(),
                legacyMissingSub,
                otherActiveManagersExist,
                canSubmit,
                canWithdraw,
                canReview,
                canSelfAttest,
                canReopen,
                canSupersede,
                canRecoverLegacyReview,
                TaskQualityRubric.RUBRIC_VERSION,
                TaskQualityRubric.QUALITY_V1_ANCHORS,
                subDtos,
                reviewDtos,
                evalDtos,
                revDtos);
    }

    @Override
    @Transactional
    public TaskWorkflowResult submit(SubmitTaskCommand command, CurrentActor actor) {
        if (command == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Submit command is required");
        }
        long actorUserId = requireActor(actor);
        requireExpectedTaskVersion(command.expectedTaskVersion());
        requireRequestId(command.requestId());

        String summary = requireNonBlank(command.summary(), "Submission summary");
        List<Long> dedupedFileIds = deduplicateFileIds(command.evidenceProjectFileIds());
        String payloadHash = computeSubmitHash(command, dedupedFileIds);

        LockedContext ctx = lockWorkflowContext(command.taskId(), actorUserId, true);
        Optional<TaskWorkflowResult> replay = checkIdempotencyReplay(actorUserId, OP_SUBMIT, command.requestId(), payloadHash, command.taskId());
        if (replay.isPresent()) {
            return replay.get();
        }

        TaskEntity task = ctx.task();
        long projectId = ctx.project().getId();
        checkExpectedVersion(command.expectedTaskVersion(), task);
        assertTaskSprintWritable(task);

        if (task.getStatus() != TaskStatus.IN_PROGRESS) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Task must be IN_PROGRESS before submitting for review (current status: " + task.getStatus() + ")");
        }
        if (task.getAssigneeId() == null) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Unassigned tasks cannot be submitted");
        }
        if (!Objects.equals(task.getAssigneeId(), actorUserId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Only the current active assignee can submit this task for review");
        }

        List<ProjectFileEntity> resolvedFiles = resolveSameProjectEvidenceFiles(projectId, dedupedFileIds);
        Instant txNow = txNow();
        int nextCycleNo = submissionRepository.findMaxCycleNoByTaskId(task.getId()) + 1;
        String criteriaSnapshotJson = buildCriteriaSnapshotJson(task, ctx.project().getTimezoneId());

        TaskSubmissionEntity submission = submissionRepository.saveAndFlush(TaskSubmissionEntity.builder()
                .taskId(task.getId())
                .projectId(projectId)
                .cycleNo(nextCycleNo)
                .assignmentVersion(task.effectiveAssignmentVersion())
                .submitterId(actorUserId)
                .summary(summary)
                .submittedAt(txNow)
                .commitmentDueAt(task.getDueDate())
                .dueCommitmentRevision(task.effectiveDueCommitmentRevision())
                .criteriaRevision(task.effectiveCriteriaRevision())
                .projectTimezoneId(ctx.project().getTimezoneId())
                .criteriaSnapshotJson(criteriaSnapshotJson)
                .status("PENDING")
                .createdAt(txNow)
                .build());

        for (ProjectFileEntity file : resolvedFiles) {
            evidenceFileRepository.saveAndFlush(TaskSubmissionEvidenceFileEntity.builder()
                    .submissionId(submission.getId())
                    .projectId(projectId)
                    .liveSourceFileId(file.getId())
                    .originalFileIdSnapshot(file.getId())
                    .originalName(file.getOriginalName())
                    .contentType(file.getContentType())
                    .fileSize(file.getFileSize())
                    .storageKey(file.getStorageKey())
                    .storageBucket(file.getStorageBucket())
                    .contentIdentity(computeContentIdentity(file))
                    .capturedAt(txNow)
                    .available(Boolean.TRUE)
                    .build());
        }

        task.setStatus(TaskStatus.REVIEW);
        task.setCurrentSubmissionId(submission.getId());
        task.markMutated(txNow);
        TaskEntity savedTask = taskRepository.saveAndFlush(task);

        notifyActiveManagers(ctx, actorUserId,
                "Task Submitted for Review",
                "Task \"" + savedTask.getTitle() + "\" was submitted for review.",
                savedTask.getId());

        TaskWorkflowResult result = new TaskWorkflowResult(
                savedTask.getId(),
                savedTask.effectiveVersion(),
                TaskWorkflowState.REVIEW,
                submission.getId(),
                null,
                savedTask.getCurrentEvaluationId(),
                null,
                savedTask.getCompletionEvidenceStatus(),
                null,
                submission.getSubmittedAt(),
                null,
                savedTask.getCompletedAt(),
                null);

        recordIdempotency(actorUserId, OP_SUBMIT, command.requestId(), projectId, savedTask.getId(), payloadHash, result);
        return result;
    }

    @Override
    @Transactional
    public TaskWorkflowResult withdrawSubmission(WithdrawTaskCommand command, CurrentActor actor) {
        if (command == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Withdraw command is required");
        }
        long actorUserId = requireActor(actor);
        requireExpectedTaskVersion(command.expectedTaskVersion());
        requireRequestId(command.requestId());
        if (command.submissionId() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "submissionId is required");
        }
        String payloadHash = computeWithdrawHash(command);

        LockedContext ctx = lockWorkflowContext(command.taskId(), actorUserId, false);
        Optional<TaskWorkflowResult> replay = checkIdempotencyReplay(actorUserId, OP_WITHDRAW, command.requestId(), payloadHash, command.taskId());
        if (replay.isPresent()) {
            return replay.get();
        }

        TaskEntity task = ctx.task();
        long projectId = ctx.project().getId();
        checkExpectedVersion(command.expectedTaskVersion(), task);
        assertTaskSprintWritable(task);

        if (task.getStatus() != TaskStatus.REVIEW) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Task is not in REVIEW (current status: " + task.getStatus() + ")");
        }
        if (task.getCurrentSubmissionId() == null) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "LEGACY_REVIEW_MISSING_SUBMISSION: Legacy REVIEW task has no recorded submission; use explicit legacy recovery");
        }
        if (!Objects.equals(task.getAssigneeId(), actorUserId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Only the current active assignee can withdraw this submission");
        }
        if (!Objects.equals(task.getCurrentSubmissionId(), command.submissionId())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Pending submission mismatch; refresh and retry");
        }

        TaskSubmissionEntity submission = submissionRepository.findByIdForUpdate(command.submissionId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Submission not found"));
        if (!"PENDING".equals(submission.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Submission is no longer pending (status: " + submission.getStatus() + ")");
        }

        Instant txNow = txNow();
        String trimmedReason = command.reason() != null && !command.reason().isBlank() ? command.reason().trim() : null;
        submission.setStatus("WITHDRAWN");
        submission.setWithdrawnAt(txNow);
        submission.setWithdrawalReason(trimmedReason);
        submission.setWithdrawnByUserId(actorUserId);
        submissionRepository.saveAndFlush(submission);

        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setCurrentSubmissionId(null);
        task.markMutated(txNow);
        TaskEntity savedTask = taskRepository.saveAndFlush(task);

        TaskWorkflowResult result = new TaskWorkflowResult(
                savedTask.getId(),
                savedTask.effectiveVersion(),
                TaskWorkflowState.IN_PROGRESS,
                submission.getId(),
                null,
                savedTask.getCurrentEvaluationId(),
                null,
                savedTask.getCompletionEvidenceStatus(),
                null,
                submission.getSubmittedAt(),
                null,
                savedTask.getCompletedAt(),
                null);

        recordIdempotency(actorUserId, OP_WITHDRAW, command.requestId(), projectId, savedTask.getId(), payloadHash, result);
        return result;
    }

    @Override
    @Transactional
    public TaskWorkflowResult review(ReviewTaskCommand command, CurrentActor actor) {
        if (command == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Review command is required");
        }
        long actorUserId = requireActor(actor);
        requireExpectedTaskVersion(command.expectedTaskVersion());
        requireRequestId(command.requestId());
        if (command.submissionId() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "submissionId is required");
        }
        if (command.decision() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Review decision is required");
        }
        String payloadHash = computeReviewHash(command);

        LockedContext ctx = lockWorkflowContext(command.taskId(), actorUserId, true);
        Optional<TaskWorkflowResult> replay = checkIdempotencyReplay(actorUserId, OP_REVIEW, command.requestId(), payloadHash, command.taskId());
        if (replay.isPresent()) {
            return replay.get();
        }

        if (ctx.actorMember().getRole() != MemberRole.MANAGER) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Only an active project manager can review submissions");
        }

        TaskEntity task = ctx.task();
        long projectId = ctx.project().getId();
        checkExpectedVersion(command.expectedTaskVersion(), task);
        assertTaskSprintWritable(task);

        if (task.getStatus() != TaskStatus.REVIEW) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Task is not in REVIEW (current status: " + task.getStatus() + ")");
        }
        if (task.getCurrentSubmissionId() == null) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "LEGACY_REVIEW_MISSING_SUBMISSION: Legacy REVIEW task has no recorded submission; use explicit legacy recovery");
        }
        if (!Objects.equals(task.getCurrentSubmissionId(), command.submissionId())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Submission is no longer current; refresh and retry");
        }

        TaskSubmissionEntity submission = submissionRepository.findByIdForUpdate(command.submissionId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Submission not found"));
        if (!Objects.equals(submission.getTaskId(), task.getId())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Submission does not belong to this task");
        }
        if (!"PENDING".equals(submission.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Submission already finalized with status " + submission.getStatus());
        }

        Instant txNow = txNow();
        validatePendingSubmissionAssigneeEligibility(ctx, task, submission);

        boolean isOwnTask = Objects.equals(actorUserId, submission.getSubmitterId())
                || Objects.equals(actorUserId, task.getAssigneeId());

        if (command.decision() == ReviewDecision.RETURN) {
            if (isOwnTask) {
                throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                        "You cannot independently return your own task submission; use withdraw instead");
            }
            if (command.qualityRating() != null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "Returned submissions cannot receive a numeric rating");
            }
            String feedback = requireNonBlank(command.feedback(), "Actionable return feedback");
            String evidenceSummary = trimToNull(command.evidenceSummary());

            TaskReviewEntity review = reviewRepository.saveAndFlush(TaskReviewEntity.builder()
                    .taskId(task.getId())
                    .projectId(projectId)
                    .submissionId(submission.getId())
                    .reviewerId(actorUserId)
                    .decision("RETURN")
                    .qualityRating(null)
                    .rubricVersion(null)
                    .feedback(feedback)
                    .evidenceSummary(evidenceSummary)
                    .selfAttested(Boolean.FALSE)
                    .reviewedAt(txNow)
                    .build());

            submission.setStatus("RETURNED");
            submissionRepository.saveAndFlush(submission);

            task.setStatus(TaskStatus.IN_PROGRESS);
            task.setCurrentSubmissionId(null);
            task.markMutated(txNow);
            TaskEntity savedTask = taskRepository.saveAndFlush(task);

            notifyUserIfNotActor(ctx, submission.getSubmitterId(), actorUserId,
                    "Task Returned for Revision",
                    "Task \"" + savedTask.getTitle() + "\" was returned: " + feedback,
                    savedTask.getId());

            TaskWorkflowResult result = new TaskWorkflowResult(
                    savedTask.getId(),
                    savedTask.effectiveVersion(),
                    TaskWorkflowState.IN_PROGRESS,
                    submission.getId(),
                    review.getId(),
                    savedTask.getCurrentEvaluationId(),
                    null,
                    savedTask.getCompletionEvidenceStatus(),
                    null,
                    submission.getSubmittedAt(),
                    review.getReviewedAt(),
                    savedTask.getCompletedAt(),
                    null);
            recordIdempotency(actorUserId, OP_REVIEW, command.requestId(), projectId, savedTask.getId(), payloadHash, result);
            return result;
        }

        if (command.decision() == ReviewDecision.ACCEPT) {
            if (isOwnTask) {
                throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                        "You cannot independently accept or numerically rate your own task; use SELF_ATTEST only when no other active manager exists");
            }
            int rating = validateQualityRating(command.qualityRating());
            String feedback = requireNonBlank(command.feedback(), "Acceptance rationale");
            String evidenceSummary = requireNonBlank(command.evidenceSummary(), "Anchor/evidence summary");

            return finalizeAcceptance(
                    ctx, task, submission, actorUserId, "ACCEPT",
                    EvidenceStatus.INDEPENDENT_ACCEPTED, rating, feedback, evidenceSummary,
                    false, txNow, OP_REVIEW, command.requestId(), payloadHash);
        }

        // SELF_ATTEST
        if (!isOwnTask) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "SELF_ATTEST is only permitted for your own assigned task");
        }
        if (command.qualityRating() != null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "SELF_ATTEST forbids numeric quality ratings");
        }
        if (hasOtherActiveManager(ctx, actorUserId)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Another active project manager is available; independent review is required instead of self-attestation");
        }
        String reason = requireNonBlank(command.feedback(), "Self-attestation reason");
        String evidenceSummary = trimToNull(command.evidenceSummary());

        return finalizeAcceptance(
                ctx, task, submission, actorUserId, "SELF_ATTEST",
                EvidenceStatus.SELF_ATTESTED, null, reason, evidenceSummary,
                true, txNow, OP_REVIEW, command.requestId(), payloadHash);
    }

    @Override
    @Transactional
    public TaskWorkflowResult supersedeEvaluation(SupersedeEvaluationCommand command, CurrentActor actor) {
        if (command == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Supersede command is required");
        }
        long actorUserId = requireActor(actor);
        requireExpectedTaskVersion(command.expectedTaskVersion());
        requireRequestId(command.requestId());
        if (command.submissionId() == null || command.expectedEvaluationId() == null || command.expectedEvaluationVersion() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "submissionId, expectedEvaluationId, and expectedEvaluationVersion are required");
        }
        int rating = validateQualityRating(command.qualityRating());
        String reason = requireNonBlank(command.reason(), "Supersession reason");
        String evidenceSummary = requireNonBlank(command.evidenceSummary(), "Anchor/evidence summary");
        String payloadHash = computeSupersedeHash(command);

        LockedContext ctx = lockWorkflowContext(command.taskId(), actorUserId, true);
        Optional<TaskWorkflowResult> replay = checkIdempotencyReplay(actorUserId, OP_SUPERSEDE, command.requestId(), payloadHash, command.taskId());
        if (replay.isPresent()) {
            return replay.get();
        }

        if (ctx.actorMember().getRole() != MemberRole.MANAGER) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Only an active project manager can supersede an evaluation");
        }

        TaskEntity task = ctx.task();
        long projectId = ctx.project().getId();
        checkExpectedVersion(command.expectedTaskVersion(), task);
        // Note: supersedeEvaluation is permitted on a COMPLETED sprint as an explicit audited
        // quality evidence correction that leaves sprint closure accounting unchanged.

        if (task.getStatus() != TaskStatus.DONE) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Supersede requires task status DONE (current status: " + task.getStatus() + ")");
        }
        if (task.getCurrentSubmissionId() == null || task.getCurrentEvaluationId() == null) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Legacy DONE tasks have no recorded submission/evaluation to supersede; reopen instead");
        }
        if (!Objects.equals(task.getCurrentSubmissionId(), command.submissionId())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Submission ID does not match task's current accepted submission");
        }
        if (!Objects.equals(task.getCurrentEvaluationId(), command.expectedEvaluationId())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Expected evaluation ID is stale; refresh and retry");
        }

        TaskSubmissionEntity submission = submissionRepository.findByIdForUpdate(command.submissionId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Submission not found"));
        TaskEvaluationEntity currentEval = evaluationRepository.findByIdForUpdate(command.expectedEvaluationId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Evaluation not found"));

        if (!Objects.equals(currentEval.getEvaluationVersion().longValue(), command.expectedEvaluationVersion())
                || Boolean.TRUE.equals(currentEval.getInvalidated())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Evaluation version is stale or already invalidated; refresh and retry");
        }

        if (Objects.equals(actorUserId, submission.getSubmitterId())
                || Objects.equals(actorUserId, currentEval.getAssigneeId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "You cannot numerically rate or supersede an evaluation for your own task");
        }

        Instant txNow = txNow();
        String invalidationCode = currentEval.getEvidenceStatus() == EvidenceStatus.SELF_ATTESTED
                ? "SUPERSEDED_BY_INDEPENDENT_REVIEW"
                : "RATING_CORRECTED";

        long baseEvidenceRev = Math.max(evaluationRepository.findMaxEvidenceRevisionByTaskId(task.getId()),
                currentEval.getEvidenceRevision());
        long invEvidenceRev = baseEvidenceRev + 1L;
        long nextEvidenceRev = baseEvidenceRev + 2L;

        currentEval.setInvalidated(Boolean.TRUE);
        evaluationRepository.saveAndFlush(currentEval);

        invalidationRepository.saveAndFlush(TaskEvaluationInvalidationEntity.builder()
                .taskId(task.getId())
                .projectId(projectId)
                .evaluationId(currentEval.getId())
                .evaluationVersion(currentEval.getEvaluationVersion())
                .reason(invalidationCode)
                .detailReason(reason)
                .actorUserId(actorUserId)
                .occurredAt(txNow)
                .evidenceRevision(invEvidenceRev)
                .build());

        TaskReviewEntity newReview = reviewRepository.saveAndFlush(TaskReviewEntity.builder()
                .taskId(task.getId())
                .projectId(projectId)
                .submissionId(submission.getId())
                .reviewerId(actorUserId)
                .decision("SUPERSEDE_ACCEPT")
                .qualityRating(rating)
                .rubricVersion(TaskQualityRubric.RUBRIC_VERSION)
                .feedback(reason)
                .evidenceSummary(evidenceSummary)
                .selfAttested(Boolean.FALSE)
                .supersedesReviewId(currentEval.getReviewId())
                .reviewedAt(txNow)
                .build());

        int nextEvalVersion = evaluationRepository.findMaxVersionBySubmissionId(submission.getId()) + 1;
        Instant originalCompletedAt = currentEval.getOriginalCompletedAt();

        TaskEvaluationEntity newEval = evaluationRepository.saveAndFlush(TaskEvaluationEntity.builder()
                .taskId(task.getId())
                .projectId(projectId)
                .submissionId(submission.getId())
                .reviewId(newReview.getId())
                .assigneeId(currentEval.getAssigneeId())
                .reviewerId(actorUserId)
                .assignmentVersion(currentEval.getAssignmentVersion())
                .assignmentDecisionId(currentEval.getAssignmentDecisionId())
                .qualityRating(rating)
                .evidenceStatus(EvidenceStatus.INDEPENDENT_ACCEPTED)
                .rubricVersion(TaskQualityRubric.RUBRIC_VERSION)
                .acceptedSubmittedAt(currentEval.getAcceptedSubmittedAt())
                .reviewedAt(txNow)
                .originalCompletedAt(originalCompletedAt)
                .commitmentDueAt(currentEval.getCommitmentDueAt())
                .onTime(currentEval.getOnTime())
                .timelinessStatus(currentEval.getTimelinessStatus())
                .evaluationVersion(nextEvalVersion)
                .supersedesEvaluationId(currentEval.getId())
                .invalidated(Boolean.FALSE)
                .evidenceRevision(nextEvidenceRev)
                .createdAt(txNow)
                .build());

        task.setCurrentEvaluationId(newEval.getId());
        task.setCompletionEvidenceStatus(EvidenceStatus.INDEPENDENT_ACCEPTED);
        // Preserve original completedAt when superseding grade/self-attestation on same accepted submission
        task.setCompletedAt(originalCompletedAt);
        task.markMutated(txNow);
        TaskEntity savedTask = taskRepository.saveAndFlush(task);

        Long invJournalId = persistInvalidatedJournal(
                task.getId(), projectId, currentEval, invalidationCode, txNow, invEvidenceRev, savedTask.effectiveVersion());
        Long accJournalId = persistAcceptedJournal(
                savedTask, newEval, txNow);
        journalDispatcher.scheduleAfterCommit(List.of(invJournalId, accJournalId));

        if (sprintClosureCorrectionRepository != null && savedTask.getSprintId() != null) {
            SprintEntity sprint = sprintRepository.findById(savedTask.getSprintId()).orElse(null);
            if (sprint != null && sprint.getStatus() == SprintStatus.COMPLETED) {
                sprintClosureCorrectionRepository.save(SprintClosureCorrectionEntity.builder()
                        .projectId(projectId)
                        .sprintId(sprint.getId())
                        .taskId(savedTask.getId())
                        .correctionType("AUDITED_CORRECTION")
                        .reason(reason)
                        .detailJson("{\"supersededEvaluationId\":" + currentEval.getId()
                                + ",\"newEvaluationId\":" + newEval.getId()
                                + ",\"qualityRating\":" + rating + "}")
                        .actorUserId(actorUserId)
                        .occurredAt(txNow)
                        .build());
            }
        }

        TaskWorkflowResult result = new TaskWorkflowResult(
                savedTask.getId(),
                savedTask.effectiveVersion(),
                TaskWorkflowState.DONE,
                submission.getId(),
                newReview.getId(),
                newEval.getId(),
                newEval.getEvaluationVersion(),
                newEval.getEvidenceStatus(),
                newEval.getTimelinessStatus(),
                newEval.getAcceptedSubmittedAt(),
                newEval.getReviewedAt(),
                savedTask.getCompletedAt(),
                newEval.getOnTime());

        recordIdempotency(actorUserId, OP_SUPERSEDE, command.requestId(), projectId, savedTask.getId(), payloadHash, result);
        return result;
    }

    @Override
    @Transactional
    public TaskWorkflowResult reopen(ReopenTaskCommand command, CurrentActor actor) {
        if (command == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Reopen command is required");
        }
        long actorUserId = requireActor(actor);
        requireExpectedTaskVersion(command.expectedTaskVersion());
        requireRequestId(command.requestId());
        String reason = requireNonBlank(command.reason(), "Reopen reason");
        String payloadHash = computeReopenHash(command);

        LockedContext ctx = lockWorkflowContext(command.taskId(), actorUserId, true);
        Optional<TaskWorkflowResult> replay = checkIdempotencyReplay(actorUserId, OP_REOPEN, command.requestId(), payloadHash, command.taskId());
        if (replay.isPresent()) {
            return replay.get();
        }

        if (ctx.actorMember().getRole() != MemberRole.MANAGER) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Only an active project manager can reopen a completed task");
        }

        TaskEntity task = ctx.task();
        long projectId = ctx.project().getId();
        checkExpectedVersion(command.expectedTaskVersion(), task);
        assertTaskSprintWritable(task);

        if (task.getStatus() != TaskStatus.DONE) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Task must be DONE to reopen (current status: " + task.getStatus() + ")");
        }

        Instant txNow = txNow();
        TaskEvaluationEntity invalidatedEval = null;
        long invalidationEvidenceRev = 0L;

        if (task.getCurrentEvaluationId() != null) {
            TaskEvaluationEntity currentEval = evaluationRepository.findByIdForUpdate(task.getCurrentEvaluationId())
                    .orElse(null);
            if (currentEval != null && !Boolean.TRUE.equals(currentEval.getInvalidated())) {
                invalidationEvidenceRev = Math.max(evaluationRepository.findMaxEvidenceRevisionByTaskId(task.getId()),
                        currentEval.getEvidenceRevision()) + 1L;
                currentEval.setInvalidated(Boolean.TRUE);
                evaluationRepository.saveAndFlush(currentEval);

                invalidationRepository.saveAndFlush(TaskEvaluationInvalidationEntity.builder()
                        .taskId(task.getId())
                        .projectId(projectId)
                        .evaluationId(currentEval.getId())
                        .evaluationVersion(currentEval.getEvaluationVersion())
                        .reason("REOPENED")
                        .detailReason(reason)
                        .actorUserId(actorUserId)
                        .occurredAt(txNow)
                        .evidenceRevision(invalidationEvidenceRev)
                        .build());
                invalidatedEval = currentEval;
            }
        }

        boolean assigneeCleared = false;
        Long previousAssigneeId = task.getAssigneeId();
        if (previousAssigneeId != null) {
            boolean stillMember = ctx.members().stream()
                    .anyMatch(m -> Objects.equals(m.getUserId(), previousAssigneeId));
            boolean activeAccount = isAccountEffectiveActive(previousAssigneeId, ctx.accounts());
            if (!stillMember || !activeAccount) {
                task.updateAssignee(null);
                assigneeCleared = true;
            }
        }

        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setCompletedAt(null);
        task.setCurrentSubmissionId(null);
        task.setCurrentEvaluationId(null);
        task.setCompletionEvidenceStatus(null);
        task.markMutated(txNow);
        TaskEntity savedTask = taskRepository.saveAndFlush(task);

        List<Long> journalIds = new ArrayList<>();
        if (invalidatedEval != null) {
            journalIds.add(persistInvalidatedJournal(
                    savedTask.getId(), projectId, invalidatedEval, "REOPENED",
                    txNow, invalidationEvidenceRev, savedTask.effectiveVersion()));
        }

        if (assigneeCleared) {
            notifyActiveManagers(ctx, null,
                    "Reopened Task Unassigned",
                    "Reopened task \"" + savedTask.getTitle() + "\" was unassigned because the previous assignee is no longer an active project member.",
                    savedTask.getId());
        } else if (savedTask.getAssigneeId() != null) {
            notifyUserIfNotActor(ctx, savedTask.getAssigneeId(), actorUserId,
                    "Task Reopened",
                    "Task \"" + savedTask.getTitle() + "\" was reopened: " + reason,
                    savedTask.getId());
        }

        journalDispatcher.scheduleAfterCommit(journalIds);

        TaskWorkflowResult result = new TaskWorkflowResult(
                savedTask.getId(),
                savedTask.effectiveVersion(),
                TaskWorkflowState.IN_PROGRESS,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        recordIdempotency(actorUserId, OP_REOPEN, command.requestId(), projectId, savedTask.getId(), payloadHash, result);
        return result;
    }

    @Override
    @Transactional
    public TaskWorkflowResult recoverLegacyReview(RecoverLegacyReviewCommand command, CurrentActor actor) {
        if (command == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Recover command is required");
        }
        long actorUserId = requireActor(actor);
        requireExpectedTaskVersion(command.expectedTaskVersion());
        requireRequestId(command.requestId());
        String reason = requireNonBlank(command.reason(), "Recovery reason");
        String payloadHash = computeRecoverHash(command);

        LockedContext ctx = lockWorkflowContext(command.taskId(), actorUserId, false);
        Optional<TaskWorkflowResult> replay = checkIdempotencyReplay(actorUserId, OP_RECOVER_LEGACY_REVIEW, command.requestId(), payloadHash, command.taskId());
        if (replay.isPresent()) {
            return replay.get();
        }

        TaskEntity task = ctx.task();
        long projectId = ctx.project().getId();
        checkExpectedVersion(command.expectedTaskVersion(), task);
        assertTaskSprintWritable(task);

        boolean isManager = ctx.actorMember().getRole() == MemberRole.MANAGER;
        boolean isAssignee = Objects.equals(task.getAssigneeId(), actorUserId);
        if (!isManager && !isAssignee) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Only an active manager or the current assignee can recover a legacy REVIEW task");
        }
        if (task.getStatus() != TaskStatus.REVIEW || task.getCurrentSubmissionId() != null) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Task is not a legacy REVIEW task missing a submission record");
        }

        Instant txNow = txNow();
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.markMutated(txNow);
        TaskEntity savedTask = taskRepository.saveAndFlush(task);

        journalRepository.saveAndFlush(TaskWorkflowJournalEntity.builder()
                .eventId(UUID.randomUUID())
                .eventType("LEGACY_REVIEW_RECOVERED")
                .aggregateVersion(savedTask.effectiveVersion())
                .evidenceRevision(0L)
                .projectId(projectId)
                .taskId(savedTask.getId())
                .payloadJson(toJson(Map.of(
                        "taskId", savedTask.getId(),
                        "actorUserId", actorUserId,
                        "reason", reason,
                        "occurredAt", txNow.toString())))
                .status("SUCCEEDED")
                .attempts(1)
                .nextAttemptAt(txNow)
                .createdAt(txNow)
                .processedAt(txNow)
                .build());

        TaskWorkflowResult result = new TaskWorkflowResult(
                savedTask.getId(),
                savedTask.effectiveVersion(),
                TaskWorkflowState.IN_PROGRESS,
                null,
                null,
                savedTask.getCurrentEvaluationId(),
                null,
                savedTask.getCompletionEvidenceStatus(),
                null,
                null,
                null,
                savedTask.getCompletedAt(),
                null);
        recordIdempotency(actorUserId, OP_RECOVER_LEGACY_REVIEW, command.requestId(), projectId, savedTask.getId(), payloadHash, result);
        return result;
    }

    /**
     * Invoked inside ProjectServiceImpl / ProjectAccountLifecycleAdapter under canonical locks
     * when clearing an orphaned REVIEW task whose assignee left, was removed, or was suspended.
     */
    public void invalidatePendingSubmissionOnAssigneeRemoval(TaskEntity task, String reason, Instant txNow) {
        if (task == null || task.getCurrentSubmissionId() == null) {
            return;
        }
        submissionRepository.findByIdForUpdate(task.getCurrentSubmissionId()).ifPresent(sub -> {
            if ("PENDING".equals(sub.getStatus())) {
                sub.setStatus("INVALIDATED");
                sub.setInvalidatedAt(txNow);
                sub.setInvalidationReason(reason);
                submissionRepository.saveAndFlush(sub);
            }
        });
        task.setCurrentSubmissionId(null);
    }

    /**
     * Invoked inside ProjectFileService.deleteFile transaction before deleting project_files row.
     * Preserves immutable snapshot metadata and appends availability-loss event without exposing public links.
     */
    public void recordEvidenceFilesUnavailable(long projectId, long deletedFileId, long actorUserId, Instant occurredAt) {
        List<TaskSubmissionEvidenceFileEntity> refs = evidenceFileRepository.findByLiveSourceFileId(deletedFileId);
        if (refs.isEmpty()) {
            return;
        }
        Instant effectiveTime = (occurredAt != null ? occurredAt : clock.instant()).truncatedTo(ChronoUnit.MICROS);
        for (TaskSubmissionEvidenceFileEntity ref : refs) {
            ref.setAvailable(Boolean.FALSE);
            ref.setLiveSourceFileId(null);
            evidenceFileRepository.saveAndFlush(ref);

            availabilityEventRepository.saveAndFlush(TaskEvidenceAvailabilityEventEntity.builder()
                    .evidenceRefId(ref.getId())
                    .projectId(projectId)
                    .originalFileId(deletedFileId)
                    .available(Boolean.FALSE)
                    .reason("FILE_DELETED")
                    .occurredAt(effectiveTime)
                    .actorUserId(actorUserId)
                    .build());
        }
    }

    private TaskWorkflowResult finalizeAcceptance(
            LockedContext ctx,
            TaskEntity task,
            TaskSubmissionEntity submission,
            long reviewerId,
            String reviewDecisionName,
            EvidenceStatus evidenceStatus,
            Integer qualityRating,
            String feedback,
            String evidenceSummary,
            boolean selfAttested,
            Instant txNow,
            String operation,
            UUID requestId,
            String payloadHash) {
        long projectId = ctx.project().getId();
        Instant dueAt = submission.getCommitmentDueAt();
        Boolean onTime = dueAt == null ? null : !submission.getSubmittedAt().isAfter(dueAt);
        TimelinessStatus timelinessStatus = dueAt == null
                ? TimelinessStatus.NO_DUE_DATE
                : (Boolean.TRUE.equals(onTime) ? TimelinessStatus.ON_TIME : TimelinessStatus.LATE);

        TaskReviewEntity review = reviewRepository.saveAndFlush(TaskReviewEntity.builder()
                .taskId(task.getId())
                .projectId(projectId)
                .submissionId(submission.getId())
                .reviewerId(reviewerId)
                .decision(reviewDecisionName)
                .qualityRating(qualityRating)
                .rubricVersion(TaskQualityRubric.RUBRIC_VERSION)
                .feedback(feedback)
                .evidenceSummary(evidenceSummary)
                .selfAttested(selfAttested)
                .reviewedAt(txNow)
                .build());

        int evalVersion = evaluationRepository.findMaxVersionBySubmissionId(submission.getId()) + 1;
        long nextEvidenceRevision = evaluationRepository.findMaxEvidenceRevisionByTaskId(task.getId()) + 1L;
        String eligibleDecisionId = resolveEligibleDecisionId(task, submission);

        TaskEvaluationEntity evaluation = evaluationRepository.saveAndFlush(TaskEvaluationEntity.builder()
                .taskId(task.getId())
                .projectId(projectId)
                .submissionId(submission.getId())
                .reviewId(review.getId())
                .assigneeId(submission.getSubmitterId())
                .reviewerId(reviewerId)
                .assignmentVersion(submission.getAssignmentVersion())
                .assignmentDecisionId(eligibleDecisionId)
                .qualityRating(qualityRating)
                .evidenceStatus(evidenceStatus)
                .rubricVersion(TaskQualityRubric.RUBRIC_VERSION)
                .acceptedSubmittedAt(submission.getSubmittedAt())
                .reviewedAt(txNow)
                .originalCompletedAt(txNow)
                .commitmentDueAt(dueAt)
                .onTime(onTime)
                .timelinessStatus(timelinessStatus)
                .evaluationVersion(evalVersion)
                .invalidated(Boolean.FALSE)
                .evidenceRevision(nextEvidenceRevision)
                .createdAt(txNow)
                .build());

        submission.setStatus("ACCEPTED");
        submissionRepository.saveAndFlush(submission);

        task.setStatus(TaskStatus.DONE);
        task.setCompletedAt(txNow);
        task.setCurrentSubmissionId(submission.getId());
        task.setCurrentEvaluationId(evaluation.getId());
        task.setCompletionEvidenceStatus(evidenceStatus);
        task.markMutated(txNow);
        TaskEntity savedTask = taskRepository.saveAndFlush(task);

        Long journalId = persistAcceptedJournal(savedTask, evaluation, txNow);
        journalDispatcher.scheduleAfterCommit(List.of(journalId));

        notifyUserIfNotActor(ctx, submission.getSubmitterId(), reviewerId,
                selfAttested ? "Task Self-Attested" : "Task Accepted",
                "Task \"" + savedTask.getTitle() + "\" was marked DONE (" + evidenceStatus.name() + ").",
                savedTask.getId());

        TaskWorkflowResult result = new TaskWorkflowResult(
                savedTask.getId(),
                savedTask.effectiveVersion(),
                TaskWorkflowState.DONE,
                submission.getId(),
                review.getId(),
                evaluation.getId(),
                evaluation.getEvaluationVersion(),
                evaluation.getEvidenceStatus(),
                evaluation.getTimelinessStatus(),
                evaluation.getAcceptedSubmittedAt(),
                evaluation.getReviewedAt(),
                savedTask.getCompletedAt(),
                evaluation.getOnTime());

        recordIdempotency(reviewerId, operation, requestId, projectId, savedTask.getId(), payloadHash, result);
        return result;
    }

    private Long persistAcceptedJournal(TaskEntity savedTask, TaskEvaluationEntity evaluation, Instant txNow) {
        UUID eventId = UUID.randomUUID();
        TaskEvaluationAccepted payload = new TaskEvaluationAccepted(
                eventId,
                savedTask.getId(),
                savedTask.getProjectId(),
                evaluation.getAssignmentVersion(),
                evaluation.getSubmissionId(),
                evaluation.getId(),
                evaluation.getEvaluationVersion().longValue(),
                evaluation.getAssigneeId(),
                evaluation.getReviewerId(),
                evaluation.getAcceptedSubmittedAt(),
                evaluation.getReviewedAt(),
                evaluation.getCommitmentDueAt(),
                evaluation.getQualityRating(),
                evaluation.getOnTime(),
                evaluation.getRubricVersion(),
                evaluation.getEvidenceStatus().name(),
                evaluation.getEvidenceRevision(),
                evaluation.getAssignmentDecisionId());
        TaskWorkflowJournalEntity entry = journalRepository.saveAndFlush(TaskWorkflowJournalEntity.builder()
                .eventId(eventId)
                .eventType(TaskWorkflowJournalDispatcher.EVENT_EVALUATION_ACCEPTED)
                .aggregateVersion(savedTask.effectiveVersion())
                .evidenceRevision(evaluation.getEvidenceRevision())
                .projectId(savedTask.getProjectId())
                .taskId(savedTask.getId())
                .submissionId(evaluation.getSubmissionId())
                .evaluationId(evaluation.getId())
                .payloadJson(toJson(payload))
                .status("PENDING")
                .attempts(0)
                .nextAttemptAt(txNow)
                .createdAt(txNow)
                .build());
        return entry.getId();
    }

    private Long persistInvalidatedJournal(
            long taskId,
            long projectId,
            TaskEvaluationEntity evaluation,
            String reason,
            Instant txNow,
            long evidenceRevision,
            long aggregateVersion) {
        UUID eventId = UUID.randomUUID();
        TaskEvaluationInvalidated payload = new TaskEvaluationInvalidated(
                eventId,
                taskId,
                projectId,
                evaluation.getId(),
                evaluation.getEvaluationVersion().longValue(),
                reason,
                txNow,
                evidenceRevision);
        TaskWorkflowJournalEntity entry = journalRepository.saveAndFlush(TaskWorkflowJournalEntity.builder()
                .eventId(eventId)
                .eventType(TaskWorkflowJournalDispatcher.EVENT_EVALUATION_INVALIDATED)
                .aggregateVersion(aggregateVersion)
                .evidenceRevision(evidenceRevision)
                .projectId(projectId)
                .taskId(taskId)
                .submissionId(evaluation.getSubmissionId())
                .evaluationId(evaluation.getId())
                .payloadJson(toJson(payload))
                .status("PENDING")
                .attempts(0)
                .nextAttemptAt(txNow)
                .createdAt(txNow)
                .build());
        return entry.getId();
    }

    @Transactional(readOnly = true)
    public TaskWorkflowHistoryDto getDeletedTaskWorkflowAudit(long projectId, long taskId, long actorUserId) {
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found"));
        if (!Objects.equals(task.getProjectId(), projectId) || !task.isDeleted()) {
            throw new BusinessException(HttpStatus.NOT_FOUND.value(), "Deleted task audit record not found");
        }
        return buildWorkflowHistoryDto(task, actorUserId, true);
    }

    private LockedContext lockWorkflowContext(long taskId, long actorUserId, boolean lockAllManagersAndAssignee) {
        TaskEntity preCheck = taskRepository.findActiveById(taskId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found"));
        long projectId = preCheck.getProjectId();

        // Canonical lock order: project -> accounts (asc) -> task
        ProjectEntity project = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));
        if (project.getStatus() == ProjectStatus.ARCHIVED) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Project is archived");
        }

        List<ProjectMemberEntity> members = projectMemberRepository.findMembers(projectId);
        Set<Long> userIdsToLock = new TreeSet<>();
        userIdsToLock.add(actorUserId);

        taskRepository.findAssigneeIdById(taskId).ifPresent(userIdsToLock::add);
        if (preCheck.getCurrentSubmissionId() != null) {
            submissionRepository.findById(preCheck.getCurrentSubmissionId())
                    .map(TaskSubmissionEntity::getSubmitterId)
                    .ifPresent(userIdsToLock::add);
        }
        if (lockAllManagersAndAssignee) {
            for (ProjectMemberEntity member : members) {
                if (member.getRole() == MemberRole.MANAGER) {
                    userIdsToLock.add(member.getUserId());
                }
            }
        }

        Map<Long, AccountAuthStateDto> accounts = accountAuthStatePort == null
                ? Map.of()
                : accountAuthStatePort.lockAndLoadByIds(userIdsToLock);

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

        ProjectMemberEntity actorMember = members.stream()
                .filter(m -> Objects.equals(m.getUserId(), actorUserId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN.value(),
                        "You are not a member of this project"));

        TaskEntity lockedTask = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found"));
        if (lockedTask.isDeleted()) {
            throw new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found");
        }
        if (!Objects.equals(lockedTask.getProjectId(), projectId)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Task project scope changed");
        }

        return new LockedContext(project, lockedTask, actorMember, members, accounts);
    }

    /**
     * Fences pending-submission review outcomes against a stale assignment or an assignee whose
     * security suspension/deactivation/unverification committed before asynchronous project
     * recovery invalidated the pending submission.
     */
    private void validatePendingSubmissionAssigneeEligibility(
            LockedContext ctx,
            TaskEntity task,
            TaskSubmissionEntity submission) {
        Long currentAssigneeId = task.getAssigneeId();
        if (currentAssigneeId == null) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Pending submission cannot be reviewed because task has no current active assignee");
        }
        if (!Objects.equals(submission.getSubmitterId(), currentAssigneeId)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Pending submission submitter no longer matches the current task assignee");
        }
        if (!Objects.equals(submission.getAssignmentVersion(), task.effectiveAssignmentVersion())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Task assignment changed after this submission was created; awaiting invalidation or resubmission");
        }
        boolean assigneeIsMember = ctx.members().stream()
                .anyMatch(m -> Objects.equals(m.getUserId(), currentAssigneeId));
        if (!assigneeIsMember) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Pending submission assignee is no longer an active member of this project; awaiting recovery or reassignment");
        }
        if (accountAuthStatePort != null) {
            AccountAuthStateDto assigneeAccount = ctx.accounts().get(currentAssigneeId);
            if (assigneeAccount == null || !ProjectAccountLifecycleAdapter.isEffectiveActive(assigneeAccount)) {
                String state = assigneeAccount != null ? assigneeAccount.lifecycle() : "MISSING";
                throw new BusinessException(HttpStatus.CONFLICT.value(),
                        "Pending submission assignee account is not active and verified (state=" + state
                                + "); awaiting lifecycle recovery");
            }
        }
    }

    private Optional<TaskWorkflowResult> checkIdempotencyReplay(
            long actorUserId,
            String operation,
            UUID requestId,
            String payloadHash,
            long expectedTaskId) {
        if (idempotencyRepository == null) {
            return Optional.empty();
        }
        Optional<TaskCommandIdempotencyEntity> existing = idempotencyRepository
                .findByActorUserIdAndOperationAndRequestId(actorUserId, operation, requestId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        TaskCommandIdempotencyEntity row = existing.get();
        if (!Objects.equals(row.getPayloadHash(), payloadHash)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Idempotency key already used with a different request payload");
        }
        if (row.getTaskId() != null && !Objects.equals(row.getTaskId(), expectedTaskId)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Idempotency key already used for a different task");
        }
        try {
            return Optional.of(CANONICAL_MAPPER.readValue(row.getResponseJson(), TaskWorkflowResult.class));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize stored workflow idempotency response", ex);
        }
    }

    private void recordIdempotency(
            long actorUserId,
            String operation,
            UUID requestId,
            long projectId,
            long taskId,
            String payloadHash,
            TaskWorkflowResult result) {
        if (idempotencyRepository == null) {
            return;
        }
        idempotencyRepository.saveAndFlush(TaskCommandIdempotencyEntity.builder()
                .actorUserId(actorUserId)
                .operation(operation)
                .requestId(requestId)
                .projectId(projectId)
                .taskId(taskId)
                .payloadHash(payloadHash)
                .httpStatus(HttpStatus.OK.value())
                .responseJson(toJson(result))
                .createdAt(txNow())
                .build());
    }

    private List<ProjectFileEntity> resolveSameProjectEvidenceFiles(long projectId, List<Long> fileIds) {
        if (fileIds.isEmpty()) {
            return List.of();
        }
        List<ProjectFileEntity> resolved = new ArrayList<>(fileIds.size());
        for (Long fileId : fileIds) {
            ProjectFileEntity file = projectFileRepository.findById(fileId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                            "Evidence file not found: " + fileId));
            if (!Objects.equals(file.getProjectId(), projectId)) {
                throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                        "Evidence file " + fileId + " does not belong to this project");
            }
            resolved.add(file);
        }
        return resolved;
    }

    private List<Long> deduplicateFileIds(List<Long> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<Long> deduped = new LinkedHashSet<>();
        for (Long id : raw) {
            if (id == null || id <= 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "evidenceProjectFileIds must contain valid positive file IDs");
            }
            deduped.add(id);
        }
        return List.copyOf(deduped);
    }

    private boolean hasOtherActiveManager(LockedContext ctx, long actorUserId) {
        return ctx.members().stream()
                .filter(m -> m.getRole() == MemberRole.MANAGER && !Objects.equals(m.getUserId(), actorUserId))
                .anyMatch(m -> isAccountEffectiveActive(m.getUserId(), ctx.accounts()));
    }

    private boolean isAccountEffectiveActive(long userId, Map<Long, AccountAuthStateDto> accounts) {
        if (accountAuthStatePort == null) {
            return true;
        }
        return ProjectAccountLifecycleAdapter.isEffectiveActive(accounts.get(userId));
    }

    private void assertTaskSprintWritable(TaskEntity task) {
        if (!isTaskSprintWritable(task)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Tasks in a completed sprint are read-only");
        }
    }

    private boolean isTaskSprintWritable(TaskEntity task) {
        if (task.getSprintId() == null) {
            return true;
        }
        SprintEntity sprint = sprintRepository.findById(task.getSprintId()).orElse(null);
        return sprint == null || sprint.getStatus() != SprintStatus.COMPLETED;
    }

    private String resolveEligibleDecisionId(TaskEntity task, TaskSubmissionEntity submission) {
        if (task.getAssignmentDecisionId() != null
                && !task.getAssignmentDecisionId().isBlank()
                && Objects.equals(task.effectiveAssignmentVersion(), submission.getAssignmentVersion())) {
            return task.getAssignmentDecisionId();
        }
        return null;
    }

    private String buildCriteriaSnapshotJson(TaskEntity task, String projectTimezoneId) {
        List<Long> skillIds = taskRequiredSkillRepository.findByTaskId(task.getId()).stream()
                .map(TaskRequiredSkillEntity::getSkillId)
                .sorted()
                .toList();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", task.getTitle());
        snapshot.put("description", task.getDescription());
        snapshot.put("priority", task.getPriority() != null ? task.getPriority().name() : null);
        snapshot.put("difficultyLevel", task.getDifficultyLevel());
        snapshot.put("startDate", task.getStartDate());
        snapshot.put("dueDate", task.getDueDate());
        snapshot.put("projectTimezoneId", projectTimezoneId);
        snapshot.put("criteriaRevision", task.effectiveCriteriaRevision());
        snapshot.put("dueCommitmentRevision", task.effectiveDueCommitmentRevision());
        snapshot.put("requiredSkillIds", skillIds);
        return toJson(snapshot);
    }

    private void notifyActiveManagers(LockedContext ctx, Long excludeUserId, String title, String content, long taskId) {
        if (notificationPort == null) {
            return;
        }
        for (ProjectMemberEntity member : ctx.members()) {
            if (member.getRole() == MemberRole.MANAGER
                    && !Objects.equals(member.getUserId(), excludeUserId)
                    && isAccountEffectiveActive(member.getUserId(), ctx.accounts())) {
                notificationPort.createNotification(new SystemNotificationCommandDto(
                        member.getUserId(),
                        title,
                        content,
                        "/projects/" + ctx.project().getId() + "/tasks/" + taskId,
                        NotificationTypeDto.ASSIGNED,
                        ctx.project().getId(),
                        taskId));
            }
        }
    }

    private void notifyUserIfNotActor(LockedContext ctx, Long targetUserId, long actorUserId, String title, String content, long taskId) {
        if (notificationPort == null || targetUserId == null || Objects.equals(targetUserId, actorUserId)) {
            return;
        }
        boolean isMember = ctx.members().stream().anyMatch(m -> Objects.equals(m.getUserId(), targetUserId));
        if (!isMember) {
            return;
        }
        if (accountAuthStatePort != null) {
            AccountAuthStateDto acc = ctx.accounts().get(targetUserId);
            if (acc == null) {
                acc = accountAuthStatePort.findById(targetUserId).orElse(null);
            }
            if (!ProjectAccountLifecycleAdapter.isEffectiveActive(acc)) {
                return;
            }
        }
        notificationPort.createNotification(new SystemNotificationCommandDto(
                targetUserId,
                title,
                content,
                "/projects/" + ctx.project().getId() + "/tasks/" + taskId,
                NotificationTypeDto.ASSIGNED,
                ctx.project().getId(),
                taskId));
    }

    private Map<Long, String> resolveUserNames(Set<Long> userIds) {
        if (userIds.isEmpty() || userPort == null) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (Long uid : userIds) {
            if (uid != null) {
                userPort.findById(uid).map(UserProfileDto::fullName).ifPresent(name -> names.put(uid, name));
            }
        }
        return names;
    }

    private long requireActor(CurrentActor actor) {
        if (actor == null || actor.userId() <= 0) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Authentication required");
        }
        return actor.userId();
    }

    private void requireExpectedTaskVersion(Long expectedTaskVersion) {
        if (expectedTaskVersion == null) {
            throw new BusinessException(HttpStatus.PRECONDITION_REQUIRED.value(),
                    "expectedTaskVersion is required for task workflow mutations");
        }
        if (expectedTaskVersion < 0L) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "expectedTaskVersion must be non-negative");
        }
    }

    private void requireRequestId(UUID requestId) {
        if (requestId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "requestId / Idempotency-Key is required for task workflow mutations");
        }
    }

    private void checkExpectedVersion(Long expectedTaskVersion, TaskEntity task) {
        if (!Objects.equals(expectedTaskVersion, task.effectiveVersion())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Task has been modified (expected version " + expectedTaskVersion
                            + ", actual " + task.effectiveVersion() + "); refresh and retry");
        }
    }

    private String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), field + " is required");
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private int validateQualityRating(Integer rating) {
        if (rating == null || rating < 1 || rating > 5) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "qualityRating must be an integer between 1 and 5 under quality-v1 rubric");
        }
        return rating;
    }

    private Instant txNow() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private String computeContentIdentity(ProjectFileEntity file) {
        return sha256String(file.getStorageBucket() + ":" + file.getStorageKey() + ":" + file.getFileSize());
    }

    private String computeSubmitHash(SubmitTaskCommand c, List<Long> fileIds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", OP_SUBMIT);
        m.put("taskId", c.taskId());
        m.put("expectedTaskVersion", c.expectedTaskVersion());
        m.put("summary", c.summary() != null ? c.summary().trim() : null);
        m.put("evidenceProjectFileIds", fileIds);
        return sha256Json(m);
    }

    private String computeWithdrawHash(WithdrawTaskCommand c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", OP_WITHDRAW);
        m.put("taskId", c.taskId());
        m.put("submissionId", c.submissionId());
        m.put("expectedTaskVersion", c.expectedTaskVersion());
        m.put("reason", trimToNull(c.reason()));
        return sha256Json(m);
    }

    private String computeReviewHash(ReviewTaskCommand c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", OP_REVIEW);
        m.put("taskId", c.taskId());
        m.put("submissionId", c.submissionId());
        m.put("expectedTaskVersion", c.expectedTaskVersion());
        m.put("decision", c.decision() != null ? c.decision().name() : null);
        m.put("qualityRating", c.qualityRating());
        m.put("feedback", trimToNull(c.feedback()));
        m.put("evidenceSummary", trimToNull(c.evidenceSummary()));
        return sha256Json(m);
    }

    private String computeSupersedeHash(SupersedeEvaluationCommand c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", OP_SUPERSEDE);
        m.put("taskId", c.taskId());
        m.put("submissionId", c.submissionId());
        m.put("expectedEvaluationId", c.expectedEvaluationId());
        m.put("expectedEvaluationVersion", c.expectedEvaluationVersion());
        m.put("expectedTaskVersion", c.expectedTaskVersion());
        m.put("qualityRating", c.qualityRating());
        m.put("reason", trimToNull(c.reason()));
        m.put("evidenceSummary", trimToNull(c.evidenceSummary()));
        return sha256Json(m);
    }

    private String computeReopenHash(ReopenTaskCommand c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", OP_REOPEN);
        m.put("taskId", c.taskId());
        m.put("expectedTaskVersion", c.expectedTaskVersion());
        m.put("reason", trimToNull(c.reason()));
        return sha256Json(m);
    }

    private String computeRecoverHash(RecoverLegacyReviewCommand c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", OP_RECOVER_LEGACY_REVIEW);
        m.put("taskId", c.taskId());
        m.put("expectedTaskVersion", c.expectedTaskVersion());
        m.put("reason", trimToNull(c.reason()));
        return sha256Json(m);
    }

    private String toJson(Object value) {
        try {
            return CANONICAL_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize workflow JSON", ex);
        }
    }

    private String sha256Json(Object value) {
        return sha256String(toJson(value));
    }

    private String sha256String(String text) {
        try {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash payload", ex);
        }
    }

    private record LockedContext(
            ProjectEntity project,
            TaskEntity task,
            ProjectMemberEntity actorMember,
            List<ProjectMemberEntity> members,
            Map<Long, AccountAuthStateDto> accounts) {
    }
}
