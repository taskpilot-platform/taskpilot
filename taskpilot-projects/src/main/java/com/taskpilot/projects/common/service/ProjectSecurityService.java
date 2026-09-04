package com.taskpilot.projects.common.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.ProjectStatus;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProjectSecurityService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;

    public ProjectEntity requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));
    }

    public ProjectEntity requireActiveProject(Long projectId) {
        ProjectEntity project = requireProject(projectId);
        validateProjectNotArchived(project);
        return project;
    }

    public void validateMember(Long projectId, Long userId) {
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "You are not a member of this project");
        }
    }

    public ProjectMemberEntity validateManager(Long projectId, Long userId) {
        ProjectMemberEntity member = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN.value(), "You are not a member of this project"));
        if (member.getRole() != MemberRole.MANAGER) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Only Project Manager can perform this action");
        }
        return member;
    }

    public void validateProjectNotArchived(ProjectEntity project) {
        if (project.getStatus() == ProjectStatus.ARCHIVED) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Project is archived");
        }
    }

    public void validateProjectNotArchived(Long projectId) {
        requireActiveProject(projectId);
    }
}

