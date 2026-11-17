package com.taskpilot.projects.common.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Meta;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.taskpilot.projects.common.entity.SprintEntity;
import com.taskpilot.projects.common.enums.SprintStatus;

public interface SprintRepository extends JpaRepository<SprintEntity, Long> {
    List<SprintEntity> findByProjectIdOrderByStartDateAsc(Long projectId);

    List<SprintEntity> findByProjectIdOrderByStartDateAscIdAsc(Long projectId);

    Optional<SprintEntity> findByProjectIdAndStatus(Long projectId, SprintStatus status);

    boolean existsByProjectIdAndStatus(Long projectId, SprintStatus status);

    long countByProjectIdAndStatus(Long projectId, SprintStatus status);

    List<SprintEntity> findByProjectIdAndStatusOrderByIdAsc(Long projectId, SprintStatus status);

    List<SprintEntity> findByProjectIdAndStatusInOrderByStartDateAscIdAsc(Long projectId, List<SprintStatus> statuses);

    List<SprintEntity> findByStatusOrderByProjectIdAscIdAsc(SprintStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Meta(comment = "SprintRepository.findByIdForUpdate")
    @Query("SELECT s FROM SprintEntity s WHERE s.id = :id")
    Optional<SprintEntity> findByIdForUpdate(@Param("id") Long id);

    @org.springframework.data.jpa.repository.Modifying
    @Meta(comment = "SprintRepository.deleteByProjectId")
    @Query("DELETE FROM SprintEntity s WHERE s.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
