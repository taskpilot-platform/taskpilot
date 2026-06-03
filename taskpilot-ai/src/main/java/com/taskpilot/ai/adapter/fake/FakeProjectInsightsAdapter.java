package com.taskpilot.ai.adapter.fake;

import com.taskpilot.contracts.aiquery.dto.ProjectMemberDto;
import com.taskpilot.contracts.aiquery.dto.ProjectOverviewDto;
import com.taskpilot.contracts.aiquery.dto.ProjectStatusDto;
import com.taskpilot.contracts.aiquery.dto.LabelSummaryDto;
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

    @Override
    public ProjectOverviewDto createProject(String name, String description, String startDate, String endDate, Long requesterUserId) {
        log.info("[FakeAdapter] createProject called for name={}, description={}, startDate={}, endDate={}, userId={}",
                name, description, startDate, endDate, requesterUserId);
        return new ProjectOverviewDto(99L, name, description, "ACTIVE", "MANAGER", startDate, endDate, "2026-06-03T12:00:00Z");
    }

    @Override
    public ProjectOverviewDto updateProject(Long projectId, String name, String description, String status, String heuristicMode, String workflowMode, String startDate, String endDate, Long requesterUserId) {
        log.info("[FakeAdapter] updateProject called for projectId={}", projectId);
        return new ProjectOverviewDto(projectId, name, description, status, "MANAGER", startDate, endDate, "2026-06-03T12:00:00Z");
    }

    @Override
    public Object joinProject(String projectCode, Long requesterUserId) {
        log.info("[FakeAdapter] joinProject called for code={} user={}", projectCode, requesterUserId);
        return "Joined project successfully";
    }

    @Override
    public void leaveProject(Long projectId, Long requesterUserId) {
        log.info("[FakeAdapter] leaveProject called for projectId={} user={}", projectId, requesterUserId);
    }

    @Override
    public void updateMemberRole(Long projectId, Long targetUserId, String role, Long requesterUserId) {
        log.info("[FakeAdapter] updateMemberRole called for projectId={} target={} role={} user={}", projectId, targetUserId, role, requesterUserId);
    }

    @Override
    public void removeMember(Long projectId, Long targetUserId, Long requesterUserId) {
        log.info("[FakeAdapter] removeMember called for projectId={} target={} user={}", projectId, targetUserId, requesterUserId);
    }

    @Override
    public void archiveProject(Long projectId, Long requesterUserId) {
        log.info("[FakeAdapter] archiveProject called for projectId={} user={}", projectId, requesterUserId);
    }

    @Override
    public void restoreProject(Long projectId, Long requesterUserId) {
        log.info("[FakeAdapter] restoreProject called for projectId={} user={}", projectId, requesterUserId);
    }

    @Override
    public void deleteProject(Long projectId, Long requesterUserId) {
        log.info("[FakeAdapter] deleteProject called for projectId={} user={}", projectId, requesterUserId);
    }

    @Override
    public List<LabelSummaryDto> getProjectLabels(Long projectId, Long requesterUserId) {
        log.info("[FakeAdapter] getProjectLabels called for projectId={} user={}", projectId, requesterUserId);
        return List.of(new LabelSummaryDto(1L, "backend", "#6366F1"));
    }

    @Override
    public LabelSummaryDto createProjectLabel(Long projectId, String name, String color, Long requesterUserId) {
        log.info("[FakeAdapter] createProjectLabel called for projectId={} name={} user={}", projectId, name, requesterUserId);
        return new LabelSummaryDto(99L, name, color != null ? color : "#6366F1");
    }

    @Override
    public void deleteProjectLabel(Long projectId, Long labelId, Long requesterUserId) {
        log.info("[FakeAdapter] deleteProjectLabel called for projectId={} labelId={} user={}", projectId, labelId, requesterUserId);
    }
}
