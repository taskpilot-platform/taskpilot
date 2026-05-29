package com.taskpilot.ai.adapter.fake;

import com.taskpilot.contracts.aiquery.dto.ProjectMemberDto;
import com.taskpilot.contracts.aiquery.dto.ProjectOverviewDto;
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
    public List<ProjectOverviewDto> getMyProjects(Long requesterUserId) {
        log.info("[FakeAdapter] getMyProjects called for userId={}", requesterUserId);
        return List.of(new ProjectOverviewDto(1L, "E-Commerce Redesign", null, "ACTIVE",
                "MEMBER", null, "2026-07-01", null));
    }

    @Override
    public ProjectStatusDto getProjectStatus(Long projectId, Long requesterUserId) {
        log.info("[FakeAdapter] getProjectStatus called for projectId={}", projectId);
        if (projectId % 2 == 0) {
            return ScenarioFixtures.getProjectStatusAtRisk(projectId);
        }
        return ScenarioFixtures.getProjectStatusOnTrack(projectId);
    }

    @Override
    public List<ProjectMemberDto> getProjectMembers(Long projectId, Long requesterUserId) {
        log.info("[FakeAdapter] getProjectMembers called for projectId={}", projectId);
        return ScenarioFixtures.getProjectMembers(projectId);
    }
}
