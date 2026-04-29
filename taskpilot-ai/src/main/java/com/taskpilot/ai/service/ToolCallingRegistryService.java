package com.taskpilot.ai.service;

import com.taskpilot.ai.tools.TaskPilotAiTools;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolCallingRegistryService {

    private final TaskPilotAiTools taskPilotAiTools;

    private List<ToolSpecification> toolSpecifications;
    private Map<String, ToolExecutor> toolExecutors;

    @PostConstruct
    void init() {
        ToolService toolService = new ToolService();
        toolService.tools(List.of(taskPilotAiTools));

        this.toolSpecifications = List.copyOf(toolService.toolSpecifications());
        this.toolExecutors = Map.copyOf(toolService.toolExecutors());

        log.info("[AI Tools] Registered {} tool specifications", this.toolSpecifications.size());
    }

    public List<ToolSpecification> toolSpecifications() {
        return toolSpecifications;
    }

    public List<ToolSpecification> toolSpecificationsByName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return List.of();
        }
        return toolSpecifications.stream()
                .filter(spec -> toolName.equals(spec.name()))
                .collect(Collectors.toList());
    }

    public String execute(ToolExecutionRequest request) {
        ToolExecutor executor = toolExecutors.get(request.name());
        if (executor == null) {
            return "Tool not available: " + request.name();
        }

        try {
            return executor.execute(request, null);
        } catch (Exception ex) {
            log.error("[AI Tools] Tool execution failed for {}: {}", request.name(), ex.getMessage(), ex);
            return "Tool execution failed: " + ex.getMessage();
        }
    }
}
