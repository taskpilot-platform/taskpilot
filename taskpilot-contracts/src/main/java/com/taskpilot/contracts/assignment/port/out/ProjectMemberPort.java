package com.taskpilot.contracts.assignment.port.out;

import java.util.List;
import java.time.LocalDate;

import com.taskpilot.contracts.assignment.dto.ProjectDueDto;
import com.taskpilot.contracts.assignment.dto.ProjectMemberDto;

public interface ProjectMemberPort {

    List<ProjectMemberDto> findProjectMembers(Long projectId);

    List<ProjectDueDto> findUpcomingProjects(Long userId, LocalDate fromDate, LocalDate toDate, int limit);

    boolean isProjectMember(Long projectId, Long userId);

    boolean isProjectManager(Long projectId, Long userId);

    default boolean isProjectArchived(Long projectId) {
        return false;
    }

    default String computeProjectRosterRevision(Long projectId) {
        return "untracked";
    }

    /** Serialize storage metadata writes against project deletion; caller must own a transaction. */
    void lockProjectForStorageWrite(Long projectId);
}
