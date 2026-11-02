package com.taskpilot.ai.tools;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskPilotAiToolsTest {

    @Test
    void toolSpecificationsFromTaskPilotAiTools_containsCreateTask() {
        List<ToolSpecification> specs = ToolSpecifications.toolSpecificationsFrom(TaskPilotAiTools.class);
        assertTrue(specs.stream().anyMatch(s -> "createTask".equals(s.name())),
                "TaskPilotAiTools must expose createTask as an @Tool specification");
    }
}
