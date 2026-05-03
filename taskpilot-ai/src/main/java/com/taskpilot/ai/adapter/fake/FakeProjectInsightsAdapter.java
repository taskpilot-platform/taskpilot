package com.taskpilot.ai.adapter.fake;

import com.taskpilot.contracts.aiquery.dto.ProjectMemberDto;
import com.taskpilot.contracts.aiquery.dto.ProjectStatusDto;
import com.taskpilot.contracts.aiquery.port.out.ProjectInsightsPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@Profile("ai-dev")
public class FakeProjectInsightsAdapter implements ProjectInsightsPort {

    @Override
    public ProjectStatusDto getProjectStatus(Long projectId) {
        log.info("[FakeAdapter] getProjectStatus called for projectId={}", projectId);
        if (projectId % 2 == 0) {
            return ScenarioFixtures.getProjectStatusAtRisk(projectId);
        }
        return ScenarioFixtures.getProjectStatusOnTrack(projectId);
    }

    @Override
    public List<ProjectMemberDto> getProjectMembers(Long projectId) {
        log.info("[FakeAdapter] getProjectMembers called for projectId={}", projectId);
        return ScenarioFixtures.getProjectMembers(projectId);
    }
}
