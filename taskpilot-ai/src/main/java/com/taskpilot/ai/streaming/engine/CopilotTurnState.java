package com.taskpilot.ai.streaming.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.taskpilot.ai.entity.ChatSessionEntity;
import com.taskpilot.ai.routing.TurnAttemptTracker;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared mutable state for a single Copilot streaming turn, preserved across
 * tool rounds, key rotations, and model failovers.
 */
public class CopilotTurnState {

    public final SseEmitter emitter;
    public final AtomicBoolean emitterCompleted;
    public final ChatSessionEntity session;
    public final Long sessionId;
    public final Long userId;
    public final String userInput;
    public final String systemPrompt;
    public final long startTime;
    public final String clientMessageId;
    public final boolean requiresAHP;
    public final boolean requiresTools;
    public final List<ChatMessage> history;
    public final StringBuilder fullResponse;
    public final AtomicBoolean clientDisconnected;
    public final AtomicBoolean generatingMarked;
    public final List<Map<String, Object>> toolCallSummaries;
    public final LinkedHashSet<String> toolNames;
    public final TurnAttemptTracker tracker;
    public final TurnToolLedger ledger;
    public final AtomicBoolean modelSwitchedAfterTool = new AtomicBoolean(false);
    public final AtomicBoolean terminated = new AtomicBoolean(false);
    public final AtomicReference<String> activeModelName = new AtomicReference<>("");
    public final AtomicReference<ScheduledFuture<?>> turnDeadlineFuture = new AtomicReference<>(null);

    public CopilotTurnState(
            SseEmitter emitter,
            AtomicBoolean emitterCompleted,
            ChatSessionEntity session,
            Long sessionId,
            Long userId,
            String userInput,
            String systemPrompt,
            long startTime,
            String clientMessageId,
            boolean requiresAHP,
            boolean requiresTools,
            List<ChatMessage> history,
            StringBuilder fullResponse,
            AtomicBoolean clientDisconnected,
            AtomicBoolean generatingMarked,
            List<Map<String, Object>> toolCallSummaries,
            LinkedHashSet<String> toolNames,
            TurnAttemptTracker tracker,
            ObjectMapper objectMapper) {
        this.emitter = emitter;
        this.emitterCompleted = emitterCompleted;
        this.session = session;
        this.sessionId = sessionId;
        this.userId = userId;
        this.userInput = userInput;
        this.systemPrompt = systemPrompt;
        this.startTime = startTime;
        this.clientMessageId = clientMessageId;
        this.requiresAHP = requiresAHP;
        this.requiresTools = requiresTools;
        this.history = history;
        this.fullResponse = fullResponse;
        this.clientDisconnected = clientDisconnected;
        this.generatingMarked = generatingMarked;
        this.toolCallSummaries = toolCallSummaries;
        this.toolNames = toolNames;
        this.tracker = tracker;
        this.ledger = new TurnToolLedger(objectMapper, modelSwitchedAfterTool);
    }

    public boolean isTerminated() {
        return terminated.get();
    }

    public void cancelTurnDeadline() {
        ScheduledFuture<?> future = turnDeadlineFuture.getAndSet(null);
        if (future != null) {
            future.cancel(false);
        }
    }

    public List<Map<String, Object>> snapshotToolCallSummaries() {
        if (toolCallSummaries == null) {
            return List.of();
        }
        synchronized (toolCallSummaries) {
            return new ArrayList<>(toolCallSummaries);
        }
    }

    public LinkedHashSet<String> snapshotToolNames() {
        if (toolNames == null) {
            return new LinkedHashSet<>();
        }
        synchronized (toolNames) {
            return new LinkedHashSet<>(toolNames);
        }
    }

    public boolean hasPendingConfirmation() {
        return hasPendingConfirmation(snapshotToolCallSummaries());
    }

    public static boolean hasPendingConfirmation(List<Map<String, Object>> summaries) {
        return summaries != null && summaries.stream()
                .anyMatch(summary -> summary != null && summary.get("confirmation") instanceof Map<?, ?>);
    }

