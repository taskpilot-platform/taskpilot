package com.taskpilot.contracts.aiquery.port.out;

import com.taskpilot.contracts.aiquery.dto.ProjectMemberDto;
import com.taskpilot.contracts.aiquery.dto.ProjectStatusDto;

import java.util.List;

public interface ProjectInsightsPort {
    ProjectStatusDto getProjectStatus(Long projectId);
    List<ProjectMemberDto> getProjectMembers(Long projectId);
}
