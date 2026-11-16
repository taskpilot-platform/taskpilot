package com.taskpilot.projects.common.repository;

import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.enums.FileArchiveStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
public interface ProjectFileRepository extends JpaRepository<ProjectFileEntity, Long> {

    List<ProjectFileEntity> findByProjectId(Long projectId);

    Page<ProjectFileEntity> findByProjectId(Long projectId, Pageable pageable);

    Page<ProjectFileEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId, Pageable pageable);

    Page<ProjectFileEntity> findByProjectIdAndOriginalNameContainingIgnoreCase(Long projectId, String keyword, Pageable pageable);

    Page<ProjectFileEntity> findByProjectIdAndOriginalNameContainingIgnoreCaseOrderByCreatedAtDesc(Long projectId, String keyword, Pageable pageable);

    Optional<ProjectFileEntity> findByIdAndProjectId(Long id, Long projectId);

    Optional<ProjectFileEntity> findBySourceEgressId(String sourceEgressId);

    Optional<ProjectFileEntity> findByProjectIdAndStorageKey(Long projectId, String storageKey);

    long countByProjectId(Long projectId);

    @Query("""
            SELECT f FROM ProjectFileEntity f
            WHERE f.storageProvider = 'b2'
              AND f.archiveStatus IN :statuses
              AND f.archiveRetryCount < :maxRetries
              AND f.createdAt <= :cutoff
              AND (
                  f.archiveStatus = com.taskpilot.projects.common.enums.FileArchiveStatus.ARCHIVE_PENDING
                  OR f.updatedAt <= :retryCutoff
              )
            ORDER BY f.createdAt ASC
            """)
    List<ProjectFileEntity> findEligibleForArchive(
            @Param("statuses") Collection<FileArchiveStatus> statuses,
            @Param("maxRetries") int maxRetries,
            @Param("cutoff") Instant cutoff,
            @Param("retryCutoff") Instant retryCutoff,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ProjectFileEntity f
            SET f.archiveStatus = com.taskpilot.projects.common.enums.FileArchiveStatus.ARCHIVE_PENDING,
                f.archiveError = 'Recovered stale ARCHIVING lock after restart',
                f.updatedAt = :now
            WHERE f.archiveStatus = com.taskpilot.projects.common.enums.FileArchiveStatus.ARCHIVING
              AND f.updatedAt < :staleBefore
            """)
    int resetStaleArchiving(@Param("staleBefore") Instant staleBefore, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ProjectFileEntity f
            SET f.archiveStatus = com.taskpilot.projects.common.enums.FileArchiveStatus.ARCHIVING,
                f.updatedAt = :now
            WHERE f.id = :id
              AND f.storageProvider = 'b2'
              AND f.archiveStatus IN :claimableStatuses
            """)
    int claimForArchiving(
            @Param("id") Long id,
            @Param("claimableStatuses") Collection<FileArchiveStatus> claimableStatuses,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ProjectFileEntity f
            SET f.storageProvider = 'gdrive',
                f.storageBucket = 'googledrive',
                f.storageKey = :driveFileId,
                f.fileSize = :verifiedSize,
                f.contentMd5 = :md5Hex,
                f.archiveStatus = com.taskpilot.projects.common.enums.FileArchiveStatus.ARCHIVED,
                f.archiveError = null,
                f.updatedAt = :now
            WHERE f.id = :id
              AND f.archiveStatus = com.taskpilot.projects.common.enums.FileArchiveStatus.ARCHIVING
            """)
    int completeArchiveToGoogleDrive(
            @Param("id") Long id,
            @Param("driveFileId") String driveFileId,
            @Param("verifiedSize") long verifiedSize,
            @Param("md5Hex") String md5Hex,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ProjectFileEntity f
            SET f.archiveStatus = com.taskpilot.projects.common.enums.FileArchiveStatus.ARCHIVE_FAILED,
                f.archiveRetryCount = f.archiveRetryCount + 1,
                f.archiveError = :error,
                f.updatedAt = :now
            WHERE f.id = :id
              AND f.archiveStatus = com.taskpilot.projects.common.enums.FileArchiveStatus.ARCHIVING
            """)
    int markArchiveFailed(
            @Param("id") Long id,
            @Param("error") String error,
            @Param("now") Instant now
    );
}
