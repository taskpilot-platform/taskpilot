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

    List<ProjectMeetingEntity> findByRecordingStatusAndEgressIdIsNullAndUpdatedAtBefore(
            MeetingRecordingStatus status,
            Instant updatedBefore
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ProjectMeetingEntity m
            SET m.status = com.taskpilot.projects.common.enums.ProjectMeetingStatus.ENDED,
                m.endedAt = :endedAt,
                m.updatedAt = :now
            WHERE m.id = :id
              AND m.status <> com.taskpilot.projects.common.enums.ProjectMeetingStatus.ENDED
            """)
    int markMeetingEnded(
            @Param("id") Long id,
            @Param("endedAt") Instant endedAt,
            @Param("now") Instant now
    );

    Optional<ProjectMeetingEntity> findFirstByProjectIdAndStatusOrderByStartedAtDesc(Long projectId, ProjectMeetingStatus status);

    long countByProjectIdAndStatus(Long projectId, ProjectMeetingStatus status);

    List<ProjectMeetingEntity> findByProjectIdInOrderByCreatedAtDesc(List<Long> projectIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ProjectMeetingEntity m
            SET m.recordingStatus = com.taskpilot.projects.common.enums.MeetingRecordingStatus.STARTING,
                m.recordingEnabled = true,
                m.egressId = null,
                m.recordingError = null,
                m.backupStorageUsed = false,
                m.updatedAt = :now
            WHERE m.id = :id
              AND (
                  m.recordingStatus IS NULL
                  OR m.recordingStatus IN (
                      com.taskpilot.projects.common.enums.MeetingRecordingStatus.IDLE,
                      com.taskpilot.projects.common.enums.MeetingRecordingStatus.COMPLETED,
                      com.taskpilot.projects.common.enums.MeetingRecordingStatus.FAILED
                  )
              )
            """)
    int claimRecordingStart(@Param("id") Long id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ProjectMeetingEntity m
            SET m.egressId = :egressId,
                m.recordingStatus = :nextStatus,
                m.recordingEnabled = true,
                m.recordingError = null,
                m.backupStorageUsed = false,
                m.updatedAt = :now
            WHERE m.id = :id
              AND m.recordingStatus = com.taskpilot.projects.common.enums.MeetingRecordingStatus.STARTING
              AND m.egressId IS NULL
            """)
    int attachStartedEgress(
            @Param("id") Long id,
            @Param("egressId") String egressId,
            @Param("nextStatus") MeetingRecordingStatus nextStatus,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ProjectMeetingEntity m
            SET m.recordingStatus = com.taskpilot.projects.common.enums.MeetingRecordingStatus.FAILED,
                m.recordingError = :error,
                m.updatedAt = :now
            WHERE m.id = :id
              AND m.recordingStatus = com.taskpilot.projects.common.enums.MeetingRecordingStatus.STARTING
              AND m.egressId IS NULL
            """)
    int releaseFailedStartClaim(
            @Param("id") Long id,
            @Param("error") String error,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ProjectMeetingEntity m
            SET m.recordingFileId = :fileId,
                m.egressId = null,
                m.recordingEnabled = true,
                m.recordingStatus = com.taskpilot.projects.common.enums.MeetingRecordingStatus.COMPLETED,
                m.recordingError = null,
                m.backupStorageUsed = false,
                m.updatedAt = :now
            WHERE m.id = :id
              AND (
                  m.recordingStatus IS NULL
                  OR m.recordingStatus NOT IN (
                      com.taskpilot.projects.common.enums.MeetingRecordingStatus.STARTING,
                      com.taskpilot.projects.common.enums.MeetingRecordingStatus.RECORDING,
                      com.taskpilot.projects.common.enums.MeetingRecordingStatus.PROCESSING
                  )
              )
            """)
    int linkFallbackRecording(
            @Param("id") Long id,
            @Param("fileId") Long fileId,
            @Param("now") Instant now
    );

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
