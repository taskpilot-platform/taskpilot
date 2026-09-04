package com.taskpilot.ai.streaming.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses tool output payloads for confirmation dialogues and dynamic interactive forms.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmationBlockParser {

    private static final Pattern RECORD_CONFIRMATION_PATTERN = Pattern.compile(
            "confirmationRequired\\s*=\\s*true.*?actionId\\s*=\\s*([^,\\]\\s]+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern RECORD_TOOL_NAME_PATTERN = Pattern.compile(
            "toolName\\s*=\\s*([^,\\]\\s]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RECORD_SUMMARY_PATTERN = Pattern.compile(
            "summary\\s*=\\s*(.*?)(?:,\\s*arguments=|,\\s*preview=|,\\s*expiresAt=|\\])",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public Optional<Map<String, Object>> parseConfirmationPayload(String rawToolOutput) {
        if (rawToolOutput == null || rawToolOutput.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(rawToolOutput, MAP_TYPE);
            Object confirmationRequired = parsed.get("confirmationRequired");
            Object actionId = parsed.get("actionId");
            if (Boolean.TRUE.equals(confirmationRequired) && actionId instanceof String actionIdText
                    && !actionIdText.isBlank()) {
                return Optional.of(parsed);
            }
        } catch (Exception ex) {
            log.debug("[HumanInLoop] Tool output is not a JSON confirmation payload: {}", ex.getMessage());
        }

        Matcher recordMatcher = RECORD_CONFIRMATION_PATTERN.matcher(rawToolOutput);
        if (recordMatcher.find()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("confirmationRequired", true);
            fallback.put("actionId", recordMatcher.group(1));
            Matcher toolMatcher = RECORD_TOOL_NAME_PATTERN.matcher(rawToolOutput);
            if (toolMatcher.find()) {
                fallback.put("toolName", toolMatcher.group(1));
            }
            Matcher summaryMatcher = RECORD_SUMMARY_PATTERN.matcher(rawToolOutput);
            if (summaryMatcher.find()) {
                fallback.put("summary", summaryMatcher.group(1).trim());
            }
            return Optional.of(fallback);
        }
        return Optional.empty();
    }

    public Optional<Map<String, Object>> buildMissingAssignmentForm(String toolName, String rawArguments, String rawToolOutput) {
        if (!"recommendAndAssignTask".equals(toolName) || rawToolOutput == null) {
            return Optional.empty();
        }
        String normalized = rawToolOutput.toLowerCase(Locale.ROOT);
        if (!normalized.contains("missing required skills") && !normalized.contains("missing skills")) {
            return Optional.empty();
        }

        Long taskId = null;
        try {
            Map<String, Object> args = objectMapper.readValue(rawArguments, MAP_TYPE);
            Object rawTaskId = args.get("taskId");
            if (rawTaskId instanceof Number number) {
                taskId = number.longValue();
            } else if (rawTaskId instanceof String text && !text.isBlank()) {
                taskId = Long.valueOf(text);
            }
        } catch (Exception ex) {
            log.debug("[AiForm] Could not parse tool arguments for missing assignment form: {}", ex.getMessage());
        }

        Map<String, Object> difficultyField = new LinkedHashMap<>();
        difficultyField.put("name", "difficulty");
        difficultyField.put("label", "Độ khó (1-10)");
        difficultyField.put("type", "number");
        difficultyField.put("required", true);
        difficultyField.put("min", 1);
        difficultyField.put("max", 10);
        difficultyField.put("placeholder", "5");

        Map<String, Object> skillsField = new LinkedHashMap<>();
        skillsField.put("name", "skills");
        skillsField.put("label", "Kỹ năng yêu cầu");
        skillsField.put("type", "select");
        skillsField.put("required", true);
        skillsField.put("placeholder", "Chọn skill từ hệ thống");

        Map<String, Object> form = new LinkedHashMap<>();
        form.put("title", taskId == null ? "Bổ sung skill để phân công task" : "Bổ sung skill để phân công Task " + taskId);
        form.put("description", "Task chưa có kỹ năng yêu cầu. Chọn skill phù hợp từ danh mục hệ thống rồi tiếp tục phân công.");
        form.put("submitLabel", "Tiếp tục phân công");
        form.put("intent", taskId == null ? "assign_task_missing_skills" : "assign_task_" + taskId);
        form.put("fields", List.of(difficultyField, skillsField));
        return Optional.of(form);
    }

    public String appendTaskPilotBlocks(String responseText, List<Map<String, Object>> toolCallSummaries) {
        if (toolCallSummaries == null || toolCallSummaries.isEmpty()) {
            return responseText;
        }

        List<String> blocks = new ArrayList<>();
        Map<String, Map<?, ?>> latestConfirmations = new LinkedHashMap<>();
        for (Map<String, Object> summary : toolCallSummaries) {
            Object confirmation = summary.get("confirmation");
            if (confirmation instanceof Map<?, ?> confirmationMap) {
                latestConfirmations.put(confirmationBlockKey(confirmationMap), confirmationMap);
            }

            Object form = summary.get("form");
            if (form instanceof Map<?, ?> formMap) {
                try {
                    blocks.add("```taskpilot-form\n"
                            + objectMapper.writeValueAsString(formMap)
                            + "\n```");
                } catch (Exception ex) {
                    log.warn("[AiForm] Failed to serialize dynamic form metadata: {}", ex.getMessage());
                }
            }
        }

        for (Map<?, ?> confirmationMap : latestConfirmations.values()) {
            try {
                blocks.add("```taskpilot-confirm\n"
                        + objectMapper.writeValueAsString(confirmationMap)
                        + "\n```");
            } catch (Exception ex) {
                log.warn("[HumanInLoop] Failed to serialize pending action metadata: {}", ex.getMessage());
            }
        }

        if (blocks.isEmpty()) {
            return responseText;
        }

        String visibleText = responseText == null ? "" : responseText.trim();
        return (visibleText + "\n\n" + String.join("\n\n", blocks)).trim();
    }

    public String confirmationBlockKey(Map<?, ?> confirmation) {
        Object toolName = confirmation.get("toolName");
        Object actionId = confirmation.get("actionId");
        Object taskId = nestedValue(confirmation, "arguments", "taskId");
        if (taskId == null) {
            taskId = nestedValue(confirmation, "preview", "taskId");
        }
        Object projectId = nestedValue(confirmation, "arguments", "projectId");
        if (projectId == null) {
            projectId = nestedValue(confirmation, "preview", "projectId");
        }
        StringBuilder key = new StringBuilder(String.valueOf(toolName == null ? "pendingAction" : toolName));
        if (taskId != null) {
            key.append("|task:").append(taskId);
        }
        if (projectId != null) {
            key.append("|project:").append(projectId);
        }
        return key.length() > 0 ? key.toString() : String.valueOf(actionId);
    }

    public Object nestedValue(Map<?, ?> source, String parentKey, String childKey) {
        Object parent = source.get(parentKey);
        if (parent instanceof Map<?, ?> nested) {
            return nested.get(childKey);
        }
        return null;
    }
}
