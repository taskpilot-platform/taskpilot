package com.taskpilot.ai.streaming.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.ai.service.ToolCallingRegistryService;
import com.taskpilot.ai.streaming.engine.CopilotTurnState;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.BooleanSupplier;

/**
 * Coordinates multi-threaded parallel tool execution via Virtual Threads, loop detection, and result formatting.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamingToolCoordinator {

    public record ToolLoopState(String toolName, int consecutiveCount) {}

    public static class ToolDeadlineExceededException extends RuntimeException {
        public ToolDeadlineExceededException(String message) {
            super(message);
        }

        public ToolDeadlineExceededException(String message, Throwable cause) {
            super(message, cause);
        }
    }

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
        return executeTools(requests, emitter, toolCallSummaries, toolNames,
                userId, sessionId, userInput, allowedTools, null);
    }

    public List<ToolExecutionResultMessage> executeTools(
            List<ToolExecutionRequest> requests,
            SseEmitter emitter,
            List<Map<String, Object>> toolCallSummaries,
            LinkedHashSet<String> toolNames,
            Long userId,
            Long sessionId,
            String userInput,
            Collection<String> allowedTools,
            CopilotTurnState.TurnToolLedger ledger) {
        return executeTools(requests, emitter, toolCallSummaries, toolNames,
                userId, sessionId, userInput, allowedTools, ledger, null);
    }

    public List<ToolExecutionResultMessage> executeTools(
            List<ToolExecutionRequest> requests,
            SseEmitter emitter,
            List<Map<String, Object>> toolCallSummaries,
            LinkedHashSet<String> toolNames,
            Long userId,
            Long sessionId,
            String userInput,
            Collection<String> allowedTools,
            CopilotTurnState.TurnToolLedger ledger,
            Duration turnRemainingTimeout) {
        return executeTools(requests, emitter, toolCallSummaries, toolNames,
                userId, sessionId, userInput, allowedTools, ledger, turnRemainingTimeout, () -> false);
    }

    public List<ToolExecutionResultMessage> executeTools(
            List<ToolExecutionRequest> requests,
            SseEmitter emitter,
            List<Map<String, Object>> toolCallSummaries,
            LinkedHashSet<String> toolNames,
            Long userId,
            Long sessionId,
            String userInput,
            Collection<String> allowedTools,
            CopilotTurnState.TurnToolLedger ledger,
            Duration turnRemainingTimeout,
            BooleanSupplier turnTerminated) {

        BooleanSupplier isTerminated = turnTerminated != null ? turnTerminated : () -> false;
        if (isTerminated.getAsBoolean() || (turnRemainingTimeout != null && !turnRemainingTimeout.isPositive())) {
            throw new ToolDeadlineExceededException("Turn deadline already reached before tool execution");
        }

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

        // Partition into reusable (cached after mid-turn model switch) vs fresh executions
        Map<Integer, String> reusedOutputs = new LinkedHashMap<>();
        List<Integer> toExecuteIndices = new ArrayList<>();
        for (int i = 0; i < normalizedRequests.size(); i++) {
            ToolExecutionRequest request = normalizedRequests.get(i);
            var reused = ledger != null ? ledger.reusable(request) : java.util.Optional.<String>empty();
            if (reused.isPresent()) {
                reusedOutputs.put(i, reused.get());
                log.info("[CopilotChain] reuse tool={} key={}", request.name(), ledger.exactKey(request));
            } else {
                toExecuteIndices.add(i);
                if (!isTerminated.getAsBoolean()) {
                    String startMsg = "Truy cập hệ thống: " + getFriendlyToolName(request.name()) + "...\n\n";
                    sseTransport.safeSend(emitter, "token", Map.of("token", startMsg), MediaType.APPLICATION_JSON);
                }
            }
        }

        // 2. Launch fresh execution tasks in parallel using Java 25 StructuredTaskScope & ScopedValue
        record ToolExecutionResult(ToolExecutionRequest request, String output, Throwable error) {}

        Map<Integer, ToolExecutionResult> executedByIndex = new LinkedHashMap<>();
        java.util.Set<Integer> publishedConfirmations = java.util.concurrent.ConcurrentHashMap.newKeySet();
        if (!toExecuteIndices.isEmpty()) {
            if (isTerminated.getAsBoolean()) {
                throw new ToolDeadlineExceededException("Turn already terminated before launching tools");
            }
            ToolExecutionContext.Context ctx = new ToolExecutionContext.Context(userId, sessionId, userInput, allowedTools);
            List<StructuredTaskScope.Subtask<ToolExecutionResult>> subtasks = new ArrayList<>();

            boolean timedOut = false;
            StructuredTaskScope.TimeoutException timeoutEx = null;
            try (var scope = turnRemainingTimeout != null
                    ? StructuredTaskScope.open(
                            StructuredTaskScope.Joiner.<ToolExecutionResult>awaitAll(),
                            cfg -> cfg.withTimeout(turnRemainingTimeout))
                    : StructuredTaskScope.open(StructuredTaskScope.Joiner.<ToolExecutionResult>awaitAll())) {
                for (int idx : toExecuteIndices) {
                    ToolExecutionRequest request = normalizedRequests.get(idx);
                    var subtask = scope.fork(() -> ToolExecutionContext.callWith(ctx, () -> {
                        try {
                            String output = toolCallingRegistryService.execute(request);
                            Map<String, Object> confirmation = confirmationParser.parseConfirmationPayload(output).orElse(null);
                            if (confirmation != null) {
                                // Publish proposals before waiting for unrelated tools in this batch.
                                synchronized (toolCallSummaries) {
                                    if (!isTerminated.getAsBoolean()) {
                                        Map<String, Object> payload = new LinkedHashMap<>();
                                        payload.put("name", request.name());
                                        payload.put("arguments", request.arguments());
                                        payload.put("result", truncate(output, 1500));
                                        payload.put("confirmation", confirmation);
                                        synchronized (toolNames) {
                                            toolNames.add(request.name());
                                        }
                                        toolCallSummaries.add(payload);
                                        if (ledger != null) {
                                            ledger.record(request, output, confirmation);
                                        }
                                        publishedConfirmations.add(idx);
                                        sseTransport.safeSend(emitter, "tool", payload, MediaType.APPLICATION_JSON);
                                    }
                                }
                            }
                            return new ToolExecutionResult(request, output, null);
                        } catch (Throwable t) {
                            log.error("[executeTools] Error executing tool {}", request.name(), t);
                            return new ToolExecutionResult(request, null, t);
                        }
                    }));
                    subtasks.add(subtask);
                }
                scope.join();
            } catch (StructuredTaskScope.TimeoutException te) {
                timedOut = true;
                timeoutEx = te;
                log.warn("[executeTools] Tool execution exceeded remaining turn deadline ({}ms) for session {}",
                        turnRemainingTimeout.toMillis(), sessionId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[executeTools] Interrupted while executing parallel tools", e);
            }

            // If the independent turn deadline timer already closed SSE while tools were finishing/cleaning up,
            // do not emit any late SSE events or mutate turn state.
            if (isTerminated.getAsBoolean()) {
                log.warn("[executeTools] Ignoring late tool completion after turn already terminated for session {}", sessionId);
                throw new ToolDeadlineExceededException("Turn already terminated while tools were executing", timeoutEx);
            }

            if (timedOut) {
                throw new ToolDeadlineExceededException(
                        "Tool execution exceeded remaining turn deadline (" + turnRemainingTimeout.toMillis() + "ms)",
                        timeoutEx);
            }

            for (int j = 0; j < toExecuteIndices.size(); j++) {
                var subtask = subtasks.get(j);
                ToolExecutionRequest request = normalizedRequests.get(toExecuteIndices.get(j));
                if (subtask.state() == StructuredTaskScope.Subtask.State.SUCCESS) {
                    executedByIndex.put(toExecuteIndices.get(j), subtask.get());
                } else {
                    executedByIndex.put(toExecuteIndices.get(j),
                            new ToolExecutionResult(request, null, new InterruptedException("Tool execution did not complete")));
                }
            }
        }

        // 3. Process results sequentially in original request order
        List<ToolExecutionResultMessage> results = new ArrayList<>();
        for (int i = 0; i < normalizedRequests.size(); i++) {
            if (isTerminated.getAsBoolean()) {
                log.warn("[executeTools] Stopping tool result emission because turn terminated for session {}", sessionId);
                throw new ToolDeadlineExceededException("Turn terminated while emitting tool results");
            }
            ToolExecutionRequest request = normalizedRequests.get(i);
            if (reusedOutputs.containsKey(i)) {
                results.add(ToolExecutionResultMessage.from(request, reusedOutputs.get(i)));
                continue;
            }
            ToolExecutionResult execution = executedByIndex.get(i);
            if (publishedConfirmations.contains(i)) {
                results.add(ToolExecutionResultMessage.from(request, execution.output()));
                continue;
            }
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
            Map<String, Object> parsedConfirmation = confirmationParser.parseConfirmationPayload(output).orElse(null);
            if (parsedConfirmation != null) {
                eventPayload.put("confirmation", parsedConfirmation);
            }

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
            if (isTerminated.getAsBoolean()) {
                throw new ToolDeadlineExceededException("Turn terminated before sending tool event");
            }
            sseTransport.safeSend(emitter, "tool", eventPayload, MediaType.APPLICATION_JSON);

            synchronized (toolNames) {
                toolNames.add(request.name());
            }
            synchronized (toolCallSummaries) {
                toolCallSummaries.add(eventPayload);
            }

            if (ledger != null && execution.error() == null) {
                ledger.record(request, output, parsedConfirmation);
            }

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
                sb.append("Chưa thực hiện thao tác. Hãy dùng nút xác nhận trong ứng dụng. Chi tiết: ").append(text).append("\n");
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
