package com.taskpilot.ai.tools.domain;

import com.taskpilot.ai.dto.ConfirmationRequiredDto;
import com.taskpilot.ai.service.MeetingSummarizationService;
import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.contracts.aiquery.dto.TaskSummaryDto;
import com.taskpilot.contracts.aiquery.port.out.TaskCommandPort;
import com.taskpilot.contracts.workspace.meeting.MeetingDetailDto;
import com.taskpilot.contracts.workspace.meeting.MeetingListItemDto;
import com.taskpilot.contracts.workspace.meeting.MeetingSummaryDto;
import com.taskpilot.contracts.workspace.meeting.MeetingTaskCreationResultDto;
import com.taskpilot.contracts.workspace.meeting.ProjectMeetingPort;
import com.taskpilot.contracts.workspace.meeting.ScheduleMeetingCommand;
import com.taskpilot.contracts.workspace.meeting.TaskActionItemDto;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class MeetingAiTools {

    private final ProjectMeetingPort projectMeetingPort;
    private final MeetingSummarizationService meetingSummarizationService;
    private final TaskCommandPort taskCommandPort;
    private final PendingAiActionService pendingAiActionService;

    @Tool("Liệt kê danh sách các cuộc họp video trong dự án (có thể lọc theo trạng thái ACTIVE, SCHEDULED, ENDED)")
    public Object listProjectMeetings(
            @P("ID dự án cần xem cuộc họp") Long projectId,
            @P("Trạng thái cuộc họp: ACTIVE, SCHEDULED, ENDED (để trống nếu lấy tất cả)") String status,
            @P("Số lượng kết quả tối đa (mặc định 20)") Integer limit) {
        Long userId = ToolExecutionContext.requireUserId();
        log.info("[AiTool] listProjectMeetings called for user={}, projectId={}, status={}, limit={}",
                userId, projectId, status, limit);

        if (projectId == null) {
            return "Project ID is required to list meetings.";
        }

        int maxLimit = limit != null && limit > 0 ? Math.min(limit, 50) : 20;
        List<MeetingListItemDto> meetings = projectMeetingPort.listMeetings(projectId, status, maxLimit, userId);

        if (meetings.isEmpty()) {
            return "Dự án #" + projectId + " hiện chưa có cuộc họp nào"
                    + (status != null && !status.isBlank() ? " ở trạng thái " + status : "") + ".";
        }

        return meetings;
    }

    @Tool("Xem thông tin chi tiết cuộc họp (bao gồm danh sách người tham gia, liên kết ghi hình, ghi chú và tóm tắt AI)")
    public Object getMeetingDetails(
            @P("ID dự án") Long projectId,
            @P("ID cuộc họp cần xem") Long meetingId) {
        Long userId = ToolExecutionContext.requireUserId();
        log.info("[AiTool] getMeetingDetails called for user={}, projectId={}, meetingId={}",
                userId, projectId, meetingId);

        if (projectId == null) {
            return "Project ID is required.";
        }
        if (meetingId == null) {
            return "Meeting ID is required.";
        }

        try {
            return projectMeetingPort.getMeetingDetails(projectId, meetingId, userId);
        } catch (Exception e) {
            return "Không tìm thấy thông tin cuộc họp #" + meetingId + " trong dự án #" + projectId + ": " + e.getMessage();
        }
    }

    @Tool("Lên lịch một cuộc họp video mới cho dự án (yêu cầu người dùng xác nhận trước khi tạo)")
    public Object scheduleMeeting(
            @P("ID dự án cần tạo cuộc họp") Long projectId,
            @P("Tiêu đề cuộc họp") String title,
            @P("Mô tả hoặc mục tiêu cuộc họp (tùy chọn)") String description,
            @P("Thời gian bắt đầu (định dạng ISO-8601 ví dụ 2026-11-04T09:00:00Z hoặc YYYY-MM-DDTHH:mm)") String startTime,
            @P("Thời gian kết thúc (định dạng ISO-8601 hoặc YYYY-MM-DDTHH:mm, tùy chọn)") String endTime,
            @P("Bật tự động ghi hình khi phòng mở (mặc định true)") Boolean recordingEnabled) {
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        log.info("[AiTool] scheduleMeeting called for user={}, session={}, projectId={}, title='{}'",
                userId, sessionId, projectId, title);

        if (projectId == null) {
            return "Project ID is required to schedule meeting.";
        }
        if (title == null || title.isBlank()) {
            return "Tiêu đề cuộc họp không được để trống.";
        }

        Instant start = parseInstantOrFallback(startTime, Instant.now().plus(Duration.ofHours(1)));
        Instant end = parseInstantOrFallback(endTime, start.plus(Duration.ofMinutes(45)));
        boolean record = recordingEnabled != null ? recordingEnabled : true;

        ScheduleMeetingCommand command = new ScheduleMeetingCommand(
                title.trim(),
                description != null ? description.trim() : null,
                start,
                end,
                record);

        Map<String, Object> arguments = new HashMap<>();
        arguments.put("projectId", projectId);
        arguments.put("title", title);
        arguments.put("description", description);
        arguments.put("startTime", start.toString());
        arguments.put("endTime", end.toString());
        arguments.put("recordingEnabled", record);

        Map<String, Object> preview = new HashMap<>();
        preview.put("projectId", projectId);
        preview.put("title", title);
        preview.put("description", description);
        preview.put("startTime", start.toString());
        preview.put("endTime", end.toString());
        preview.put("recordingEnabled", record);

        return pendingAiActionService.create(
                userId,
                sessionId,
                "scheduleMeeting",
                "Lên lịch cuộc họp: \"" + title + "\" vào lúc " + start.toString(),
                arguments,
                preview,
                () -> projectMeetingPort.scheduleMeeting(projectId, command, userId)
        );
    }

    @Tool("Tự động tóm tắt nội dung cuộc họp từ bản ghi âm, ghi chú hoặc tin nhắn chat. Trích xuất tổng quan, quyết định then chốt, action items và câu hỏi còn tồn đọng.")
    public Object summarizeMeeting(
            @P("ID dự án") Long projectId,
            @P("ID cuộc họp cần tóm tắt") Long meetingId,
            @P("Nguồn dữ liệu ưu tiên: TRANSCRIPT, NOTES, CHAT_WINDOW (tùy chọn)") String sourceKind,
            @P("Bắt buộc tạo mới bỏ qua bản tóm tắt đã lưu trong cache (mặc định false)") Boolean forceRegenerate) {
        Long userId = ToolExecutionContext.requireUserId();
        log.info("[AiTool] summarizeMeeting called for user={}, projectId={}, meetingId={}, source={}, force={}",
                userId, projectId, meetingId, sourceKind, forceRegenerate);

        if (projectId == null) {
            return "Project ID is required.";
        }
        if (meetingId == null) {
            return "Meeting ID is required.";
        }

        try {
            return meetingSummarizationService.summarizeMeeting(
                    projectId, meetingId, sourceKind, forceRegenerate, userId);
        } catch (Exception e) {
            log.error("[AiTool] Failed to summarize meeting #{}: {}", meetingId, e.getMessage());
            return "Lỗi khi tóm tắt cuộc họp: " + e.getMessage();
        }
    }

    @Tool("Tạo hàng loạt task mới trong dự án dựa trên các action items đã được trích xuất từ cuộc họp (yêu cầu người dùng xác nhận trước khi tạo)")
    public Object createTasksFromMeetingActionItems(
            @P("ID dự án") Long projectId,
            @P("ID cuộc họp chứa các action items") Long meetingId,
            @P("Danh sách tùy biến các action items nếu người dùng muốn chỉnh sửa trước khi tạo (tùy chọn)") List<Map<String, Object>> customActionItems) {
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        log.info("[AiTool] createTasksFromMeetingActionItems called for user={}, session={}, projectId={}, meetingId={}",
                userId, sessionId, projectId, meetingId);

        if (projectId == null) {
            return "Project ID is required.";
        }
        if (meetingId == null) {
            return "Meeting ID is required.";
        }

        List<TaskActionItemDto> itemsToCreate = new ArrayList<>();
        if (customActionItems != null && !customActionItems.isEmpty()) {
            int idx = 1;
            for (Map<String, Object> map : customActionItems) {
                String title = (String) map.getOrDefault("title", "Task từ cuộc họp #" + meetingId);
                String desc = (String) map.get("description");
                String assignee = (String) map.get("assigneeName");
                Long assigneeId = map.get("assigneeId") != null ? Long.valueOf(map.get("assigneeId").toString()) : null;
                String dueDate = (String) map.get("dueDate");
                String priority = (String) map.getOrDefault("priority", "MEDIUM");
                itemsToCreate.add(new TaskActionItemDto(
                        idx++,
                        title,
                        desc,
                        assignee,
                        assigneeId,
                        assigneeId == null && assignee != null,
                        dueDate,
                        priority,
                        null));
            }
        } else {
            MeetingSummaryDto summary = meetingSummarizationService.summarizeMeeting(
                    projectId, meetingId, null, false, userId);
            if (summary != null && summary.actionItems() != null) {
                itemsToCreate.addAll(summary.actionItems());
            }
        }

        if (itemsToCreate.isEmpty()) {
            return "Không tìm thấy action items nào trong cuộc họp #" + meetingId + " để tạo task.";
        }

        Map<String, Object> arguments = Map.of(
                "projectId", projectId,
                "meetingId", meetingId,
                "actionItemCount", itemsToCreate.size()
        );

        Map<String, Object> preview = Map.of(
                "projectId", projectId,
                "meetingId", meetingId,
                "tasks", itemsToCreate
        );

        return pendingAiActionService.create(
                userId,
                sessionId,
                "createTasksFromMeetingActionItems",
                "Tạo " + itemsToCreate.size() + " task từ các action items của cuộc họp #" + meetingId,
                arguments,
                preview,
                () -> {
                    List<Map<String, Object>> created = new ArrayList<>();
                    List<Map<String, Object>> skipped = new ArrayList<>();
                    List<Map<String, Object>> failed = new ArrayList<>();

                    for (TaskActionItemDto item : itemsToCreate) {
                        try {
                            String taskDesc = (item.description() != null ? item.description() : "")
                                    + "\n\n(Tạo tự động từ cuộc họp #" + meetingId + ")";
                            TaskSummaryDto createdTask = taskCommandPort.createTask(
                                    projectId,
                                    item.title(),
                                    taskDesc.trim(),
                                    item.priority() != null ? item.priority() : "MEDIUM",
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    item.assigneeId(),
                                    null,
                                    item.dueDate(),
                                    userId
                            );
                            created.add(Map.of("taskId", createdTask.id(), "title", createdTask.title()));
                        } catch (Exception e) {
                            log.error("[AiTool] Failed to create task for action item '{}': {}", item.title(), e.getMessage());
                            failed.add(Map.of("title", item.title(), "error", e.getMessage()));
                        }
                    }
                    return new MeetingTaskCreationResultDto(created, skipped, failed);
                }
        );
    }

    private Instant parseInstantOrFallback(String str, Instant fallback) {
        if (str == null || str.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(str.trim());
        } catch (Exception ignored) {}

        try {
            LocalDateTime ldt = LocalDateTime.parse(str.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return ldt.atZone(ZoneId.systemDefault()).toInstant();
        } catch (Exception ignored) {}

        try {
            LocalDate ld = LocalDate.parse(str.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
            return ld.atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (Exception ignored) {}

        return fallback;
    }
}
