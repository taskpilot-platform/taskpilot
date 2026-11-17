package com.taskpilot.projects.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "task_submission_evidence_files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskSubmissionEvidenceFileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "submission_id", nullable = false)
    private Long submissionId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "live_source_file_id")
    private Long liveSourceFileId;

    @Column(name = "original_file_id_snapshot", nullable = false)
    private Long originalFileIdSnapshot;

    @Column(name = "original_name", nullable = false)
    private String originalName;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "storage_bucket", nullable = false)
    private String storageBucket;

    @Column(name = "content_identity")
    private String contentIdentity;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Builder.Default
    @Column(nullable = false)
    private Boolean available = Boolean.TRUE;
}
