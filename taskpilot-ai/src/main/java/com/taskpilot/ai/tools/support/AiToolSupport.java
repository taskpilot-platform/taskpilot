package com.taskpilot.ai.tools.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.contracts.aiquery.dto.ProjectMemberDto;
import com.taskpilot.contracts.aiquery.port.out.ProjectInsightsPort;
import lombok.extern.slf4j.Slf4j;

import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public final class AiToolSupport {

    public static final ObjectMapper PATCH_OBJECT_MAPPER = com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .build();

    private AiToolSupport() {}
    public static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String s = value.toString().trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s) || "<null>".equalsIgnoreCase(s) || "undefined".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            log.warn("[AiTool] Failed to parse Long from: {}", s);
            return null;
        }
    }

    public static List<Long> toLongList(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(AiToolSupport::toLong)
                    .filter(item -> item != null)
                    .toList();
        }
        String s = value.toString().trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s) || "<null>".equalsIgnoreCase(s) || "undefined".equalsIgnoreCase(s)) {
            return null;
        }
        return Arrays.stream(s.split(","))
                .map(AiToolSupport::toLong)
                .filter(item -> item != null)
                .toList();
    }

    public static List<String> parseSkills(String skills) {
        if (skills == null || skills.isBlank()) {
            return List.of();
        }
        return Arrays.stream(skills.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }

    public static Set<Long> parseIdSet(String ids) {
        if (!hasText(ids)) {
            return new java.util.LinkedHashSet<>();
        }
        return Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(AiToolSupport::hasText)
                .map(AiToolSupport::toLong)
                .filter(id -> id != null)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    public static Set<Long> resolveProjectMemberIdsByNames(ProjectInsightsPort port, Long projectId, String memberNames, Long userId) {
        if (!hasText(memberNames)) {
            return new java.util.LinkedHashSet<>();
        }
        return Arrays.stream(memberNames.split(","))
                .map(String::trim)
                .filter(AiToolSupport::hasText)
                .map(name -> resolveProjectMemberByName(port, projectId, name, userId).memberId())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    public static boolean isTruthy(String value) {
        if (!hasText(value)) {
            return false;
        }
        String normalized = normalizeName(value);
        return Set.of("true", "yes", "y", "1", "co", "ok", "exclude", "loai").contains(normalized);
    }

    public static ProjectMemberDto resolveProjectMemberByName(ProjectInsightsPort port, Long projectId, String memberName, Long userId) {
        if (!hasText(memberName)) {
            throw new IllegalArgumentException("Member name is required");
        }
        String target = normalizeName(memberName);
        List<ProjectMemberDto> members = port.getProjectMembers(projectId, userId);
        return members.stream()
                .filter(member -> normalizeName(member.fullName()).equals(target))
                .findFirst()
                .or(() -> members.stream()
                        .filter(member -> normalizeName(member.fullName()).contains(target)
                                || target.contains(normalizeName(member.fullName())))
                        .findFirst())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No project member matched name '" + memberName + "' in project " + projectId));
    }

    public static String normalizeName(String text) {
        if (text == null) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT)
                .replace('\u0111', 'd')
                .replace('\u0110', 'd');
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    // =========================================================================
    // Project/task CRUD tools backed by the real project data ports.
    // =========================================================================


    public static boolean isCurrentUserConfirming(String actionId) {
        if (!hasText(actionId)) {
            return false;
        }
        String input = normalize(ToolExecutionContext.userInput());
        return input.contains(normalize(actionId))
                && isConfirmationInput(input);
    }

    public static boolean isCurrentUserConfirming() {
        return isConfirmationInput(normalize(ToolExecutionContext.userInput()));
    }

    public static boolean isConfirmationInput(String input) {
        if (input.matches("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$") || input.startsWith("confirm_action")) {
            return true;
        }
        return input.contains("confirm") || input.contains("confirmed")
                || input.contains("xac nhan") || input.contains("dong y")
                || input.contains("thuc hien") || input.contains("apply")
                || input.contains("ok") || input.contains("yes") || input.contains("approve");
    }



    public static void validatePatchField(String fieldName, Set<String> allowedFields) {
        if (!allowedFields.contains(fieldName)) {
            throw new IllegalArgumentException("Unsupported patch field: " + fieldName);
        }
    }

    public static int clampSkillLevel(Integer level) {
        int rawLevel = level != null ? level : 1;
        return Math.max(1, Math.min(5, rawLevel));
    }

    public static String stringPatchValue(Map<String, Object> patch, String fieldName) {
        Object value = patch.get(fieldName);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) || "<null>".equalsIgnoreCase(text) || "undefined".equalsIgnoreCase(text) ? null : text;
    }

    public static String cleanString(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) || "<null>".equalsIgnoreCase(text) || "undefined".equalsIgnoreCase(text) ? null : text;
    }

    public static Long longPatchValue(Map<String, Object> patch, String fieldName) {
        return toLong(patch.get(fieldName));
    }

    public static Integer integerPatchValue(Map<String, Object> patch, String fieldName) {
        Object value = patch.get(fieldName);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer value for task patch field: " + fieldName, e);
        }
    }

    public static Float floatPatchValue(Map<String, Object> patch, String fieldName) {
        Object value = patch.get(fieldName);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.floatValue();
        }
        try {
            return Float.valueOf(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number value for task patch field: " + fieldName, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Long> longListPatchValue(Map<String, Object> patch, String fieldName) {
        Object value = patch.get(fieldName);
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(AiToolSupport::toLong)
                    .filter(item -> item != null)
                    .toList();
        }
        return Arrays.stream(value.toString().split(","))
                .map(AiToolSupport::toLong)
                .filter(item -> item != null)
                .toList();
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String lower = value.toLowerCase(Locale.ROOT).replace('đ', 'd');
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    public static Map<String, Object> args(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                result.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalizePatch(Object patchData) {
        if (patchData == null) return java.util.Collections.emptyMap();
        if (patchData instanceof Map) {
            return (Map<String, Object>) patchData;
        }
        if (patchData instanceof String strData) {
            String trimmed = strData.trim();
            if (trimmed.isEmpty() || trimmed.equals("{}")) return java.util.Collections.emptyMap();
            try {
                if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 1) {
                    try {
                        Object unquoted = PATCH_OBJECT_MAPPER.readValue(trimmed, Object.class);
                        if (unquoted instanceof String s) trimmed = s.trim();
                        else if (unquoted instanceof Map) return (Map<String, Object>) unquoted;
                    } catch (Exception ignored) {}
                }
                Object parsed = PATCH_OBJECT_MAPPER.readValue(trimmed, Object.class);
                if (parsed instanceof Map) {
                    return (Map<String, Object>) parsed;
                } else if (parsed instanceof String innerStr) {
                    return PATCH_OBJECT_MAPPER.readValue(innerStr, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                }
            } catch (Exception e) {
                log.warn("Failed to parse patch string: {}", patchData, e);
                throw new IllegalArgumentException("Invalid patch data format. Must be valid JSON object.", e);
            }
        }
        throw new IllegalArgumentException("Invalid patch data type: " + (patchData != null ? patchData.getClass().getSimpleName() : "null"));
    }


}
