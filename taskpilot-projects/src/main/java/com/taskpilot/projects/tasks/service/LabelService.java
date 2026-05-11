package com.taskpilot.projects.tasks.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.LabelEntity;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity.MemberRole;
import com.taskpilot.projects.common.repository.LabelRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.tasks.dto.CreateLabelRequest;
import com.taskpilot.projects.tasks.dto.LabelDto;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;

import org.springframework.dao.DataIntegrityViolationException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class LabelService {

    private final LabelRepository labelRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserIdentityPort userIdentityPort;

    private Long getCurrentUserIdByEmail(String email) {
        return userIdentityPort.findByEmail(email)
                .map(identity -> identity.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "User not found"));
    }

    private void validateUserIsMember(Long projectId, Long userId) {
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "You are not a member of this project");
        }
    }

    private void validateUserIsProjectManager(Long projectId, Long userId) {
        ProjectMemberEntity member = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN.value(),
                        "You are not a member of this project"));
        if (member.getRole() != MemberRole.MANAGER) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Only Project Manager can perform this action");
        }
    }

    private void validateProjectNotArchived(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));
        if (project.getStatus() == ProjectEntity.ProjectStatus.ARCHIVED) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Project is archived");
        }
    }

    @Transactional(readOnly = true)
    public List<LabelDto> getLabelsByProject(Long projectId, String email) {
        Long userId = getCurrentUserIdByEmail(email);
        validateUserIsMember(projectId, userId);

        return labelRepository.findByProjectId(projectId).stream()
                .map(l -> new LabelDto(l.getId(), l.getName(), l.getColor()))
                .toList();
    }

    @Transactional
    public LabelDto createLabel(Long projectId, CreateLabelRequest request, String email) {
        Long userId = getCurrentUserIdByEmail(email);
        validateUserIsProjectManager(projectId, userId);
        validateProjectNotArchived(projectId);

        String normalizedName = request.name().trim().replaceAll("\\s+", " ");

        if (labelRepository.existsByProjectIdAndNameIgnoreCase(projectId, normalizedName)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Label with this name already exists in the project");
        }

        LabelEntity label = LabelEntity.builder()
                .projectId(projectId)
                .name(normalizedName)
                .color(request.color() != null ? request.color().toUpperCase() : "#6366F1")
                .build();

        try {
            labelRepository.save(label);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Label with this name already exists in the project");
        }

        return new LabelDto(label.getId(), label.getName(), label.getColor());
    }

    @Transactional
    public void deleteLabel(Long projectId, Long labelId, String email) {
        Long userId = getCurrentUserIdByEmail(email);
        validateUserIsProjectManager(projectId, userId);
        validateProjectNotArchived(projectId);

        LabelEntity label = labelRepository.findById(labelId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Label not found"));

        if (!label.getProjectId().equals(projectId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Label does not belong to this project");
        }

        labelRepository.delete(label);
    }
}
