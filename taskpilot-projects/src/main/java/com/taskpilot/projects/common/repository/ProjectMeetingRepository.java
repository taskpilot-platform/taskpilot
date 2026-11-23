package com.taskpilot.projects.common.repository;

import com.taskpilot.projects.common.entity.ProjectMeetingEntity;
import com.taskpilot.projects.common.enums.MeetingRecordingStatus;
import com.taskpilot.projects.common.enums.ProjectMeetingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectMeetingRepository extends JpaRepository<ProjectMeetingEntity, Long> {

    List<ProjectMeetingEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    List<ProjectMeetingEntity> findByProjectIdAndStatusOrderByStartedAtDesc(Long projectId, ProjectMeetingStatus status);

    Optional<ProjectMeetingEntity> findByIdAndProjectId(Long id, Long projectId);

    Optional<ProjectMeetingEntity> findByRoomName(String roomName);

    Optional<ProjectMeetingEntity> findByEgressId(String egressId);

    List<ProjectMeetingEntity> findByRecordingStatusInAndEgressIdIsNotNull(Collection<MeetingRecordingStatus> statuses);

    Optional<ProjectMeetingEntity> findFirstByProjectIdAndStatusOrderByStartedAtDesc(Long projectId, ProjectMeetingStatus status);

    long countByProjectIdAndStatus(Long projectId, ProjectMeetingStatus status);

    List<ProjectMeetingEntity> findByProjectIdInOrderByCreatedAtDesc(List<Long> projectIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ProjectMeetingEntity m
            SET m.recordingStatus = :nextStatus,
                m.recordingFileId = :fileId,
                m.recordingEnabled = true,
                m.recordingError = null,
                m.backupStorageUsed = :backupStorageUsed,
                m.updatedAt = :updatedAt
            WHERE m.id = :id
              AND m.egressId = :egressId
              AND m.recordingStatus IN :allowedStatuses
            """)
    int transitionRecordingCompleted(
            @Param("id") Long id,
            @Param("egressId") String egressId,
            @Param("nextStatus") MeetingRecordingStatus nextStatus,
            @Param("fileId") Long fileId,
            @Param("backupStorageUsed") boolean backupStorageUsed,
            @Param("updatedAt") Instant updatedAt,
            @Param("allowedStatuses") Collection<MeetingRecordingStatus> allowedStatuses
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ProjectMeetingEntity m
            SET m.recordingStatus = :nextStatus,
                m.recordingError = :recordingError,
                m.backupStorageUsed = :backupStorageUsed,
                m.updatedAt = :updatedAt
            WHERE m.id = :id
              AND m.egressId = :egressId
              AND m.recordingStatus IN :allowedStatuses
            """)
    int transitionRecordingStatus(
            @Param("id") Long id,
            @Param("egressId") String egressId,
            @Param("nextStatus") MeetingRecordingStatus nextStatus,
            @Param("recordingError") String recordingError,
            @Param("backupStorageUsed") boolean backupStorageUsed,
            @Param("updatedAt") Instant updatedAt,
            @Param("allowedStatuses") Collection<MeetingRecordingStatus> allowedStatuses
    );
}
