package com.taskpilot.projects.sprints.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.entity.SprintEntity;
import com.taskpilot.projects.common.entity.SprintEntity.SprintStatus;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.common.repository.SprintRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
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
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final TaskRepository taskRepository;
    private final TaskDtoMapper taskDtoMapper;
    private final UserIdentityPort userIdentityPort;

    @Transactional
    public SprintDto createSprint(Long projectId, CreateSprintRequest request, String email) {
        ProjectEntity project = findProject(projectId);
        validateManager(projectId, getCurrentUserIdByEmail(email));
        validateProjectNotArchived(project);
        validateDateRange(request.startDate(), request.endDate());

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
        findProject(projectId);
        validateMember(projectId, getCurrentUserIdByEmail(email));
        return sortedSprints(projectId).stream().map(SprintDto::fromEntity).toList();
    }

    @Transactional
    public SprintDto updateSprint(Long projectId, Long sprintId, UpdateSprintRequest request, String email) {
        ProjectEntity project = findProject(projectId);
        validateManager(projectId, getCurrentUserIdByEmail(email));
        validateProjectNotArchived(project);
        SprintEntity sprint = findSprintInProject(projectId, sprintId);

        if (sprint.getStatus() == SprintStatus.COMPLETED) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Completed sprint is readonly");
        }

        LocalDate nextStart = request.startDate() != null ? request.startDate() : sprint.getStartDate();
        LocalDate nextEnd = request.endDate() != null ? request.endDate() : sprint.getEndDate();
        validateDateRange(nextStart, nextEnd);

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
        ProjectEntity project = findProject(projectId);
        validateManager(projectId, getCurrentUserIdByEmail(email));
        validateProjectNotArchived(project);
        SprintEntity sprint = findSprintInProject(projectId, sprintId);

        if (sprint.getStatus() != SprintStatus.PLANNING) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Only planning sprint can be deleted");
        }

        taskRepository.clearSprintId(sprintId);
        sprintRepository.delete(sprint);
    }

    @Transactional
    public SprintDto startSprint(Long projectId, Long sprintId, String email) {
        ProjectEntity project = findProject(projectId);
        validateManager(projectId, getCurrentUserIdByEmail(email));
        validateProjectNotArchived(project);
        SprintEntity sprint = findSprintInProject(projectId, sprintId);

        if (sprint.getStatus() != SprintStatus.PLANNING) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Only planning sprint can be started");
        }
        if (sprintRepository.existsByProjectIdAndStatus(projectId, SprintStatus.ACTIVE)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Project already has an active sprint");
        }
        if (taskRepository.countBySprintId(sprintId) == 0) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Sprint must contain at least one task");
        }

        sprint.setStatus(SprintStatus.ACTIVE);
        return SprintDto.fromEntity(sprintRepository.save(sprint));
    }

    @Transactional
    public SprintDto completeSprint(Long projectId, Long sprintId, String email) {
        ProjectEntity project = findProject(projectId);
        validateManager(projectId, getCurrentUserIdByEmail(email));
        validateProjectNotArchived(project);
        SprintEntity sprint = findSprintInProject(projectId, sprintId);

        if (sprint.getStatus() != SprintStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Only active sprint can be completed");
        }
        if (taskRepository.existsBySprintIdAndStatusNot(sprintId, TaskEntity.TaskStatus.DONE)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "All non-DONE tasks block sprint completion");
        }

        sprint.setStatus(SprintStatus.COMPLETED);
        return SprintDto.fromEntity(sprintRepository.save(sprint));
    }

    @Transactional(readOnly = true)
    public BacklogResponse getBacklog(Long projectId, String email) {
        findProject(projectId);
        validateMember(projectId, getCurrentUserIdByEmail(email));

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
        ProjectEntity project = findProject(projectId);
        validateMember(projectId, getCurrentUserIdByEmail(email));

        if (project.getWorkflowMode() == ProjectEntity.WorkflowMode.SCRUM) {
            return sprintRepository.findByProjectIdAndStatus(projectId, SprintStatus.ACTIVE)
                    .map(active -> new BoardResponse(
                            project.getWorkflowMode(),
                            SprintDto.fromEntity(active),
                            taskDtoMapper.mapToDtoWithLabels(taskRepository.findBySprintId(active.getId()))))
                    .orElseGet(() -> new BoardResponse(project.getWorkflowMode(), null, List.of()));
        }

        return new BoardResponse(
                project.getWorkflowMode(),
                sprintRepository.findByProjectIdAndStatus(projectId, SprintStatus.ACTIVE)
                        .map(SprintDto::fromEntity)
                        .orElse(null),
                taskDtoMapper.mapToDtoWithLabels(taskRepository.findByProjectId(projectId)));
    }

    private List<SprintEntity> sortedSprints(Long projectId) {
        return sprintRepository.findByProjectIdOrderByStartDateAsc(projectId).stream()
                .sorted(Comparator
                        .comparingInt((SprintEntity sprint) -> switch (sprint.getStatus()) {
                            case ACTIVE -> 0;
                            case PLANNING -> 1;
                            case COMPLETED -> 2;
                        })
                        .thenComparing(SprintEntity::getStartDate, Comparator.nullsLast(LocalDate::compareTo))
                        .thenComparing(SprintEntity::getId))
                .toList();
    }

    private SprintEntity findSprintInProject(Long projectId, Long sprintId) {
        SprintEntity sprint = sprintRepository.findById(sprintId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Sprint not found"));
        if (!sprint.getProjectId().equals(projectId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND.value(), "Sprint not found in project");
        }
        return sprint;
    }

    private ProjectEntity findProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));
    }

    private void validateMember(Long projectId, Long userId) {
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "You are not a member of this project");
        }
    }

    private void validateManager(Long projectId, Long userId) {
        ProjectMemberEntity member = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN.value(),
                        "You are not a member of this project"));
        if (member.getRole() != ProjectMemberEntity.MemberRole.MANAGER) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Only Project Manager can perform this action");
        }
    }

    private void validateProjectNotArchived(ProjectEntity project) {
        if (project.getStatus() == ProjectEntity.ProjectStatus.ARCHIVED) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Project is archived");
        }
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Sprint end date must be greater than or equal to start date");
        }
    }

    private Long getCurrentUserIdByEmail(String email) {
        return userIdentityPort.findByEmail(email)
                .map(identity -> identity.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "User not found"));
    }
}
