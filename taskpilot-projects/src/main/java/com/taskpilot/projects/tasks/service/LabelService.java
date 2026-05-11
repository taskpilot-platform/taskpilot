package com.taskpilot.projects.tasks.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.LabelEntity;
import com.taskpilot.projects.common.repository.LabelRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.tasks.dto.CreateLabelRequest;
import com.taskpilot.projects.tasks.dto.LabelDto;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class LabelService {

    private final LabelRepository labelRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserIdentityPort userIdentityPort;

    private Long getCurrentUserIdByEmail(String email) {
        return userIdentityPort.findUserIdByEmail(email)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "User not found"));
    }

    private void validateUserIsMember(Long projectId, Long userId) {
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "You are not a member of this project");
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
        validateUserIsMember(projectId, userId);

        String normalizedName = request.name().trim().replaceAll("\\s+", " ");

        if (labelRepository.existsByProjectIdAndNameIgnoreCase(projectId, normalizedName)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Label with this name already exists in the project");
        }

        LabelEntity label = LabelEntity.builder()
                .projectId(projectId)
                .name(normalizedName)
                .color(request.color() != null ? request.color().toUpperCase() : "#6366F1")
                .build();

        labelRepository.save(label);

        return new LabelDto(label.getId(), label.getName(), label.getColor());
    }

    @Transactional
    public void deleteLabel(Long projectId, Long labelId, String email) {
        Long userId = getCurrentUserIdByEmail(email);
        validateUserIsMember(projectId, userId);

        LabelEntity label = labelRepository.findById(labelId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Label not found"));

        if (!label.getProjectId().equals(projectId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Label does not belong to this project");
        }

        labelRepository.delete(label);
    }
}
