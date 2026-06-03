package com.taskpilot.contracts.aiquery.port.out;

import com.taskpilot.contracts.aiquery.dto.ProjectMemberDto;
import com.taskpilot.contracts.aiquery.dto.ProjectOverviewDto;
import com.taskpilot.contracts.aiquery.dto.ProjectStatusDto;
import com.taskpilot.contracts.aiquery.dto.LabelSummaryDto;

import java.util.List;

public interface ProjectInsightsPort {
    List<ProjectOverviewDto> getMyProjects(Long requesterUserId);
    ProjectStatusDto getProjectStatus(Long projectId, Long requesterUserId);
    List<ProjectMemberDto> getProjectMembers(Long projectId, Long requesterUserId);
    ProjectOverviewDto createProject(String name, String description, String startDate, String endDate, Long requesterUserId);
    ProjectOverviewDto updateProject(Long projectId, String name, String description, String status, String heuristicMode, String workflowMode, String startDate, String endDate, Long requesterUserId);
    Object joinProject(String projectCode, Long requesterUserId);
    void leaveProject(Long projectId, Long requesterUserId);
    void updateMemberRole(Long projectId, Long targetUserId, String role, Long requesterUserId);
    void removeMember(Long projectId, Long targetUserId, Long requesterUserId);
    void archiveProject(Long projectId, Long requesterUserId);
    void restoreProject(Long projectId, Long requesterUserId);
    void deleteProject(Long projectId, Long requesterUserId);
    List<LabelSummaryDto> getProjectLabels(Long projectId, Long requesterUserId);
    LabelSummaryDto createProjectLabel(Long projectId, String name, String color, Long requesterUserId);
    void deleteProjectLabel(Long projectId, Long labelId, Long requesterUserId);
}
