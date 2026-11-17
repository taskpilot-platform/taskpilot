package com.taskpilot.projects.common.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Meta;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.enums.TaskStatus;

public interface TaskRepository extends JpaRepository<TaskEntity, Long>, TaskSearchFragment {

    @Meta(comment = "TaskRepository.countByProjectId")
    @Query("SELECT COUNT(t) FROM TaskEntity t WHERE t.projectId = :projectId AND t.deletedAt IS NULL")
    long countByProjectId(@Param("projectId") Long projectId);

    @Meta(comment = "TaskRepository.countByProjectIdAndStatus")
    @Query("SELECT COUNT(t) FROM TaskEntity t WHERE t.projectId = :projectId AND t.status = :status AND t.deletedAt IS NULL")
    long countByProjectIdAndStatus(@Param("projectId") Long projectId, @Param("status") TaskStatus status);

    @Meta(comment = "TaskRepository.findByProjectId")
    @Query("SELECT t FROM TaskEntity t WHERE t.projectId = :projectId AND t.deletedAt IS NULL")
    List<TaskEntity> findByProjectId(@Param("projectId") Long projectId);

    @Meta(comment = "TaskRepository.findByAssigneeId")
    @Query("SELECT t FROM TaskEntity t WHERE t.assigneeId = :assigneeId AND t.deletedAt IS NULL")
    List<TaskEntity> findByAssigneeId(@Param("assigneeId") Long assigneeId);

    @Meta(comment = "TaskRepository.findByParentId")
    @Query("SELECT t FROM TaskEntity t WHERE t.parentId = :parentId AND t.deletedAt IS NULL")
    List<TaskEntity> findByParentId(@Param("parentId") Long parentId);

    @Meta(comment = "TaskRepository.findAllByParentIdIncludingDeleted")
    @Query("SELECT t FROM TaskEntity t WHERE t.parentId = :parentId ORDER BY t.id ASC")
    List<TaskEntity> findAllByParentIdIncludingDeleted(@Param("parentId") Long parentId);

    @Meta(comment = "TaskRepository.findBySprintId")
    @Query("SELECT t FROM TaskEntity t WHERE t.sprintId = :sprintId AND t.deletedAt IS NULL")
    List<TaskEntity> findBySprintId(@Param("sprintId") Long sprintId);

    @Meta(comment = "TaskRepository.findActiveBySprintIdOrderByIdAscForUpdate")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TaskEntity t WHERE t.sprintId = :sprintId AND t.deletedAt IS NULL ORDER BY t.id ASC")
    List<TaskEntity> findActiveBySprintIdOrderByIdAscForUpdate(@Param("sprintId") Long sprintId);

    @Meta(comment = "TaskRepository.findByProjectIdAndSprintIdIsNullOrderByPositionAsc")
    @Query("SELECT t FROM TaskEntity t WHERE t.projectId = :projectId AND t.sprintId IS NULL AND t.deletedAt IS NULL ORDER BY t.position ASC")
    List<TaskEntity> findByProjectIdAndSprintIdIsNullOrderByPositionAsc(@Param("projectId") Long projectId);

    @Meta(comment = "TaskRepository.countBySprintId")
    @Query("SELECT COUNT(t) FROM TaskEntity t WHERE t.sprintId = :sprintId AND t.deletedAt IS NULL")
    long countBySprintId(@Param("sprintId") Long sprintId);

    @Meta(comment = "TaskRepository.existsBySprintIdAndStatusNot")
    @Query("SELECT CASE WHEN COUNT(t) > 0 THEN true ELSE false END FROM TaskEntity t WHERE t.sprintId = :sprintId AND t.status <> :status AND t.deletedAt IS NULL")
    boolean existsBySprintIdAndStatusNot(@Param("sprintId") Long sprintId, @Param("status") TaskStatus status);

    @Meta(comment = "TaskRepository.existsBySuccessorOfTaskId")
    @Query("SELECT CASE WHEN COUNT(t) > 0 THEN true ELSE false END FROM TaskEntity t WHERE t.successorOfTaskId = :taskId")
    boolean existsBySuccessorOfTaskId(@Param("taskId") Long taskId);

    @Meta(comment = "TaskRepository.findTombstonedByProjectId")
    @Query("SELECT t FROM TaskEntity t WHERE t.projectId = :projectId AND t.deletedAt IS NOT NULL ORDER BY t.deletedAt DESC, t.id DESC")
    List<TaskEntity> findTombstonedByProjectId(@Param("projectId") Long projectId);

    @Meta(comment = "TaskRepository.findActiveById")
    @Query("SELECT t FROM TaskEntity t WHERE t.id = :id AND t.deletedAt IS NULL")
    Optional<TaskEntity> findActiveById(@Param("id") Long id);

    @Meta(comment = "TaskRepository.findByIdForUpdate")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TaskEntity t WHERE t.id = :id")
    Optional<TaskEntity> findByIdForUpdate(@Param("id") Long id);