    /**
     * Prevents re-executing tools that already ran before a mid-turn model failover,
     * ensuring no duplicate write proposals or side effects.
     */
    public static class TurnToolLedger {
        private final ObjectMapper canonicalMapper;
        private final AtomicBoolean modelSwitchedAfterTool;
        private final Map<String, String> outputByExactKey = new ConcurrentHashMap<>();
        private final Map<String, String> outputByTargetKey = new ConcurrentHashMap<>();

        public TurnToolLedger(ObjectMapper baseMapper, AtomicBoolean modelSwitchedAfterTool) {
            ObjectMapper copied = baseMapper != null ? baseMapper.copy() : null;
            ObjectMapper copy = copied != null ? copied : new ObjectMapper();
            copy.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            this.canonicalMapper = copy;
            this.modelSwitchedAfterTool = modelSwitchedAfterTool;
        }

        public void record(ToolExecutionRequest request, String output, Map<String, Object> confirmation) {
            if (request == null || request.name() == null || output == null) {
                return;
            }
            outputByExactKey.put(exactKey(request), output);
            if (confirmation != null) {
                String target = targetKey(request, confirmation);
                if (target != null) {
                    outputByTargetKey.put(target, output);
                }
            }
        }

        public Optional<String> reusable(ToolExecutionRequest request) {
            if (!modelSwitchedAfterTool.get() || request == null || request.name() == null) {
                return Optional.empty();
            }
            String exact = outputByExactKey.get(exactKey(request));
            if (exact != null) {
                return Optional.of(exact);
            }
            String target = targetKey(request, null);
            if (target != null) {
                String byTarget = outputByTargetKey.get(target);
                if (byTarget != null) {
                    return Optional.of(byTarget);
                }
            }
            return Optional.empty();
        }

        public String exactKey(ToolExecutionRequest request) {
            String toolName = request.name() == null ? "" : request.name().trim();
            String args = request.arguments();
            if (args == null || args.isBlank()) {
                return toolName + "|{}";
            }
            try {
                Object tree = canonicalMapper.readValue(args, Object.class);
                return toolName + "|" + canonicalMapper.writeValueAsString(tree);
            } catch (Exception ignored) {
                return toolName + "|" + args.trim();
            }
        }

        private String targetKey(ToolExecutionRequest request, Map<String, Object> confirmation) {
            String toolName = request.name() == null ? "" : request.name().trim();
            String taskId = extractArgField(request.arguments(), "taskId");
            String projectId = extractArgField(request.arguments(), "projectId");
            if (confirmation != null) {
                if (taskId == null) taskId = extractNestedField(confirmation, "arguments", "taskId");
                if (taskId == null) taskId = extractNestedField(confirmation, "preview", "taskId");
                if (projectId == null) projectId = extractNestedField(confirmation, "arguments", "projectId");
                if (projectId == null) projectId = extractNestedField(confirmation, "preview", "projectId");
            }
            if (taskId == null && projectId == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder(toolName);
            if (taskId != null) sb.append("|task:").append(taskId);
            if (projectId != null) sb.append("|project:").append(projectId);
            return sb.toString();
        }

        private String extractArgField(String rawArgs, String field) {
            if (rawArgs == null || rawArgs.isBlank()) return null;
            try {
                JsonNode node = canonicalMapper.readTree(rawArgs);
                JsonNode val = node.get(field);
                if (val != null && !val.isNull()) {
                    String text = val.asText();
                    return text.isBlank() ? null : text;
                }
            } catch (Exception ignored) {}
            return null;
        }

        private String extractNestedField(Map<?, ?> root, String parentKey, String childKey) {
            Object parent = root.get(parentKey);
            if (parent instanceof Map<?, ?> nested) {
                Object val = nested.get(childKey);
                if (val != null) {
                    String text = String.valueOf(val).trim();
                    return text.isEmpty() ? null : text;
                }
            }
            return null;
        }
    }
}
