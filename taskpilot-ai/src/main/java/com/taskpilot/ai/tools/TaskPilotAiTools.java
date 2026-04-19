package com.taskpilot.ai.tools;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskPilotAiTools {
    @Tool("Get the current status, task counts, and overall progress of a project")
    public String getProjectStatus(@P("The ID of the project to query") Long projectId) {
        log.info("[AiTool] getProjectStatus called for project {}", projectId);
        return String.format("Project ID %d: Status query not yet implemented. Please check the project dashboard.", projectId);
    }
    @Tool("Get the current workload of all project members to understand team availability")
    public String getMemberWorkload(@P("The ID of the project") Long projectId) {
        log.info("[AiTool] getMemberWorkload called for project {}", projectId);
        return String.format("Project ID %d: Member workload query not yet implemented. Please use the auto-assign endpoint.", projectId);
    }
    @Tool("Find the best team members to assign to a task based on skills and workload")
    public String findBestCandidates(
            @P("The project ID") Long projectId,
            @P("Comma-separated list of required skill names, e.g. 'Java, Spring Boot, React'") String skills) {
        log.info("[AiTool] findBestCandidates called for project {} with skills: {}", projectId, skills);
        return String.format(
                "Use the /api/v1/ai/auto-assign endpoint with projectId=%d and skills=[%s] to get AI recommendations.",
                projectId, skills
        );
    }
}

