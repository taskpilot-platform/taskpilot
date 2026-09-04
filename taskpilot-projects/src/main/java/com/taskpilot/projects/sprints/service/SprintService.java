package com.taskpilot.projects.sprints.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.util.ValidationUtils;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.SprintEntity;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.enums.SprintStatus;
import com.taskpilot.projects.common.enums.WorkflowMode;
import com.taskpilot.projects.common.repository.SprintRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.sprints.dto.BacklogResponse;
import com.taskpilot.projects.sprints.dto.BoardResponse;
import com.taskpilot.projects.sprints.dto.CreateSprintRequest;
import com.taskpilot.projects.sprints.dto.SprintBacklogSection;
import com.taskpilot.projects.sprints.dto.SprintDto;
import com.taskpilot.projects.sprints.dto.UpdateSprintRequest;
import com.taskpilot.projects.tasks.service.TaskDtoMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SprintService {

    private final SprintRepository sprintRepository;
    private final ProjectSecurityService projectSecurityService;
    private final TaskRepository taskRepository;
    private final TaskDtoMapper taskDtoMapper;
    private final UserIdentityPort userIdentityPort;

    @Transactional
    public SprintDto createSprint(Long projectId, CreateSprintRequest request, String email) {
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateManager(projectId, getCurrentUserIdByEmail(email));
        ValidationUtils.validateDateRange(request.startDate(), request.endDate(), "Sprint end date must be greater than or equal to start date");

        SprintEntity sprint = SprintEntity.builder()
                .projectId(projectId)
                .name(request.name())
                .goal(request.goal())
                .status(SprintStatus.PLANNING)
                .startDate(request.startDate())
                .endDate(request.endDate())
                .build();
        return SprintDto.fromEntity(sprintRepository.save(sprint));
    }

    @Transactional(readOnly = true)
    public List<SprintDto> listSprints(Long projectId, String email) {
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, getCurrentUserIdByEmail(email));
        return sortedSprints(projectId).stream().map(SprintDto::fromEntity).toList();
    }

    @Transactional
    public SprintDto updateSprint(Long projectId, Long sprintId, UpdateSprintRequest request, String email) {
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateManager(projectId, getCurrentUserIdByEmail(email));
        SprintEntity sprint = findSprintInProject(projectId, sprintId);

        if (sprint.getStatus() == SprintStatus.COMPLETED) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Completed sprint is readonly");
        }

        LocalDate nextStart = request.startDate() != null ? request.startDate() : sprint.getStartDate();
        LocalDate nextEnd = request.endDate() != null ? request.endDate() : sprint.getEndDate();
        ValidationUtils.validateDateRange(nextStart, nextEnd, "Sprint end date must be greater than or equal to start date");

        if (request.name() != null && !request.name().isBlank()) {
            sprint.setName(request.name());
        }
        if (request.goal() != null) {
            sprint.setGoal(request.goal());
        }
        if (request.startDate() != null) {
            sprint.setStartDate(request.startDate());
        }
        if (request.endDate() != null) {
            sprint.setEndDate(request.endDate());
        }
        return SprintDto.fromEntity(sprintRepository.save(sprint));
    }

    @Transactional
    public void deleteSprint(Long projectId, Long sprintId, String email) {
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateManager(projectId, getCurrentUserIdByEmail(email));
        SprintEntity sprint = findSprintInProject(projectId, sprintId);

        if (sprint.getStatus() != SprintStatus.PLANNING) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Only planning sprint can be deleted");
        }

        taskRepository.clearSprintId(sprintId);
        sprintRepository.delete(sprint);
    }

    @Transactional
    public SprintDto startSprint(Long projectId, Long sprintId, String email) {
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateManager(projectId, getCurrentUserIdByEmail(email));
        SprintEntity sprint = findSprintInProject(projectId, sprintId);

        if (sprint.getStatus() != SprintStatus.PLANNING) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Only planning sprint can be started");
        }
        if (sprintRepository.existsByProjectIdAndStatus(projectId, SprintStatus.ACTIVE)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Project already has an active sprint");
        }

        sprint.setStatus(SprintStatus.ACTIVE);
        return SprintDto.fromEntity(sprintRepository.save(sprint));
    }

    @Transactional
    public SprintDto completeSprint(Long projectId, Long sprintId, String email) {
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateManager(projectId, getCurrentUserIdByEmail(email));
        SprintEntity sprint = findSprintInProject(projectId, sprintId);

        if (sprint.getStatus() != SprintStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Only active sprint can be completed");
        }

        sprint.setStatus(SprintStatus.COMPLETED);
        return SprintDto.fromEntity(sprintRepository.save(sprint));
    }

    @Transactional(readOnly = true)
    public BacklogResponse getBacklog(Long projectId, String email) {
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, getCurrentUserIdByEmail(email));

        var unscheduled = taskDtoMapper.mapToDtoWithLabels(
                taskRepository.findByProjectIdAndSprintIdIsNullOrderByPositionAsc(projectId));

        List<SprintBacklogSection> sections = sortedSprints(projectId).stream()
                .map(sprint -> new SprintBacklogSection(
                        SprintDto.fromEntity(sprint),
                        taskDtoMapper.mapToDtoWithLabels(taskRepository.findBySprintId(sprint.getId()).stream()
                                .sorted(Comparator.comparing(TaskEntity::getPosition,
                                        Comparator.nullsLast(Float::compareTo)))
                                .toList())))
                .toList();

        return new BacklogResponse(unscheduled, sections);
    }

    @Transactional(readOnly = true)
    public BoardResponse getBoard(Long projectId, String email) {
        ProjectEntity project = projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, getCurrentUserIdByEmail(email));

        if (project.getWorkflowMode() == WorkflowMode.SCRUM) {
            return sprintRepository.findByProjectIdAndStatus(projectId, SprintStatus.ACTIVE)
                    .map(active -> new BoardResponse(
                            project.getWorkflowMode(),
                            SprintDto.fromEntity(active),
                            taskDtoMapper.mapToDtoWithLabels(taskRepository.findBySprintId(active.getId()))))
                    .orElseGet(() -> new BoardResponse(project.getWorkflowMode(), null, List.of()));
        }

        return new BoardResponse(
                project.getWorkflowMode(),
                null,
                taskDtoMapper.mapToDtoWithLabels(
                        taskRepository.findByProjectIdAndSprintIdIsNullOrderByPositionAsc(projectId)));
    }

    private List<SprintEntity> sortedSprints(Long projectId) {
        return sprintRepository.findByProjectIdOrderByStartDateAscIdAsc(projectId);
    }

    private SprintEntity findSprintInProject(Long projectId, Long sprintId) {
        SprintEntity sprint = sprintRepository.findById(sprintId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Sprint not found"));
        if (!sprint.getProjectId().equals(projectId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Sprint does not belong to this project");
        }
        return sprint;
    }

    private Long getCurrentUserIdByEmail(String email) {
        return userIdentityPort.findByEmail(email)
                .map(identity -> identity.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "User not found"));
    }
}
