package com.taskpilot.contracts.assignment.port.out;

import java.util.Optional;

import com.taskpilot.contracts.assignment.dto.ProjectHeuristicConfigDto;

public interface ProjectPort {

    Optional<ProjectHeuristicConfigDto> findById(Long projectId);
}
