package com.taskpilot.contracts.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PatchTaskCommandTest {

    @Test
    void preservesExplicitNullAndTracksPresenceExactly() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("description", null);
        raw.put("dueDate", null);
        raw.put("title", "Updated title");

        UUID requestId = UUID.randomUUID();
        PatchTaskCommand command = PatchTaskCommand.fromValues(42L, 7L, requestId, raw);

        assertEquals(Set.of("description", "dueDate", "title"), command.presentFields());
        assertEquals(command.presentFields(), command.values().keySet());
        assertTrue(command.isPresent("description"));
        assertTrue(command.isPresent("dueDate"));
        assertFalse(command.isPresent("assigneeId"));
        assertNull(command.valueOf("description"));
        assertNull(command.valueOf("dueDate"));
        assertEquals("Updated title", command.valueOf("title"));
        assertEquals(7L, command.expectedTaskVersion());
        assertEquals(requestId, command.requestId());

        assertThrows(UnsupportedOperationException.class, () -> command.presentFields().add("sprintId"));
        assertThrows(UnsupportedOperationException.class, () -> command.values().put("sprintId", null));
    }

    @Test
    void rejectsPresentFieldMismatchAndForbiddenMassAssignmentKeys() {
        Map<String, Object> mismatch = new LinkedHashMap<>();
        mismatch.put("title", "Valid");
        assertThrows(IllegalArgumentException.class,
                () -> new PatchTaskCommand(1L, 0L, null, Set.of("title", "dueDate"), mismatch));

        for (String forbidden : Set.of("reviewerId", "qualityRating", "evaluationId", "role", "reporterId", "version", "updatedAt")) {
            Map<String, Object> payload = Map.of(forbidden, 999);
            assertThrows(IllegalArgumentException.class,
                    () -> PatchTaskCommand.fromValues(1L, 0L, null, payload),
                    "Should reject forbidden field: " + forbidden);
        }
    }
}
