package com.taskpilot.ai.tools;

import com.taskpilot.ai.dto.AutoAssignmentResponse;
import com.taskpilot.ai.dto.CandidateScore;
import com.taskpilot.ai.dto.ConfirmationRequiredDto;
import com.taskpilot.ai.dto.RecommendAndAssignResult;
import com.taskpilot.ai.service.AutoAssignmentService;
import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.service.SmartQueryService;
import com.taskpilot.ai.tools.domain.*;
import com.taskpilot.contracts.assignment.dto.ProjectDueDto;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.contracts.aiquery.dto.*;
import com.taskpilot.contracts.aiquery.port.out.MemberAnalyticsPort;
import com.taskpilot.contracts.aiquery.port.out.ProjectInsightsPort;
import com.taskpilot.contracts.aiquery.port.out.SprintQueryPort;
import com.taskpilot.contracts.aiquery.port.out.TaskCommandPort;
import com.taskpilot.contracts.aiquery.port.out.TaskCommentQueryPort;
import com.taskpilot.contracts.skill.dto.SkillDto;
import com.taskpilot.contracts.skill.port.out.SkillPort;
import com.taskpilot.contracts.user.port.out.UserNotificationQueryPort;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class TaskPilotAiTools {

    private final ProjectAiTools projectAiTools;
    private final TaskAiTools taskAiTools;
    private final SprintAiTools sprintAiTools;
    private final CommentAiTools commentAiTools;
    private final NotificationAiTools notificationAiTools;
    private final SkillAiTools skillAiTools;
    private final AhpAssignmentAiTools ahpAssignmentAiTools;
    private final SystemAiTools systemAiTools;

    @Autowired
    public TaskPilotAiTools(
            ProjectAiTools projectAiTools,
            TaskAiTools taskAiTools,
            SprintAiTools sprintAiTools,
            CommentAiTools commentAiTools,
            NotificationAiTools notificationAiTools,
            SkillAiTools skillAiTools,
            AhpAssignmentAiTools ahpAssignmentAiTools,
            SystemAiTools systemAiTools) {
        this.projectAiTools = projectAiTools;
        this.taskAiTools = taskAiTools;
        this.sprintAiTools = sprintAiTools;
        this.commentAiTools = commentAiTools;
        this.notificationAiTools = notificationAiTools;
        this.skillAiTools = skillAiTools;
        this.ahpAssignmentAiTools = ahpAssignmentAiTools;
        this.systemAiTools = systemAiTools;
    }

    public TaskPilotAiTools(
            AutoAssignmentService autoAssignmentService,
            ProjectMemberPort projectMemberPort,
            ProjectInsightsPort projectInsightsPort,
            MemberAnalyticsPort memberAnalyticsPort,
            TaskCommandPort taskCommandPort,
            TaskCommentQueryPort taskCommentQueryPort,
            SprintQueryPort sprintQueryPort,
            SkillPort skillPort,
            UserNotificationQueryPort userNotificationQueryPort,
            PendingAiActionService pendingAiActionService,
            SmartQueryService smartQueryService,
            @Nullable JdbcTemplate jdbcTemplate) {
        this(
                new ProjectAiTools(projectInsightsPort, projectMemberPort, pendingAiActionService),
                new TaskAiTools(taskCommandPort, projectInsightsPort, pendingAiActionService),
                new SprintAiTools(sprintQueryPort, pendingAiActionService),
                new CommentAiTools(taskCommentQueryPort, pendingAiActionService, jdbcTemplate),
                new NotificationAiTools(userNotificationQueryPort, pendingAiActionService),
                new SkillAiTools(skillPort, pendingAiActionService),
                new AhpAssignmentAiTools(autoAssignmentService, projectMemberPort, projectInsightsPort,
                        memberAnalyticsPort, taskCommandPort, pendingAiActionService),
                new SystemAiTools(pendingAiActionService, smartQueryService, jdbcTemplate)
        );
    }

    public TaskPilotAiTools(
            AutoAssignmentService autoAssignmentService,
            ProjectMemberPort projectMemberPort,
            ProjectInsightsPort projectInsightsPort,
            MemberAnalyticsPort memberAnalyticsPort,
            TaskCommandPort taskCommandPort,
            TaskCommentQueryPort taskCommentQueryPort,
            SprintQueryPort sprintQueryPort,
            SkillPort skillPort,
            UserNotificationQueryPort userNotificationQueryPort,
            PendingAiActionService pendingAiActionService,
            SmartQueryService smartQueryService) {
        this(autoAssignmentService, projectMemberPort, projectInsightsPort, memberAnalyticsPort,
                taskCommandPort, taskCommentQueryPort, sprintQueryPort, skillPort, userNotificationQueryPort,
                pendingAiActionService, smartQueryService, null);
    }

    public record AiQueryTaskDto(
            Long id,
            Long projectId,
            Long sprintId,
            Long parentTaskId,
            String title,
            String description,
            String status,
            String priority,
            Integer difficulty,
            String requiredSkills,
            Long assigneeId,
            String assigneeName,
            String dueDate,
            String createdAt
    ) {}

    @Tool("Search for projects the current user participates in. All filters are optional (can be null/empty). Supports keyword search and sorting.")
    public Object queryProjects(
            @P("Optional. Status to filter by (e.g. PLANNING, ACTIVE, COMPLETED, ARCHIVED). Use null/empty to get all statuses.") String status,
            @P("Optional. Role of the user in the project (e.g. MANAGER, MEMBER). Use null/empty to get all roles.") String role,
            @P("Optional. Search keyword for project name or description") String searchTerm,
            @P("Optional. Field to sort by: 'name', 'startDate', 'endDate', 'status' (default 'name')") String sortBy,
            @P("Optional. Sort direction: 'ASC' or 'DESC' (default 'ASC')") String sortDirection,
            @P("Optional. Maximum number of projects to return (default 10, max 20)") Integer limit) {
        return projectAiTools.queryProjects(status, role, searchTerm, sortBy, sortDirection, limit);
    }

    @Tool("Get the status, progress, and health summary of a project by its project ID.")
    public ProjectStatusDto getProjectStatus(@P("The ID of the project to query") String projectId) {
        return projectAiTools.getProjectStatus(projectId);
    }

    @Tool("Get the workload snapshot of all members in a project by project ID.")
    public List<MemberWorkloadDto> getMemberWorkload(@P("The ID of the project") String projectId) {
        return ahpAssignmentAiTools.getMemberWorkload(projectId);
    }

    @Tool("Search and list members in a specific project. All filters except projectId are optional. Supports sorting.")
    public Object queryProjectMembers(
            @P("The ID of the project") String projectId,
            @P("Optional. Role to filter by (e.g. MANAGER, MEMBER). Can be null/empty.") String role,
            @P("Optional. Search keyword for member name") String searchTerm,
            @P("Optional. Field to sort by: 'fullName', 'role' (default 'fullName')") String sortBy,
            @P("Optional. Sort direction: 'ASC' or 'DESC' (default 'ASC')") String sortDirection,
            @P("Optional. Maximum number of members to return (default 10, max 20)") Integer limit) {
        return ahpAssignmentAiTools.queryProjectMembers(projectId, role, searchTerm, sortBy, sortDirection, limit);
    }

    @Tool("Fetch all labels configured for a project by project ID.")
    public List<LabelSummaryDto> getProjectLabels(@P("The ID of the project") String projectId) {
        return projectAiTools.getProjectLabels(projectId);
    }

    @Tool("Get workload details of a specific member by member ID (open tasks, overdue tasks, estimated hours).")
    public MemberWorkloadDto getMemberWorkloadByMemberId(@P("The ID of the member") String memberId) {
        return ahpAssignmentAiTools.getMemberWorkloadByMemberId(memberId);
    }

    @Tool("Get task details (title, description, status, priority, difficulty, required skills, due date) by task ID.")
    public TaskDetailDto getTaskDetails(@P("The ID of the task") String taskId) {
        return taskAiTools.getTaskDetails(taskId);
    }

    @Tool("Search the global system skill directory by keyword (use empty string to list default active skills).")
    public List<SkillDto> searchSystemSkills(
            @P("Skill search keyword. Use empty string to list common active skills.") String keyword) {
        return skillAiTools.searchSystemSkills(keyword);
    }

    @Tool("Create a new system skill in the shared skill directory. Requires confirmation.")
    public Object createSystemSkill(
            @P("Skill name") String name,
            @P("Optional skill description") String description) {
        return skillAiTools.createSystemSkill(name, description);
    }

    @Tool("Partially update a system skill. Send patchData map containing changed fields (name, description). Requires confirmation.")
    public Object patchSystemSkill(
            @P("The ID of the skill") Long skillId,
            @P("Map containing only changed fields") Object patchData,
            @P("Optional reason for the change") String reason) {
        return skillAiTools.patchSystemSkill(skillId, patchData, reason);
    }

    @Tool("Delete or deactivate a system skill in the shared directory by skill ID. Requires confirmation.")
    public Object deleteSystemSkill(@P("System skill ID to deactivate") Long skillId) {
        return skillAiTools.deleteSystemSkill(skillId);
    }

    @Tool("List the current user's personal skills.")
    public Object getMySkills() {
        return skillAiTools.getMySkills();
    }

    @Tool("Add a system skill that does NOT exist in the current user's personal skills with a level (1-5). CRITICAL: If the user already has this skill and you want to update or change its level, you MUST call patchMySkill instead! If adding common skills like Java, use the ID mentioned in parameter descriptions (e.g. 1 for Java) directly! DO NOT call searchSystemSkills to look up ID. Requires confirmation.")
    public Object addMySkill(
            @P("System skill ID (e.g. 1 for Java)") Long skillId,
            @P("Skill level from 1 to 5") Integer level) {
        return skillAiTools.addMySkill(skillId, level);
    }

    @Tool("Partially update an existing personal skill (e.g. update its level). Send patchData map containing changed fields (level 1-5). CRITICAL: If you want to update or change the level of a skill the user already has, you MUST call this tool (patchMySkill) instead of addMySkill! Requires confirmation.")
    public Object patchMySkill(
            @P("The ID of the skill (e.g. 1 for Java)") Long skillId,
            @P("Map containing only changed fields") Object patchData,
            @P("Optional reason for the change") String reason) {
        return skillAiTools.patchMySkill(skillId, patchData, reason);
    }

    @Tool("Remove a skill from the current user's personal skills. Requires confirmation.")
    public Object deleteMySkill(@P("System skill ID to remove from my skills (e.g. 1 for Java)") Long skillId) {
        return skillAiTools.deleteMySkill(skillId);
    }

    @Tool("List notifications for the current user. Set unreadOnly=true to get unread notifications only.")
    public String getMyNotifications(
            @P("Return only unread notifications when true") Boolean unreadOnly,
            @P("Maximum number of notifications to return, between 1 and 50") Integer limit) {
        return notificationAiTools.getMyNotifications(unreadOnly, limit);
    }

    @Tool("Get the count of unread notifications for the current user.")
    public Object getUnreadNotificationCount() {
        return notificationAiTools.getUnreadNotificationCount();
    }

    @Tool("Mark a specific notification as read by its ID. Requires confirmation.")
    public Object markNotificationRead(@P("The ID of the notification to mark as read") Long notificationId) {
        return notificationAiTools.markNotificationRead(notificationId);
    }

    @Tool("Mark all notifications for the current user as read. Requires confirmation.")
    public Object markAllNotificationsRead() {
        return notificationAiTools.markAllNotificationsRead();
    }

    @Tool("Assign a task to a project member by task ID and member ID. Requires confirmation.")
    public Object assignTaskToMember(
            @P("The ID of the task") String taskId,
            @P("The ID of the member") String memberId,
            @P("Reason for the assignment") String reason) {
        return ahpAssignmentAiTools.assignTaskToMember(taskId, memberId, reason);
    }

    @Tool("Assign a task to a project member by task ID and member name. Resolves project and member. Requires confirmation.")
    public Object assignTaskToMemberByName(
            @P("The ID of the task") String taskId,
            @P("Full or partial member name, e.g. Julia Design") String memberName,
            @P("Reason for the assignment") String reason) {
        return ahpAssignmentAiTools.assignTaskToMemberByName(taskId, memberName, reason);
    }

    @Tool("Recommend the top candidate and assign the task to them in a single write operation. Requires confirmation.")
    public Object recommendAndAssignTask(
            @P("The ID of the task to assign") String taskId,
            @P("Optional project ID. If omitted, it is read from task details") String projectId,
            @P("Optional comma-separated required skill names. If omitted, task required skills are used") String skills,
            @P("Optional task difficulty 1-10. If omitted, task difficulty is used. Note: send as string like '5'") String difficulty,
            @P("Reason to store with the assignment") String reason) {
        return ahpAssignmentAiTools.recommendAndAssignTask(taskId, projectId, skills, difficulty, reason);
    }

    @Tool("Update required skills for a task (comma-separated skill names). Requires confirmation.")
    public Object updateTaskRequiredSkills(
            @P("The ID of the task") Long taskId,
            @P("Comma-separated active skill names from the system skill directory") String skills) {
        return taskAiTools.updateTaskRequiredSkills(taskId, skills);
    }

    @Tool("Confirm and execute a pending write action by its unique action ID.")
    public Object confirmPendingAction(@P("Pending action ID returned by a confirmationRequired tool result") String actionId) {
        return systemAiTools.confirmPendingAction(actionId);
    }

    @Tool("Confirm and execute the most recent pending write action in this session.")
    public Object confirmLatestPendingAction() {
        return systemAiTools.confirmLatestPendingAction();
    }

    @Tool("Cancel a pending write action by its unique action ID.")
    public Object cancelPendingAction(@P("Pending action ID to cancel") String actionId) {
        return systemAiTools.cancelPendingAction(actionId);
    }

    @Tool("Recommend ranked candidates for a project based on skills and difficulty (1-10, default is 5). Read-only.")
    public AutoAssignmentResponse recommendAssignmentCandidates(
            @P("The project ID") String projectId,
            @P("Comma-separated list of required skill names") String skills,
            @P("Task difficulty 1-10. Note: send as string like '5'") String difficulty) {
        return ahpAssignmentAiTools.recommendAssignmentCandidates(projectId, skills, difficulty);
    }

    @Tool("Recommend and compare candidates specifically for a task ID, reading its metrics automatically. Supports filters. Read-only.")
    public AutoAssignmentResponse recommendTaskAssignmentCandidates(
            @P("The ID of the task") String taskId,
            @P("Optional comma-separated required skill names or IDs. Use this when the task is missing skills and the user provided them in a form.") String skills,
            @P("Optional task difficulty 1-10. If omitted, task difficulty is used. Note: send as string like '5'") String difficulty,
            @P("Optional comma-separated member names to compare/include, e.g. 'Julia Design, Evan Ops'") String includeMemberNames,
            @P("Optional comma-separated member/user IDs to compare/include, e.g. '10,5'") String includeMemberIds,
            @P("Optional comma-separated member names to exclude") String excludeMemberNames,
            @P("Optional comma-separated member/user IDs to exclude") String excludeMemberIds,
            @P("Set true to exclude the task's current assignee") String excludeCurrentAssignee) {
        return ahpAssignmentAiTools.recommendTaskAssignmentCandidates(taskId, skills, difficulty, includeMemberNames, includeMemberIds, excludeMemberNames, excludeMemberIds, excludeCurrentAssignee);
    }

    @Tool("Fetch projects due soon within a number-of-days window (daysAhead, default is 7).")
    public String getUpcomingProjects(
            @P("Number of days ahead to check (default 7). Note: send as string like '7'") String daysAhead) {
        return projectAiTools.getUpcomingProjects(daysAhead);
    }

    @Tool("Find projects due within a concrete date range (fromDate and toDate in YYYY-MM-DD format).")
    public String findProjectsDue(
            @P("Start date in YYYY-MM-DD format") String fromDate,
            @P("End date in YYYY-MM-DD format") String toDate) {
        return projectAiTools.findProjectsDue(fromDate, toDate);
    }

    @Tool("Search, query, and filter tasks in a project. All filters except projectId are optional (can be null/empty). Supports keyword search and sorting.")
    public Object queryTasks(
            @P("The ID of the project") String projectId,
            @P("Optional. Filter by assignee ID. Can be null/empty.") String assigneeId,
            @P("Optional. Filter by status (TODO, IN_PROGRESS, REVIEW, DONE). Can be null/empty.") String status,
            @P("Optional. Set to true to find overdue tasks (dueDate < today). Can be null/empty.") Boolean isOverdue,
            @P("Optional. Set to true to find tasks with dueDate = today. Can be null/empty.") Boolean dueToday,
            @P("Optional. Set to true to find tasks that have NO assignee. Can be null/empty.") Boolean unassignedOnly,
            @P("Optional. Search keyword for task title or description. Can be null/empty.") String searchTerm,
            @P("Optional. Field to sort by: 'title', 'dueDate', 'priority', 'difficultyLevel' (default 'title')") String sortBy,
            @P("Optional. Sort direction: 'ASC' or 'DESC' (default 'ASC')") String sortDirection,
            @P("Optional. Maximum number of results to return (default 10, max 50).") Integer limit) {
        return taskAiTools.queryTasks(projectId, assigneeId, status, isOverdue, dueToday, unassignedOnly, searchTerm, sortBy, sortDirection, limit);
    }

    public Object executeQuerySql(@P("The SELECT SQL query statement to run") String sql) {
        return systemAiTools.executeQuerySql(sql);
    }

    @Tool("Fetch subtasks belonging to a specific parent task ID.")
    public Object getSubtasks(@P("The ID of the parent task") Long parentTaskId) {
        return taskAiTools.getSubtasks(parentTaskId);
    }

    @Tool("Fetch comments made on a specific task by task ID.")
    public String getTaskComments(
            @P("The ID of the task") Long taskId,
            @P("Optional. Maximum number of comments to return. Default 10, max 30.") Integer limit) {
        return commentAiTools.getTaskComments(taskId, limit);
    }

    @Tool("Fetch comments authored by or mentioning the current user, across projects/tasks. Set mentionedMe=true for mentions.")
    public String getMyTaskComments(
            @P("Optional project ID filter") Long projectId,
            @P("Optional task ID filter") Long taskId,
            @P("True when the user asks for comments mentioning them") Boolean mentionedMe,
            @P("Maximum number of comments to return, between 1 and 50") Integer limit) {
        return commentAiTools.getMyTaskComments(projectId, taskId, mentionedMe, limit);
    }

    @Tool("Create a comment or reply to a comment on a task. Requires confirmation.")
    public Object createTaskComment(
            @P("The ID of the task") Long taskId,
            @P("Comment content") String content,
            @P("Optional parent comment ID when replying") Long parentCommentId,
            @P("Optional mentioned user IDs") List<Long> mentionedUserIds) {
        return commentAiTools.createTaskComment(taskId, content, parentCommentId, mentionedUserIds);
    }

    @Tool("Update the content of an existing task comment authored by the current user. Requires confirmation.")
    public Object updateTaskComment(
            @P("The ID of the task") Long taskId,
            @P("The ID of the comment") Long commentId,
            @P("Updated comment content") String content,
            @P("Optional mentioned user IDs") List<Long> mentionedUserIds) {
        return commentAiTools.updateTaskComment(taskId, commentId, content, mentionedUserIds);
    }

    @Tool("Partially update a task comment. Send patchData map containing changed fields (content, mentionedUserIds). Requires confirmation.")
    public Object patchTaskComment(
            @Nullable @P("Optional. The ID of the task. If not provided, the system will resolve it automatically.") Long taskId,
            @P("The ID of the comment to patch") Long commentId,
            @P("Map containing only changed fields") Object patchData,
            @P("Optional reason for the change") String reason) {
        return commentAiTools.patchTaskComment(taskId, commentId, patchData, reason);
    }

    @Tool("Delete a comment from a task. Requires confirmation.")
    public Object deleteTaskComment(
            @Nullable @P("Optional. The ID of the task. If not provided, the system will resolve it automatically.") Long taskId,
            @P("The ID of the comment") Long commentId) {
        return commentAiTools.deleteTaskComment(taskId, commentId);
    }

    @Tool("Update the status of a task (TODO, IN_PROGRESS, REVIEW, DONE). Requires confirmation.")
    public Object updateTaskStatus(
            @P("The ID of the task") Long taskId,
            @P("The new status (TODO, IN_PROGRESS, REVIEW, DONE)") String status) {
        return taskAiTools.updateTaskStatus(taskId, status);
    }

    @Tool("Update multiple fields of a task. Omit unchanged parameters. Requires confirmation.")
    public Object updateTask(
            @P("The ID of the task") Long taskId,
            @P("Optional title") String title,
            @P("Optional description") String description,
            @P("Optional status (TODO, IN_PROGRESS, REVIEW, DONE)") String status,
            @P("Optional priority (LOW, MEDIUM, HIGH, URGENT)") String priority,
            @P("Optional kanban position") Float position,
            @P("Optional full replacement label ID list") List<Long> labelIds,
            @P("Optional difficulty 1-10. Note: send as string like '5'") String difficultyLevel,
            @P("Optional full replacement required skill ID list") List<Long> requiredSkillIds,
            @P("Optional assignee user ID") Long assigneeId,
            @P("Optional start date as ISO-8601 instant or YYYY-MM-DD") String startDate,
            @P("Optional due date as ISO-8601 instant or YYYY-MM-DD") String dueDate) {
        return taskAiTools.updateTask(taskId, title, description, status, priority, position, labelIds, difficultyLevel, requiredSkillIds, assigneeId, startDate, dueDate);
    }

    @Tool("Partially update a task. Send patchData map containing changed fields (title, status, assigneeId, dueDate, etc). Requires confirmation.")
    public Object patchTask(
            @P("The ID of the task") Long taskId,
            @P("Map containing only changed fields") Object patchData,
            @P("Optional reason for the change") String reason) {
        return taskAiTools.patchTask(taskId, patchData, reason);
    }

    @Tool("Delete a task by task ID. Requires confirmation.")
    public Object deleteTask(@P("The ID of the task to delete") Long taskId) {
        return taskAiTools.deleteTask(taskId);
    }

    @Tool("Move a task on the kanban board by updating status and position. Requires confirmation.")
    public Object moveTaskKanban(
            @P("The ID of the task") String taskId,
            @P("Target status (TODO, IN_PROGRESS, REVIEW, DONE)") String status,
            @P("Target kanban position") Float position) {
        return taskAiTools.moveTaskKanban(taskId, status, position);
    }

    @Tool("Fetch all sprints belonging to a project. Supports optional status filter.")
    public Object getSprintsByProject(
            @P("The ID of the project") String projectId,
            @P("Optional. Filter sprints by status (e.g. ACTIVE, PLANNING, COMPLETED)") String status,
            @P("Optional. Maximum number of results to return. Default 10, max 30.") Integer limit) {
        return sprintAiTools.getSprintsByProject(projectId, status, limit);
    }

    @Tool("Create a new label in a project with optional name and hex color. Requires confirmation.")
    public Object createProjectLabel(
            @P("The ID of the project") Long projectId,
            @P("Label name") String name,
            @P("Optional hex color, e.g. #6366F1") String color) {
        return projectAiTools.createProjectLabel(projectId, name, color);
    }

    @Tool("Delete a label from a project by project ID and label ID. Requires confirmation.")
    public Object deleteProjectLabel(
            @P("The ID of the project") Long projectId,
            @P("The ID of the label") Long labelId) {
        return projectAiTools.deleteProjectLabel(projectId, labelId);
    }

    @Tool("Create a new project. Supports optional description, startDate, endDate. Requires confirmation.")
    public Object createProject(
            @P("Name of the project. If missing or not specified, you MUST still call this tool with a null/empty name; it will automatically return the form.") String projectName,
            @P("Optional description of the project") String description,
            @P("Optional start date in YYYY-MM-DD format") String startDate,
            @P("Optional end date in YYYY-MM-DD format") String endDate) {
        return projectAiTools.createProject(projectName, description, startDate, endDate);
    }

    @Tool("Update multiple fields of an existing project. Omit unchanged parameters. Requires confirmation.")
    public Object updateProject(
            @P("The ID of the project to update") Long projectId,
            @P("Optional name of the project") String name,
            @P("Optional description") String description,
            @P("Optional status (ACTIVE, COMPLETED, ARCHIVED)") String status,
            @P("Optional heuristic mode (BALANCED, SKILL_FIT_ONLY, WORKLOAD_ONLY)") String heuristicMode,
            @P("Optional workflow mode (STANDARD, SCRUM, KANBAN)") String workflowMode,
            @P("Optional start date in YYYY-MM-DD format") String startDate,
            @P("Optional end date in YYYY-MM-DD format") String endDate) {
        return projectAiTools.updateProject(projectId, name, description, status, heuristicMode, workflowMode, startDate, endDate);
    }

    @Tool("Partially update a project. Send patchData map containing changed fields (name, status, endDate, etc). Requires confirmation.")
    public Object patchProject(
            @P("The ID of the project to update") Long projectId,
            @P("Map containing only changed fields") Object patchData,
            @P("Optional reason for the change") String reason) {
        return projectAiTools.patchProject(projectId, patchData, reason);
    }

    @Tool("Join an existing project using an invitation code. Requires confirmation.")
    public Object joinProject(@P("The invitation project code") String projectCode) {
        return projectAiTools.joinProject(projectCode);
    }

    @Tool("Leave a project by project ID. Requires confirmation.")
    public Object leaveProject(@P("The ID of the project to leave") Long projectId) {
        return projectAiTools.leaveProject(projectId);
    }

    @Tool("Update a project member's role (MANAGER, MEMBER). Requires confirmation.")
    public Object updateMemberRole(
            @P("The ID of the project") Long projectId,
            @P("The ID of the target user to update role") Long targetUserId,
            @P("The new role (MANAGER, MEMBER)") String role) {
        return ahpAssignmentAiTools.updateMemberRole(projectId, targetUserId, role);
    }

    @Tool("Remove a member from a project by user ID. Requires confirmation.")
    public Object removeMember(
            @P("The ID of the project") Long projectId,
            @P("The ID of the target user to remove") Long targetUserId) {
        return ahpAssignmentAiTools.removeMember(projectId, targetUserId);
    }

    @Tool("Archive a project to make it read-only. Requires confirmation.")
    public Object archiveProject(@P("The ID of the project to archive") Long projectId) {
        return projectAiTools.archiveProject(projectId);
    }

    @Tool("Restore an archived project to active status. Requires confirmation.")
    public Object restoreProject(@P("The ID of the project to restore") Long projectId) {
        return projectAiTools.restoreProject(projectId);
    }

    @Tool("Permanently delete a project and all its data. Requires confirmation.")
    public Object deleteProject(@P("The ID of the project to delete") Long projectId) {
        return projectAiTools.deleteProject(projectId);
    }

    public Object createTask(
            @P("Optional: The project ID. If creating a subtask and project ID is not explicitly given, you should resolve it or default it based on the parent task.") Object projectId,
            @P("Title of the task. If missing or not specified, you MUST still call this tool with a null/empty title; it will automatically return the form.") String title,
            @P("Priority: LOW, MEDIUM, HIGH, or URGENT. Default to MEDIUM if not specified.") String priority,
            @P("Optional description") String description,
            @P("Optional sprint ID to place the task in") Object sprintId,
            @P("Optional task difficulty 1-10. Send as string e.g. '5'") String difficultyLevel,
            @P("Optional label ID list") Object labelIds,
            @P("Optional required skill ID list") Object requiredSkillIds,
            @P("Optional assignee user ID. IMPORTANT: Leave NULL unless explicitly requested by user. Do NOT guess or invent IDs.") Object assigneeId,
            @P("Optional start date as ISO-8601 or YYYY-MM-DD") String startDate,
            @P("Optional due date as ISO-8601 or YYYY-MM-DD") String dueDate,
            @P("Optional parent task ID if this is a subtask") Object parentId) {
        return taskAiTools.createTask(projectId, title, priority, description, sprintId, difficultyLevel, labelIds, requiredSkillIds, assigneeId, startDate, dueDate, parentId);
    }

    @Tool("Fetch the sprint backlog of a project (unscheduled tasks and sprints).")
    public Object getSprintBacklog(
            @P("The ID of the project") Object projectId,
            @P("Optional. Maximum number of tasks to return per sprint/unscheduled. Default 10, max 30.") Integer limit) {
        return sprintAiTools.getSprintBacklog(projectId, limit);
    }

    @Tool("Fetch the active sprint board for a project (tasks in the active sprint organized by column).")
    public Object getSprintBoard(
            @P("The ID of the project") Long projectId,
            @P("Optional. Maximum number of active tasks to return. Default 15, max 30.") Integer limit) {
        return sprintAiTools.getSprintBoard(projectId, limit);
    }

    @Tool("Plan and create a new sprint in a project. Requires confirmation.")
    public Object createSprint(
            @P("The project ID. If missing or not specified, you MUST still call this tool with a null/empty projectId; it will automatically return the form.") Long projectId,
            @P("Name of the sprint, e.g. 'Sprint 3'. If missing or not specified, you MUST still call this tool with a null/empty name; it will automatically return the form.") String name,
            @P("Optional start date in YYYY-MM-DD format") String startDate,
            @P("Optional end date in YYYY-MM-DD format") String endDate,
            @P("Optional goal or objective of the sprint") String goal) {
        return sprintAiTools.createSprint(projectId, name, startDate, endDate, goal);
    }

    @Tool("Update multiple fields of a planning or active sprint. CRITICAL: Use patchSprint instead if you are partially updating a sprint (like renaming it or changing dates/goal). Requires confirmation.")
    public Object updateSprint(
            @P("The project ID") Long projectId,
            @P("The ID of the sprint") Long sprintId,
            @P("Optional sprint name") String name,
            @P("Optional start date in YYYY-MM-DD format") String startDate,
            @P("Optional end date in YYYY-MM-DD format") String endDate,
            @P("Optional sprint goal") String goal) {
        return sprintAiTools.updateSprint(projectId, sprintId, name, startDate, endDate, goal);
    }

    @Tool("Partially update a sprint (e.g. rename it, change goal, or change dates). Send patchData map containing changed fields (name, goal, dates). CRITICAL: If you are changing the name, goal, or dates of a sprint, you MUST use this tool (patchSprint) instead of updateSprint! Requires confirmation.")
    public Object patchSprint(
            @P("The project ID") Long projectId,
            @P("The ID of the sprint") Long sprintId,
            @P("Map containing only changed fields") Object patchData,
            @P("Optional reason for the change") String reason) {
        return sprintAiTools.patchSprint(projectId, sprintId, patchData, reason);
    }

    @Tool("Delete a planned sprint by sprint ID and project ID. Requires confirmation.")
    public Object deleteSprint(
            @P("The project ID") Long projectId,
            @P("The ID of the sprint") Long sprintId) {
        return sprintAiTools.deleteSprint(projectId, sprintId);
    }

    @Tool("Start a planned sprint in a project. Requires confirmation.")
    public Object startSprint(
            @P("The project ID") Long projectId,
            @P("The ID of the sprint to start") Long sprintId) {
        return sprintAiTools.startSprint(projectId, sprintId);
    }

    @Tool("Mark an active sprint as completed in a project. Requires confirmation.")
    public Object completeSprint(
            @P("The project ID") Long projectId,
            @P("The ID of the sprint to complete") Long sprintId) {
        return sprintAiTools.completeSprint(projectId, sprintId);
    }

    @Tool("Move or assign a task to a sprint (or set sprintId=null to move to backlog). Requires confirmation.")
    public Object assignTaskToSprint(
            @P("The ID of the task") Long taskId,
            @P("The ID of the target sprint, or null to move it to the backlog") Long sprintId) {
        return sprintAiTools.assignTaskToSprint(taskId, sprintId);
    }

    @Tool("Execute multiple query chains in parallel. Each chain is a sequence of dependent queries. " +
          "Chains run simultaneously on separate threads for maximum speed. " +
          "Entities: projects, tasks, members, sprints, comments, workload, notifications. " +
          "Use 'ref' within a chain to reference previous step results by key name. " +
          "Use 'aggregate' for special project selection: $latest, $mostMembers, $mostTasks. " +
          "CRITICAL: Do NOT call this tool if the request involves any write/CUD operations (e.g. createTask, patchTask, createProject). You MUST call the specific CUD tool directly in the first turn.")
    public Object smartQuery(
        @P("List of query chains. Each chain is a list of sequential query steps. " +
           "Example: " +
           "[{\"steps\": [{\"key\":\"p\", \"entity\":\"projects\", \"aggregate\":\"$latest\"}, " +
           "{\"key\":\"t\", \"entity\":\"tasks\", \"ref\":{\"projectId\":\"p\"}, \"filters\":{\"dueToday\":\"true\"}}]}, " +
           "{\"steps\": [{\"key\":\"all\", \"entity\":\"projects\"}, " +
           "{\"key\":\"w\", \"entity\":\"workload\", \"ref\":{\"projectId\":\"all\"}, \"sort\":\"activeWorkloadScore DESC\", \"limit\":1}]}]") 
        List<java.util.Map> chains
    ) {
        return systemAiTools.smartQuery(chains);
    }
}
