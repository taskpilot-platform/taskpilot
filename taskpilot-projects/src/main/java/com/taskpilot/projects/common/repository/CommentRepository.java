package com.taskpilot.projects.common.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.taskpilot.projects.common.entity.CommentEntity;

@Repository
public interface CommentRepository extends JpaRepository<CommentEntity, Long> {

    List<CommentEntity> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    Optional<CommentEntity> findByIdAndTaskId(Long id, Long taskId);

    @Query("SELECT DISTINCT c.userId FROM CommentEntity c WHERE c.taskId = :taskId")
    Set<Long> findParticipantUserIdsByTaskId(@Param("taskId") Long taskId);
}
