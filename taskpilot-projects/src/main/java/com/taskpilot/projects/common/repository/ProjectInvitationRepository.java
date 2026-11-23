package com.taskpilot.projects.common.repository;

import com.taskpilot.projects.common.entity.ProjectInvitationEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Meta;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProjectInvitationRepository extends JpaRepository<ProjectInvitationEntity, Long> {

    @Meta(comment = "ProjectInvitationRepository.findProjectIdByTokenHash")
    @Query("SELECT i.projectId FROM ProjectInvitationEntity i WHERE i.tokenHash = :tokenHash")
    Optional<Long> findProjectIdByTokenHash(@Param("tokenHash") String tokenHash);

    @Meta(comment = "ProjectInvitationRepository.findByTokenHashForUpdate")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM ProjectInvitationEntity i WHERE i.tokenHash = :tokenHash")
    Optional<ProjectInvitationEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Meta(comment = "ProjectInvitationRepository.findByIdAndProjectIdForUpdate")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM ProjectInvitationEntity i WHERE i.id = :id AND i.projectId = :projectId")
    Optional<ProjectInvitationEntity> findByIdAndProjectIdForUpdate(
            @Param("id") Long id,
            @Param("projectId") Long projectId);

    @Meta(comment = "ProjectInvitationRepository.findPendingByProjectIdAndEmailForUpdate")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM ProjectInvitationEntity i "
            + "WHERE i.projectId = :projectId AND i.inviteeEmail = :email "
            + "AND i.consumedAt IS NULL AND i.revokedAt IS NULL")
    List<ProjectInvitationEntity> findPendingByProjectIdAndEmailForUpdate(
            @Param("projectId") Long projectId,
            @Param("email") String email);

    @Meta(comment = "ProjectInvitationRepository.revokePendingByProjectIdAndEmail")
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProjectInvitationEntity i SET i.revokedAt = :now, i.revokedByUserId = :actorId, i.revocationReason = :reason "
            + "WHERE i.projectId = :projectId AND i.inviteeEmail = :email "
            + "AND i.consumedAt IS NULL AND i.revokedAt IS NULL")
    int revokePendingByProjectIdAndEmail(
            @Param("projectId") Long projectId,
            @Param("email") String email,
            @Param("now") Instant now,
            @Param("actorId") Long actorId,
            @Param("reason") String reason);

    List<ProjectInvitationEntity> findByProjectIdOrderByIssuedAtDesc(Long projectId);
}