    @Meta(comment = "TaskRepository.findAssigneeIdById")
    @Query("SELECT t.assigneeId FROM TaskEntity t WHERE t.id = :id AND t.deletedAt IS NULL")
    Optional<Long> findAssigneeIdById(@Param("id") Long id);

    @Meta(comment = "TaskRepository.findActiveAssignedByProjectIdAndAssigneeIdForUpdate")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TaskEntity t WHERE t.projectId = :projectId AND t.assigneeId = :assigneeId "
            + "AND t.status <> :doneStatus AND t.deletedAt IS NULL ORDER BY t.id ASC")
    List<TaskEntity> findActiveAssignedByProjectIdAndAssigneeIdForUpdate(
            @Param("projectId") Long projectId,
            @Param("assigneeId") Long assigneeId,
            @Param("doneStatus") TaskStatus doneStatus);

    default List<TaskEntity> findActiveAssignedByProjectIdAndAssigneeIdForUpdate(Long projectId, Long assigneeId) {
        return findActiveAssignedByProjectIdAndAssigneeIdForUpdate(projectId, assigneeId, TaskStatus.DONE);
    }

    @Meta(comment = "TaskRepository.clearSprintId")
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TaskEntity t SET t.sprintId = NULL, t.updatedAt = :updatedAt, t.updatedAtLegacy = false, t.version = t.version + 1 WHERE t.sprintId = :sprintId AND t.deletedAt IS NULL")
    int clearSprintId(@Param("sprintId") Long sprintId, @Param("updatedAt") Instant updatedAt);

    @Meta(comment = "TaskRepository.deleteByProjectId")
    @Modifying
    @Query("DELETE FROM TaskEntity t WHERE t.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);

    @Meta(comment = "TaskRepository.countTasksByStatusNative")
    @NativeQuery("SELECT status, COUNT(*) FROM public.tasks WHERE project_id = :projectId AND deleted_at IS NULL GROUP BY status")
    List<Object[]> countTasksByStatusNative(@Param("projectId") Long projectId);

    @Meta(comment = "TaskRepository.findTrappedUnfinishedInCompletedSprints")
    @Query("""
            SELECT t FROM TaskEntity t
            JOIN SprintEntity s ON s.id = t.sprintId
            WHERE (:projectId IS NULL OR t.projectId = :projectId)
              AND t.deletedAt IS NULL
              AND s.status = :completedStatus
              AND t.status <> :doneStatus
            ORDER BY t.projectId ASC, t.sprintId ASC, t.id ASC
            """)
    List<TaskEntity> findTrappedUnfinishedInCompletedSprints(
            @Param("projectId") Long projectId,
            @Param("completedStatus") com.taskpilot.projects.common.enums.SprintStatus completedStatus,
            @Param("doneStatus") TaskStatus doneStatus);

    default List<TaskEntity> findTrappedUnfinishedInCompletedSprints(Long projectId) {
        return findTrappedUnfinishedInCompletedSprints(
                projectId,
                com.taskpilot.projects.common.enums.SprintStatus.COMPLETED,
                TaskStatus.DONE);
    }

    @Meta(comment = "TaskRepository.findLegacyReviewMissingSubmission")
    @Query("""
            SELECT t FROM TaskEntity t
            WHERE (:projectId IS NULL OR t.projectId = :projectId)
              AND t.deletedAt IS NULL
              AND t.status = :reviewStatus
              AND t.currentSubmissionId IS NULL
            ORDER BY t.projectId ASC, t.id ASC
            """)
    List<TaskEntity> findLegacyReviewMissingSubmission(
            @Param("projectId") Long projectId,
            @Param("reviewStatus") TaskStatus reviewStatus);

    default List<TaskEntity> findLegacyReviewMissingSubmission(Long projectId) {
        return findLegacyReviewMissingSubmission(projectId, TaskStatus.REVIEW);
    }

    @Meta(comment = "TaskRepository.findLegacyUnverifiedDoneIds")
    @Query("""
            SELECT t.id FROM TaskEntity t
            WHERE (:projectId IS NULL OR t.projectId = :projectId)
              AND t.deletedAt IS NULL
              AND t.status = :doneStatus
              AND t.completionEvidenceStatus = :legacyStatus
            ORDER BY t.id ASC
            """)
    List<Long> findLegacyUnverifiedDoneIds(
            @Param("projectId") Long projectId,
            @Param("doneStatus") TaskStatus doneStatus,
            @Param("legacyStatus") com.taskpilot.contracts.task.EvidenceStatus legacyStatus);

    default List<Long> findLegacyUnverifiedDoneIds(Long projectId) {
        return findLegacyUnverifiedDoneIds(
                projectId,
                TaskStatus.DONE,
                com.taskpilot.contracts.task.EvidenceStatus.LEGACY_UNVERIFIED);
    }
}
