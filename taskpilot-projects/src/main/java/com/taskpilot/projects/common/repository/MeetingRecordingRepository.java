package com.taskpilot.projects.common.repository;

import com.taskpilot.projects.common.entity.MeetingRecordingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MeetingRecordingRepository extends JpaRepository<MeetingRecordingEntity, UUID> {
    List<MeetingRecordingEntity> findByMeetingIdAndProjectIdOrderByCreatedAtDesc(Long meetingId, Long projectId);

    Optional<MeetingRecordingEntity> findByProjectFileId(Long projectFileId);

    @Modifying
    @Query("""
            update MeetingRecordingEntity r set r.state = :interrupted, r.expiresAt = null, r.version = r.version + 1
            where r.expiresAt < :now
            """)
    int expire(@Param("now") Instant now, @Param("interrupted") MeetingRecordingEntity.State interrupted);
}
