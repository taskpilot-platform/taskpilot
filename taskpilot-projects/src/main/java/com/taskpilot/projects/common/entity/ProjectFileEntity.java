package com.taskpilot.projects.common.entity;

import com.taskpilot.projects.common.enums.FileArchiveStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "project_files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectFileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "uploader_id")
    private Long uploaderId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "original_name", nullable = false)
    private String originalName;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "storage_bucket", nullable = false)
    @Builder.Default
    private String storageBucket = "documents";

    @Column(name = "storage_provider", nullable = false, length = 32)
    @Builder.Default
    private String storageProvider = "s3";

    @Column(name = "source_egress_id", length = 128)
    private String sourceEgressId;

    @Enumerated(EnumType.STRING)
    @Column(name = "archive_status", nullable = false, length = 32)
    @Builder.Default
    private FileArchiveStatus archiveStatus = FileArchiveStatus.NONE;

    @Column(name = "archive_retry_count", nullable = false)
    @Builder.Default
    private Integer archiveRetryCount = 0;

    @Column(name = "archive_error", columnDefinition = "TEXT")
    private String archiveError;

    @Column(name = "content_md5", length = 64)
    private String contentMd5;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
