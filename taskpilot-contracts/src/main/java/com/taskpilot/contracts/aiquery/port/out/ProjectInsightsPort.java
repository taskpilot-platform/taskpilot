package com.taskpilot.contracts.aiquery.port.out;

import com.taskpilot.contracts.aiquery.dto.ProjectMemberDto;
import com.taskpilot.contracts.aiquery.dto.ProjectOverviewDto;
import com.taskpilot.contracts.aiquery.dto.ProjectStatusDto;

import java.util.List;

public interface ProjectInsightsPort {
    List<ProjectOverviewDto> getMyProjects(Long requesterUserId);
    ProjectStatusDto getProjectStatus(Long projectId, Long requesterUserId);
    List<ProjectMemberDto> getProjectMembers(Long projectId, Long requesterUserId);
}
