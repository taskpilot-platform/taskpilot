package com.taskpilot.contracts.task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable presence-aware task mutation command shared across REST, Kanban, and AI channels.
 * {@code presentFields} must match {@code values.keySet()} exactly, including keys mapped to {@code null}.
 */
public record PatchTaskCommand(
        long taskId,
        Long expectedTaskVersion,
        UUID requestId,
        Set<String> presentFields,
        Map<String, Object> values
) {

    public static final Set<String> ALLOWED_FIELDS = Set.of(
            "title",
            "description",
            "status",
            "priority",
            "position",
            "labelIds",
            "difficultyLevel",
            "requiredSkillIds",
            "assigneeId",
            "startDate",
            "dueDate",
            "sprintId"
    );

    public static final Set<String> NON_NULLABLE_FIELDS = Set.of(
            "title",
            "status",
            "priority",
            "position",
            "difficultyLevel"
    );

    public static final Set<String> COLLECTION_FIELDS = Set.of(
            "labelIds",
            "requiredSkillIds"
    );

    public PatchTaskCommand {
        if (taskId <= 0) {
            throw new IllegalArgumentException("taskId must be positive");
        }
        Set<String> copiedPresent = presentFields == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(presentFields));
        Map<String, Object> copiedValues = new LinkedHashMap<>();
        if (values != null) {
            copiedValues.putAll(values);
        }
        for (String key : copiedPresent) {
            if (key == null || !ALLOWED_FIELDS.contains(key)) {
                throw new IllegalArgumentException("Unsupported or forbidden task patch field: " + key);
            }
        }
        for (String key : copiedValues.keySet()) {
            if (key == null || !ALLOWED_FIELDS.contains(key)) {
                throw new IllegalArgumentException("Unsupported or forbidden task patch field: " + key);
            }
        }
        if (!copiedPresent.equals(copiedValues.keySet())) {
            throw new IllegalArgumentException("presentFields must exactly match values keys");
        }
        presentFields = copiedPresent;
        values = Collections.unmodifiableMap(copiedValues);
    }

    public static PatchTaskCommand fromValues(
            long taskId,
            Long expectedTaskVersion,
            UUID requestId,
            Map<String, Object> values
    ) {
        Map<String, Object> safeValues = values == null ? Map.of() : new LinkedHashMap<>(values);
        return new PatchTaskCommand(
                taskId,
                expectedTaskVersion,
                requestId,
                safeValues.keySet(),
                safeValues
        );
    }

    public boolean isPresent(String field) {
        return presentFields.contains(field);
    }

    public Object valueOf(String field) {
        return values.get(field);
    }
}
