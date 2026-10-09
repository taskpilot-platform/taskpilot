package com.taskpilot.projects.common.repository;

import com.taskpilot.projects.common.entity.ProjectMeetingEntity;
import com.taskpilot.projects.common.enums.ProjectMeetingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectMeetingRepository extends JpaRepository<ProjectMeetingEntity, Long> {

    List<ProjectMeetingEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    List<ProjectMeetingEntity> findByProjectIdAndStatusOrderByStartedAtDesc(Long projectId, ProjectMeetingStatus status);

    Optional<ProjectMeetingEntity> findByIdAndProjectId(Long id, Long projectId);

    Optional<ProjectMeetingEntity> findByRoomName(String roomName);

    Optional<ProjectMeetingEntity> findFirstByProjectIdAndStatusOrderByStartedAtDesc(Long projectId, ProjectMeetingStatus status);

    long countByProjectIdAndStatus(Long projectId, ProjectMeetingStatus status);

    List<ProjectMeetingEntity> findByProjectIdInOrderByCreatedAtDesc(List<Long> projectIds);
}
