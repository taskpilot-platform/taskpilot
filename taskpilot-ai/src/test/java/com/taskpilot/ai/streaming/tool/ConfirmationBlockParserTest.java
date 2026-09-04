package com.taskpilot.ai.streaming.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ConfirmationBlockParserTest {

    private ConfirmationBlockParser parser;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        parser = new ConfirmationBlockParser(objectMapper);
    }

    @Test
    void testParseConfirmationPayloadFromJson() {
        String json = "{\"confirmationRequired\":true,\"actionId\":\"act-1234\",\"toolName\":\"createTask\"}";
        Optional<Map<String, Object>> result = parser.parseConfirmationPayload(json);

        assertTrue(result.isPresent());
        assertEquals(true, result.get().get("confirmationRequired"));
        assertEquals("act-1234", result.get().get("actionId"));
        assertEquals("createTask", result.get().get("toolName"));
    }

    @Test
    void testParseConfirmationPayloadFromRecordString() {
        String recordString = "PendingActionResponse[confirmationRequired=true, actionId=act-999, toolName=deleteProject, summary=Xóa dự án]";
        Optional<Map<String, Object>> result = parser.parseConfirmationPayload(recordString);

        assertTrue(result.isPresent());
        assertEquals(true, result.get().get("confirmationRequired"));
        assertEquals("act-999", result.get().get("actionId"));
        assertEquals("deleteProject", result.get().get("toolName"));
    }

    @Test
    void testBuildMissingAssignmentForm() {
        String toolName = "recommendAndAssignTask";
        String rawArgs = "{\"taskId\": 75}";
        String rawOutput = "Missing required skills for task 75";

        Optional<Map<String, Object>> form = parser.buildMissingAssignmentForm(toolName, rawArgs, rawOutput);
        assertTrue(form.isPresent());
        assertEquals("Bổ sung skill để phân công Task 75", form.get().get("title"));
    }

    @Test
    void testAppendTaskPilotBlocks() {
        Map<String, Object> confirmation = Map.of(
                "actionId", "act-1",
                "toolName", "createTask",
                "summary", "Tạo task mới"
        );
        Map<String, Object> summary = Map.of("confirmation", confirmation);

        String result = parser.appendTaskPilotBlocks("Tôi sẽ tạo task.", List.of(summary));
        assertTrue(result.contains("```taskpilot-confirm"));
        assertTrue(result.contains("act-1"));
        assertTrue(result.startsWith("Tôi sẽ tạo task."));
    }
}
