package com.taskpilot.projects.timeline.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.SprintEntity;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.common.repository.SprintRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
import com.taskpilot.projects.timeline.dto.TimelineProjectDto;
import com.taskpilot.projects.timeline.dto.TimelineResponse;
import com.taskpilot.projects.timeline.dto.TimelineSprintDto;
import com.taskpilot.projects.timeline.dto.TimelineTaskDto;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TimelineService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final SprintRepository sprintRepository;
    private final TaskRepository taskRepository;
    private final UserIdentityPort userIdentityPort;

    @Transactional(readOnly = true)
    public TimelineResponse getTimeline(Long projectId, String email) {
        Long userId = userIdentityPort.findByEmail(email)
                .map(identity -> identity.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "User not found"));

        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));

        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "You are not a member of this project");
        }

        List<TimelineSprintDto> sprints = sprintRepository.findByProjectIdOrderByStartDateAsc(projectId).stream()
                .sorted(Comparator
                        .comparing(SprintEntity::getStartDate, Comparator.nullsLast(LocalDate::compareTo))
                        .thenComparing(SprintEntity::getId))
                .map(sprint -> new TimelineSprintDto(
                        sprint.getId(),
                        sprint.getName(),
                        sprint.getStatus(),
                        sprint.getStartDate(),
                        sprint.getEndDate(),
                        taskRepository.findBySprintId(sprint.getId()).stream()
                                .sorted(taskComparator())
                                .map(this::toTimelineTask)
                                .toList()))
                .toList();

        List<TimelineTaskDto> unscheduled = taskRepository
                .findByProjectIdAndSprintIdIsNullOrderByPositionAsc(projectId).stream()
                .sorted(taskComparator())
                .map(this::toTimelineTask)
                .toList();

        return new TimelineResponse(
                new TimelineProjectDto(project.getId(), project.getName(), project.getStartDate(), project.getEndDate()),
                sprints,
                unscheduled);
    }

    private TimelineTaskDto toTimelineTask(TaskEntity task) {
        return new TimelineTaskDto(
                task.getId(),
                task.getTitle(),
                task.getStatus(),
                task.getPriority(),
                task.getStartDate(),
                task.getDueDate());
    }

    private Comparator<TaskEntity> taskComparator() {
        return Comparator
                .comparing(TaskEntity::getStartDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TaskEntity::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TaskEntity::getPosition, Comparator.nullsLast(Float::compareTo))
                .thenComparing(TaskEntity::getId);
    }
}
