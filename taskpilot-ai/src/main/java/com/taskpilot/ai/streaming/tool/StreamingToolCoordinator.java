package com.taskpilot.ai.streaming.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.ai.service.ToolCallingRegistryService;
import com.taskpilot.ai.streaming.sse.AiSseTransport;
import com.taskpilot.ai.tools.ToolExecutionContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.StructuredTaskScope;

/**
 * Coordinates multi-threaded parallel tool execution via Virtual Threads, loop detection, and result formatting.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamingToolCoordinator {

    public record ToolLoopState(String toolName, int consecutiveCount) {}

    private final ToolCallingRegistryService toolCallingRegistryService;
    private final AiSseTransport sseTransport;
    private final ConfirmationBlockParser confirmationParser;
    private final ObjectMapper objectMapper;

    public ToolLoopState advanceToolLoopState(String previousToolName, int previousCount, List<ToolExecutionRequest> requests) {
        String currentToolName = previousToolName;
        int currentCount = previousCount;

        for (ToolExecutionRequest request : requests) {
            String requestToolName = request.name();
            if (requestToolName != null && requestToolName.equals(currentToolName)) {
                currentCount++;
            } else {
                currentToolName = requestToolName;
                currentCount = 1;
            }
        }

        return new ToolLoopState(currentToolName, currentCount);
    }

    public List<ToolExecutionResultMessage> executeTools(
            List<ToolExecutionRequest> requests,
            SseEmitter emitter,
            List<Map<String, Object>> toolCallSummaries,
            LinkedHashSet<String> toolNames,
            Long userId,
            Long sessionId,
            String userInput,
            Collection<String> allowedTools) {

        List<ToolExecutionRequest> normalizedRequests = new ArrayList<>();
        for (ToolExecutionRequest req : requests) {
            String name = req.name();
            if (name != null && name.contains(":")) {
                name = name.substring(name.lastIndexOf(":") + 1);
            }
            if ("patchUserSkill".equals(name)) {
                name = "patchMySkill";
            }
            normalizedRequests.add(dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                    .id(req.id())
                    .name(name)
                    .arguments(req.arguments())
                    .build());
        }

        // 1. Send all start messages sequentially
        for (ToolExecutionRequest request : normalizedRequests) {
            String startMsg = "Truy cập hệ thống: " + getFriendlyToolName(request.name()) + "...\n\n";
            sseTransport.safeSend(emitter, "token", Map.of("token", startMsg), MediaType.APPLICATION_JSON);
        }

        // 2. Launch execution tasks in parallel using Java 25 StructuredTaskScope & ScopedValue
        record ToolExecutionResult(ToolExecutionRequest request, String output, Throwable error) {}

        ToolExecutionContext.Context ctx = new ToolExecutionContext.Context(userId, sessionId, userInput, allowedTools);
        List<StructuredTaskScope.Subtask<ToolExecutionResult>> subtasks = new ArrayList<>();

        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<ToolExecutionResult>awaitAll())) {
            for (ToolExecutionRequest request : normalizedRequests) {
                var subtask = scope.fork(() -> ToolExecutionContext.callWith(ctx, () -> {
                    try {
                        String output = toolCallingRegistryService.execute(request);
                        return new ToolExecutionResult(request, output, null);
                    } catch (Throwable t) {
                        log.error("[executeTools] Error executing tool {}", request.name(), t);
                        return new ToolExecutionResult(request, null, t);
                    }
                }));
                subtasks.add(subtask);
            }
            scope.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[executeTools] Interrupted while executing parallel tools", e);
        }

        // 3. Process results sequentially in original request order
        List<ToolExecutionResultMessage> results = new ArrayList<>();
        for (var subtask : subtasks) {
            ToolExecutionResult execution = subtask.get();
            ToolExecutionRequest request = execution.request();
            String output = execution.output();
            if (execution.error() != null) {
                output = "Lỗi khi truy cập hệ thống: " + execution.error().getMessage();
            }

            String endMsg = "Kết quả: " + getFriendlyToolResultSummary(request.name(), output) + "\n\n";
            sseTransport.safeSend(emitter, "token", Map.of("token", endMsg), MediaType.APPLICATION_JSON);

            Map<String, Object> eventPayload = new LinkedHashMap<>();
            eventPayload.put("name", request.name());
            eventPayload.put("arguments", request.arguments());
            eventPayload.put("result", truncate(output, 1500));
            confirmationParser.parseConfirmationPayload(output).ifPresent(confirmation -> eventPayload.put("confirmation", confirmation));

            if (output != null && output.contains("\"FORM_REQUIRED\"")) {
                try {
                    com.fasterxml.jackson.databind.JsonNode rootNode = objectMapper.readTree(output);
                    if (rootNode.has("form")) {
                        Map<String, Object> formMap = objectMapper.convertValue(rootNode.get("form"), Map.class);
                        eventPayload.put("form", formMap);
                    }
                } catch (Exception e) {
                    log.error("Failed to parse FORM_REQUIRED in executeTools: {}", e.getMessage());
                }
            } else {
                confirmationParser.buildMissingAssignmentForm(request.name(), request.arguments(), output)
                        .ifPresent(form -> eventPayload.put("form", form));
            }
            sseTransport.safeSend(emitter, "tool", eventPayload, MediaType.APPLICATION_JSON);

            toolNames.add(request.name());
            toolCallSummaries.add(eventPayload);

            results.add(ToolExecutionResultMessage.from(request, output));
        }

        return results;
    }

    public String getFriendlyToolName(String toolName) {
        if (toolName == null) return "truy vấn hệ thống";
        switch (toolName) {
            case "getMyNotifications":
            case "queryNotifications":
                return "truy xuất danh sách thông báo";
            case "getMyProjects":
            case "queryProjects":
                return "truy xuất danh sách dự án";
            case "getProjectMembers":
            case "queryProjectMembers":
                return "truy xuất danh sách thành viên dự án";
            case "getMyTasks":
            case "queryTasks":
                return "truy xuất danh sách nhiệm vụ";
            case "getTaskDetails":
                return "truy xuất chi tiết nhiệm vụ";
            case "getProjectStatus":
                return "truy xuất trạng thái dự án";
            case "getMemberWorkload":
                return "truy xuất khối lượng công việc thành viên";
            case "recommendAssignmentCandidates":
            case "recommendTaskAssignmentCandidates":
                return "phân tích và gợi ý người thực hiện nhiệm vụ";
            case "assignTaskToMember":
            case "assignTaskToMemberByName":
            case "recommendAndAssignTask":
                return "đề xuất phân công nhiệm vụ";
            default:
                return "thực thi công cụ hệ thống (" + toolName + ")";
        }
    }

    public String getFriendlyToolResultSummary(String toolName, String output) {
        if (output == null || output.isBlank()) {
            return "kết quả rỗng.";
        }
        if (output.contains("Tool execution failed") || output.contains("failed") || output.contains("Error")) {
            return "gặp lỗi hệ thống hoặc không thể thực thi.";
        }

        switch (toolName) {
            case "getMyNotifications":
            case "queryNotifications":
                return "tìm thấy dữ liệu thông báo liên quan.";
            case "getMyProjects":
            case "queryProjects":
                return "đã tải thông tin các dự án.";
            case "getProjectMembers":
            case "queryProjectMembers":
                return "đã lấy danh sách thành viên thành công.";
            case "getMyTasks":
            case "queryTasks":
                return "đã xác định các nhiệm vụ tương ứng.";
            case "getTaskDetails":
                return "đã lấy thông tin chi tiết nhiệm vụ.";
            case "recommendAssignmentCandidates":
            case "recommendTaskAssignmentCandidates":
                return "đã hoàn tất tính toán điểm số và xếp hạng ứng viên phù hợp.";
            default:
                return "nhận được kết quả phản hồi từ hệ thống.";
        }
    }

    public String generateMockAssistantResponse(List<ToolExecutionResultMessage> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return "Đã hoàn thành yêu cầu.";
        }

        StringBuilder sb = new StringBuilder();
        for (var res : toolResults) {
            String name = res.toolName();
            String text = res.text();
            if ("createProject".equals(name) || "createTask".equals(name) || "createSprint".equals(name)
                    || "createTaskComment".equals(name) || "createProjectLabel".equals(name)
                    || "addMySkill".equals(name) || "createSystemSkill".equals(name)
                    || "deleteProject".equals(name) || "deleteTask".equals(name) || "deleteSprint".equals(name)) {
                sb.append("Đã yêu cầu thực hiện thao tác ").append(name).append(". Vui lòng xác nhận.\n");
            } else if ("confirmPendingAction".equals(name)) {
                sb.append("Đã xác nhận thao tác thành công. Kết quả: ").append(text).append("\n");
            } else if ("smartQuery".equals(name)) {
                sb.append("Đã thực hiện truy vấn thông tin hệ thống thành công.\n");
            } else {
                sb.append("Đã thực hiện thao tác ").append(name).append(" thành công.\n");
            }
        }
        return sb.toString().trim();
    }

    public String formatAllToolResults(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "No prior tool execution history.";
        }
        StringBuilder sb = new StringBuilder();
        for (var msg : history) {
            if (msg instanceof ToolExecutionResultMessage toolMsg) {
                sb.append("Tool executed: ").append(toolMsg.toolName()).append("\n")
                        .append("Result: ").append(toolMsg.text()).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    public String formatToolResultsForLlama(List<ToolExecutionResultMessage> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return "No tool results available.";
        }
        StringBuilder sb = new StringBuilder();
        for (var res : toolResults) {
            sb.append("Tool: ").append(res.toolName()).append("\n")
                    .append("Result: ").append(res.text()).append("\n\n");
        }
        return sb.toString();
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
