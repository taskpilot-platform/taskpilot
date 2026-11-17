package com.taskpilot.projects.common.repository;

import com.taskpilot.projects.common.entity.TaskSubmissionEvidenceFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TaskSubmissionEvidenceFileRepository extends JpaRepository<TaskSubmissionEvidenceFileEntity, Long> {

    List<TaskSubmissionEvidenceFileEntity> findBySubmissionIdInOrderByIdAsc(Collection<Long> submissionIds);

    List<TaskSubmissionEvidenceFileEntity> findByLiveSourceFileId(Long liveSourceFileId);
}
