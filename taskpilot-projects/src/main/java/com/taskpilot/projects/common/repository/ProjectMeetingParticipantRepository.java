package com.taskpilot.projects.common.repository;

import com.taskpilot.projects.common.entity.ProjectMeetingParticipantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectMeetingParticipantRepository extends JpaRepository<ProjectMeetingParticipantEntity, Long> {

    List<ProjectMeetingParticipantEntity> findByMeetingIdOrderByJoinedAtAsc(Long meetingId);

    List<ProjectMeetingParticipantEntity> findByMeetingIdAndLeftAtIsNullOrderByJoinedAtAsc(Long meetingId);

    Optional<ProjectMeetingParticipantEntity> findFirstByMeetingIdAndUserIdAndLeftAtIsNullOrderByJoinedAtDesc(Long meetingId, Long userId);

    long countByMeetingIdAndLeftAtIsNull(Long meetingId);

    long countByMeetingId(Long meetingId);
}
