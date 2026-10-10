package com.taskpilot.ai.tools.domain;

import com.taskpilot.ai.dto.AutoAssignmentResponse;
import com.taskpilot.ai.dto.ConfirmationRequiredDto;
import com.taskpilot.ai.dto.RecommendAndAssignResult;
import com.taskpilot.ai.dto.RecommendationRequestSource;
import com.taskpilot.ai.dto.RecommendationView;
import com.taskpilot.ai.dto.RecommendedCandidateView;
import com.taskpilot.ai.entity.RecommendationDecisionEventEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotCandidateEntity;
import com.taskpilot.ai.entity.RecommendationSnapshotEntity;
import com.taskpilot.ai.service.AutoAssignmentService;
import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import org.springframework.http.HttpStatus;
import com.taskpilot.contracts.aiquery.dto.*;
import com.taskpilot.contracts.aiquery.port.out.MemberAnalyticsPort;
import com.taskpilot.contracts.aiquery.port.out.ProjectInsightsPort;
import com.taskpilot.contracts.aiquery.port.out.TaskCommandPort;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

import static com.taskpilot.ai.tools.support.AiToolSupport.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class AhpAssignmentAiTools {

    private final AutoAssignmentService autoAssignmentService;
    private final ProjectMemberPort projectMemberPort;
    private final ProjectInsightsPort projectInsightsPort;
    private final MemberAnalyticsPort memberAnalyticsPort;
    private final TaskCommandPort taskCommandPort;
    private final PendingAiActionService pendingAiActionService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.taskpilot.ai.service.RecommendationDecisionService recommendationDecisionService;

    public void setRecommendationDecisionService(com.taskpilot.ai.service.RecommendationDecisionService service) {
        this.recommendationDecisionService = service;
    }

    @Tool("Get the workload snapshot of all members in a project by project ID.")
    public List<MemberWorkloadDto> getMemberWorkload(@P("The ID of the project") String projectId) {
        log.info("[AiTool] getMemberWorkload called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        return memberAnalyticsPort.getMemberWorkloadForProject(toLong(projectId), userId);
    }


    @Tool("Search and list members in a specific project. All filters except projectId are optional. Supports sorting.")
    public Object queryProjectMembers(
            @P("The ID of the project") String projectId,
            @P("Optional. Role to filter by (e.g. MANAGER, MEMBER). Can be null/empty.") String role,
            @P("Optional. Search keyword for member name") String searchTerm,
            @P("Optional. Field to sort by: 'fullName', 'role' (default 'fullName')") String sortBy,
            @P("Optional. Sort direction: 'ASC' or 'DESC' (default 'ASC')") String sortDirection,
            @P("Optional. Maximum number of members to return (default 10, max 20)") Integer limit) {
        log.info("[AiTool] queryProjectMembers called for project {} role={} search={} sortBy={} sortDir={}", projectId, role, searchTerm, sortBy, sortDirection);
        Long userId = ToolExecutionContext.requireUserId();
        List<ProjectMemberDto> allMembers = projectInsightsPort.getProjectMembers(toLong(projectId), userId);

        String sortField = sortBy != null ? sortBy.trim().toLowerCase() : "fullname";
        String direction = sortDirection != null ? sortDirection.trim().toUpperCase() : "ASC";
        boolean isAsc = !"DESC".equals(direction);

        java.util.Comparator<ProjectMemberDto> comparator = (m1, m2) -> {
            int comp = 0;
            switch (sortField) {
                case "role":
                    comp = String.valueOf(m1.role()).compareToIgnoreCase(String.valueOf(m2.role()));
                    break;
                case "fullname":
                default:
                    comp = String.valueOf(m1.fullName()).compareToIgnoreCase(String.valueOf(m2.fullName()));
                    break;
            }
            return isAsc ? comp : -comp;
        };

        // Apply filters in-memory for Stage 1
        List<Map<String, Object>> filtered = allMembers.stream()
                .filter(m -> role == null || role.isBlank() || role.equalsIgnoreCase(m.role()))
                .filter(m -> searchTerm == null || searchTerm.isBlank() || 
                        (m.fullName() != null && m.fullName().toLowerCase().contains(searchTerm.toLowerCase())))
                .sorted(comparator)
                .map(m -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("memberId", m.memberId());
                    map.put("fullName", m.fullName());
                    map.put("role", m.role());
                    map.put("skills", m.skills() != null ? m.skills() : "");
                    return map;
                })
                .limit(limit != null ? Math.max(1, Math.min(limit, 20)) : 10)
                .collect(Collectors.toList());

        return Map.of("results", filtered, "totalMatched", filtered.size());
    }


    @Tool("Get workload details of a specific member by member ID (open tasks, overdue tasks, estimated hours).")
    public MemberWorkloadDto getMemberWorkloadByMemberId(@P("The ID of the member") String memberId) {
        log.info("[AiTool] getMemberWorkloadByMemberId called for member {}", memberId);
        Long userId = ToolExecutionContext.requireUserId();
        return memberAnalyticsPort.getMemberWorkload(toLong(memberId), userId);
    }


    @Tool("Assign a task to a project member by task ID and member ID. Requires confirmation.")
    public Object assignTaskToMember(
            @P("The ID of the task") String taskId,
            @P("The ID of the member") String memberId,
            @P("Reason for the assignment") String reason) {
        return assignTaskToMemberInternal(taskId, memberId, reason, null);
    }

    @Tool("Assign a task to an alternative candidate from a recommendation snapshot, overriding the recommendation. Requires confirmation.")
    public Object overrideRecommendationAndAssignTask(
            @P("The ID of the task to assign") String taskId,
            @P("The ID of the candidate member to assign from the snapshot") String memberId,
            @P("The recommendation snapshot ID being overridden") String snapshotId,
            @P("Reason for choosing this candidate over the recommendation") String reason) {
        return assignTaskToMemberInternal(taskId, memberId, reason, snapshotId);
    }

    public Object assignTaskToMemberInternal(
            String taskId,
            String memberId,
            String reason,
            String snapshotId) {
        log.info("[AiTool] assignTaskToMember called for task {} -> member {}, snapshotId={}", taskId, memberId, snapshotId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        String safeReason = hasText(reason) ? reason : "Task assigned by AI tool";

        Long resolvedTaskId = toLong(taskId);
        Long resolvedMemberId = toLong(memberId);
        TaskDetailDto task = taskCommandPort.getTaskDetails(resolvedTaskId, userId);
        autoAssignmentService.validateProjectManager(task.projectId(), userId);

        if (hasText(snapshotId)) {
            if (recommendationDecisionService == null) {
                throw new IllegalStateException("RecommendationDecisionService is not available");
            }
            RecommendationSnapshotEntity snapshot = recommendationDecisionService.findSnapshot(snapshotId.trim())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                            "Recommendation snapshot not found: " + snapshotId));
            if (!Objects.equals(snapshot.getProjectId(), task.projectId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "Snapshot project " + snapshot.getProjectId() + " does not match task project " + task.projectId());
            }
            if (snapshot.getTaskId() != null && !Objects.equals(snapshot.getTaskId(), resolvedTaskId)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "Snapshot task " + snapshot.getTaskId() + " does not match task " + resolvedTaskId);
            }
            Set<Long> candidateIds = snapshot.getCandidates() != null
                    ? snapshot.getCandidates().stream().map(RecommendationSnapshotCandidateEntity::getCandidateId).collect(Collectors.toSet())
                    : Set.of();
            if (!candidateIds.contains(resolvedMemberId)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "Candidate " + resolvedMemberId + " is not present in recommendation snapshot " + snapshotId);
            }
            if (Objects.equals(resolvedMemberId, snapshot.getRecommendedCandidateId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "Candidate matches recommended candidate; use standard assignment instead of override");
            }
        }

        String userFacingSummary = hasText(snapshotId)
                ? "Xác nhận gán công việc cho ứng viên đã chọn, thay cho đề xuất ban đầu."
                : "Assign task " + taskId + " to member " + memberId;

        Map<String, Object> machineArgs = new LinkedHashMap<>();
        machineArgs.put("taskId", resolvedTaskId);
        machineArgs.put("memberId", resolvedMemberId);
        machineArgs.put("reason", safeReason);
        if (hasText(snapshotId)) {
            machineArgs.put("snapshotId", snapshotId.trim());
            machineArgs.put("selectedCandidateId", resolvedMemberId);
        }

        return pendingAiActionService.create(
                userId,
                sessionId,
                hasText(snapshotId) ? "overrideRecommendationAndAssignTask" : "assignTaskToMember",
                userFacingSummary,
                machineArgs,
                null,
                () -> {
                    autoAssignmentService.validateProjectManager(task.projectId(), userId);
                    if (hasText(snapshotId) && projectMemberPort != null && !projectMemberPort.isProjectMember(task.projectId(), resolvedMemberId)) {
                        throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                                "Candidate " + resolvedMemberId + " is not an active member of project " + task.projectId());
                    }
                    TaskAssignmentResultDto assignmentResult = taskCommandPort.assignTaskToMember(resolvedTaskId, resolvedMemberId, safeReason, userId, false);
                    if (hasText(snapshotId) && recommendationDecisionService != null) {
                        try {
                            recommendationDecisionService.recordOverridden(
                                    userId,
                                    snapshotId.trim(),
                                    resolvedMemberId,
                                    "PM_OVERRIDE",
                                    safeReason
                            );
                        } catch (Exception e) {
                            log.error("[AiTool] Failed to record OVERRIDDEN decision event for snapshotId={}, projectId={}, taskId={}, selectedCandidateId={}, actorId={}: {}",
                                    snapshotId.trim(), task.projectId(), resolvedTaskId, resolvedMemberId, userId, e.getMessage(), e);
                        }
                    }
                    return assignmentResult;
                });
    }


    @Tool("Assign a task to a project member by task ID and member name. Resolves project and member. Requires confirmation.")
    public Object assignTaskToMemberByName(
            @P("The ID of the task") String taskId,
            @P("Full or partial member name, e.g. Julia Design") String memberName,
            @P("Reason for the assignment") String reason) {
        log.info("[AiTool] assignTaskToMemberByName called for task {} -> {}", taskId, memberName);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        Long resolvedTaskId = toLong(taskId);
        TaskDetailDto task = taskCommandPort.getTaskDetails(resolvedTaskId, userId);
        autoAssignmentService.validateProjectManager(task.projectId(), userId);
        ProjectMemberDto member = resolveProjectMemberByName(projectInsightsPort, task.projectId(), memberName, userId);
        String safeReason = hasText(reason)
                ? reason
                : "User explicitly requested assignment to " + member.fullName() + ".";

        return pendingAiActionService.create(
                userId,
                sessionId,
                "assignTaskToMember",
                "Assign task " + taskId + " to " + member.fullName() + " (user specified)",
                args("taskId", resolvedTaskId, "memberId", member.memberId(), "memberName", member.fullName(),
                        "reason", safeReason, "source", "user_specified_assignee"),
                Map.of("taskId", resolvedTaskId, "memberId", member.memberId(), "memberName", member.fullName(),
                        "projectId", task.projectId(), "reason", safeReason),
                () -> {
                    autoAssignmentService.validateProjectManager(task.projectId(), userId);
                    return taskCommandPort.assignTaskToMember(resolvedTaskId, member.memberId(), safeReason, userId, false);
                });
    }


    @Tool("Recommend the top candidate and assign the task to them in a single write operation. Requires confirmation.")
    public Object recommendAndAssignTask(
            @P("The ID of the task to assign") String taskId,
            @P("Optional project ID. If omitted, it is read from task details") String projectId,
            @P("Optional comma-separated required skill names. If omitted, task required skills are used") String skills,
            @P("Optional task difficulty 1-10. If omitted, task difficulty is used. Note: send as string like '5'") String difficulty,
            @P("Reason to store with the assignment") String reason) {
        log.info("[AiTool] recommendAndAssignTask called for task {}", taskId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        Long resolvedTaskId = toLong(taskId);
        TaskDetailDto task = taskCommandPort.getTaskDetails(resolvedTaskId, userId);
        Long resolvedProjectId = hasText(projectId) ? toLong(projectId) : task.projectId();
        autoAssignmentService.validateProjectManager(resolvedProjectId, userId);
        String resolvedSkills = hasText(skills) ? skills : task.requiredSkills();
        boolean shouldPersistProvidedSkills = hasText(skills) && !hasText(task.requiredSkills());

        if (!hasText(resolvedSkills)) {
            return new RecommendAndAssignResult(false, resolvedTaskId, resolvedProjectId, null, null, reason,
                    (RecommendationView) null, null,
                    "Task " + taskId + " is missing required skills. Please provide skills before assigning.");
        }

        int parsedDifficulty = task.difficultyLevel() != null ? task.difficultyLevel() : 5;
        if (difficulty != null && !difficulty.isBlank()) {
            try { parsedDifficulty = Integer.parseInt(difficulty.trim()); } catch (Exception ignored) {}
        }
        int resolvedDifficulty = Math.max(1, Math.min(10, parsedDifficulty));

        RecommendationView recommendationView;
        String snapshotId;

        AutoAssignmentService.SnapshotEvaluationResult snapshotResult = autoAssignmentService.recommendCandidatesForPreview(
                resolvedProjectId,
                resolvedTaskId,
                parseSkills(resolvedSkills),
                resolvedDifficulty,
                userId,
                Set.of(),
                Set.of());

        if (snapshotResult != null) {
            recommendationView = snapshotResult.view();
            snapshotId = snapshotResult.snapshotId();
        } else {
            recommendationView = autoAssignmentService.recommendCandidatesView(
                    resolvedProjectId,
                    parseSkills(resolvedSkills),
                    resolvedDifficulty,
                    userId,
                    Set.of(),
                    Set.of());
            snapshotId = null;
        }

        if (recommendationView == null || recommendationView.candidates() == null || recommendationView.candidates().isEmpty()) {
            return new RecommendAndAssignResult(false, resolvedTaskId, resolvedProjectId, null, null, reason,
                    recommendationView, null,
                    "No eligible candidate found for task " + taskId + ".",
                    snapshotId);
        }

        RecommendedCandidateView selected = recommendationView.candidates().get(0);
        String safeReason = hasText(reason)
                ? reason
                : "AI selected the top-ranked candidate based on skill fit, workload, and project heuristic mode.";
        RecommendAndAssignResult preview = new RecommendAndAssignResult(false, resolvedTaskId, resolvedProjectId,
                selected.candidateId(), selected.displayName(), safeReason, recommendationView, null,
                "Ready to assign task " + taskId + " to " + selected.displayName() + " after confirmation.",
                snapshotId);

        return pendingAiActionService.create(
                userId,
                sessionId,
                "recommendAndAssignTask",
                (shouldPersistProvidedSkills ? "Save required skills and assign task " : "Assign task ")
                        + taskId + " to " + selected.displayName() + " (top recommended candidate)",
                args("taskId", resolvedTaskId, "projectId", resolvedProjectId, "skills", resolvedSkills,
                        "difficulty", resolvedDifficulty, "memberId", selected.candidateId(), "reason", safeReason,
                        "persistSkills", shouldPersistProvidedSkills, "snapshotId", snapshotId),
                preview,
                () -> {
                    autoAssignmentService.validateProjectManager(resolvedProjectId, userId);
                    if (shouldPersistProvidedSkills) {
                        taskCommandPort.updateTaskRequiredSkills(resolvedTaskId, resolvedSkills, userId);
                    }
                    TaskAssignmentResultDto assignment = taskCommandPort.assignTaskToMember(
                            resolvedTaskId,
                            selected.candidateId(),
                            safeReason,
                            userId,
                            false);
                    if (snapshotId != null && recommendationDecisionService != null) {
                        try {
                            recommendationDecisionService.recordAccepted(
                                    userId,
                                    snapshotId,
                                    selected.candidateId(),
                                    "CONFIRMED_RECOMMENDATION",
                                    safeReason
                            );
                        } catch (Exception e) {
                            log.error("[AiTool] Failed to record ACCEPTED decision event for snapshotId={}, projectId={}, taskId={}, selectedCandidateId={}, actorId={}: {}",
                                    snapshotId, resolvedProjectId, resolvedTaskId, selected.candidateId(), userId, e.getMessage(), e);
                        }
                    }
                    return new RecommendAndAssignResult(true, resolvedTaskId, resolvedProjectId, selected.candidateId(),
                            selected.displayName(), safeReason, recommendationView, assignment,
                            "Task " + taskId + " assigned to " + selected.displayName() + ".",
                            snapshotId);
                });
    }


    @Tool("Recommend ranked candidates for a project based on skills and difficulty (1-10, default is 5). Read-only.")
    public RecommendationView recommendAssignmentCandidates(
            @P("The project ID") String projectId,
            @P("Comma-separated list of required skill names") String skills,
            @P("Task difficulty 1-10. Note: send as string like '5'") String difficulty) {
        log.info("[AiTool] recommendAssignmentCandidates called for project {}", projectId);

        Long userId = ToolExecutionContext.requireUserId();
        int parsedDifficulty = 5;
        if (difficulty != null && !difficulty.isBlank()) {
            try { parsedDifficulty = Integer.parseInt(difficulty.trim()); } catch (Exception ignored) {}
        }
        int safeDifficulty = Math.max(1, Math.min(10, parsedDifficulty));
        List<String> requiredSkills = parseSkills(skills);

        RecommendationView view = autoAssignmentService.recommendCandidatesViewWithSnapshot(
                toLong(projectId),
                null,
                requiredSkills,
                safeDifficulty,
                userId,
                Set.of(),
                Set.of(),
                RecommendationRequestSource.AI_TOOL_PROJECT_RECOMMENDATION);
        return view != null ? view : autoAssignmentService.recommendCandidatesView(toLong(projectId), requiredSkills, safeDifficulty, userId, Set.of(), Set.of());
    }


    @Tool("Recommend and compare candidates specifically for a task ID, reading its metrics automatically. Supports filters. Read-only.")
    public RecommendationView recommendTaskAssignmentCandidates(
            @P("The ID of the task") String taskId,
            @P("Optional comma-separated required skill names or IDs. Use this when the task is missing skills and the user provided them in a form.") String skills,
            @P("Optional task difficulty 1-10. If omitted, task difficulty is used. Note: send as string like '5'") String difficulty,
            @P("Optional comma-separated member names to compare/include, e.g. 'Julia Design, Evan Ops'") String includeMemberNames,
            @P("Optional comma-separated member/user IDs to compare/include, e.g. '10,5'") String includeMemberIds,
            @P("Optional comma-separated member names to exclude") String excludeMemberNames,
            @P("Optional comma-separated member/user IDs to exclude") String excludeMemberIds,
            @P("Set true to exclude the task's current assignee") String excludeCurrentAssignee) {
        Long userId = ToolExecutionContext.requireUserId();
        Long resolvedTaskId = toLong(taskId);
        TaskDetailDto task = taskCommandPort.getTaskDetails(resolvedTaskId, userId);
        Long projectId = task.projectId();
        String resolvedSkills = hasText(skills) ? skills : task.requiredSkills();
        if (!hasText(resolvedSkills)) {
            resolvedSkills = "Java";
        }

        Set<Long> includeIds = parseIdSet(includeMemberIds);
        includeIds.addAll(resolveProjectMemberIdsByNames(projectInsightsPort, projectId, includeMemberNames, userId));

        Set<Long> excludeIds = parseIdSet(excludeMemberIds);
        excludeIds.addAll(resolveProjectMemberIdsByNames(projectInsightsPort, projectId, excludeMemberNames, userId));
        if (isTruthy(excludeCurrentAssignee) && task.assigneeId() != null) {
            excludeIds.add(task.assigneeId());
        }

        int parsedDifficulty = task.difficultyLevel() == null ? 5 : task.difficultyLevel();
        if (hasText(difficulty)) {
            try { parsedDifficulty = Integer.parseInt(difficulty.trim()); } catch (Exception ignored) {}
        }
        int resolvedDifficulty = Math.max(1, Math.min(10, parsedDifficulty));
        RecommendationView response = autoAssignmentService.recommendCandidatesViewWithSnapshot(
                projectId,
                resolvedTaskId,
                parseSkills(resolvedSkills),
                resolvedDifficulty,
                userId,
                includeIds,
                excludeIds,
                RecommendationRequestSource.AI_TOOL_TASK_RECOMMENDATION);
        if (response == null) {
            response = autoAssignmentService.recommendCandidatesView(
                    projectId,
                    parseSkills(resolvedSkills),
                    resolvedDifficulty,
                    userId,
                    includeIds,
                    excludeIds);
        }

        String explanation = response.aiExplanation();
        if ((explanation == null || explanation.isBlank()) && isTruthy(excludeCurrentAssignee) && task.assigneeName() != null) {
            explanation = "Excluded current assignee " + task.assigneeName() + " for this recommendation.";
            return RecommendationView.builder()
                    .projectId(response.projectId())
                    .requiredSkills(response.requiredSkills())
                    .candidates(response.candidates())
                    .differentiationStatus(response.differentiationStatus())
                    .presentationContractVersion(response.presentationContractVersion())
                    .scoringModelVersion(response.scoringModelVersion())
                    .heuristicMode(response.heuristicMode())
                    .aiExplanation(explanation)
                    .build();
        }
        return response;
    }


    @Tool("Update a project member's role (MANAGER, MEMBER). Requires confirmation.")
    public Object updateMemberRole(
            @P("The ID of the project") Long projectId,
            @P("The ID of the target user to update role") Long targetUserId,
            @P("The new role (MANAGER, MEMBER)") String role) {
        log.info("[AiTool] updateMemberRole called for project {} target {} role {}", projectId, targetUserId, role);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        return pendingAiActionService.create(
                userId,
                sessionId,
                "updateMemberRole",
                "Update member role of user " + targetUserId + " to " + role + " in project " + projectId,
                args("projectId", projectId, "targetUserId", targetUserId, "role", role),
                null,
                () -> {
                    projectInsightsPort.updateMemberRole(projectId, targetUserId, role, userId);
                    return "Member role updated successfully";
                });
    }


    @Tool("Remove a member from a project by user ID. Requires confirmation.")
    public Object removeMember(
            @P("The ID of the project") Long projectId,
            @P("The ID of the target user to remove") Long targetUserId) {
        log.info("[AiTool] removeMember called for project {} target {}", projectId, targetUserId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        return pendingAiActionService.create(
                userId,
                sessionId,
                "removeMember",
                "Remove member " + targetUserId + " from project " + projectId,
                args("projectId", projectId, "targetUserId", targetUserId),
                null,
                () -> {
                    projectInsightsPort.removeMember(projectId, targetUserId, userId);
                    return "Member removed successfully";
                });
    }

    @Tool("Record a manager's decision to reject a task assignment recommendation without choosing an alternative.")
    public Object recordRecommendationRejection(
            @P("The ID of the recommendation snapshot") String snapshotId,
            @P("Optional reason code for the rejection (e.g. NO_SUITABLE_CANDIDATE, TASK_POSTPONED)") String reasonCode,
            @P("Optional note explaining the rejection") String note) {
        log.info("[AiTool] recordRecommendationRejection called for snapshot {}", snapshotId);
        Long userId = ToolExecutionContext.requireUserId();
        if (recommendationDecisionService == null) {
            throw new IllegalStateException("RecommendationDecisionService is not available");
        }
        String safeReasonCode = hasText(reasonCode) ? reasonCode.trim() : "PM_REJECTION";
        RecommendationDecisionEventEntity decision = recommendationDecisionService.recordRejected(
                userId, snapshotId, safeReasonCode, note);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("decisionId", decision.getDecisionId());
        response.put("snapshotId", decision.getSnapshotId());
        response.put("decisionType", decision.getDecisionType().name());
        response.put("recommendedCandidateId", decision.getRecommendedCandidateId());
        response.put("status", "RECORDED");
        return response;
    }

}
