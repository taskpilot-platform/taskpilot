package com.taskpilot.projects.common.entity;

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
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "project_files_id_seq_gen")
    @SequenceGenerator(name = "project_files_id_seq_gen", sequenceName = "project_files_id_seq", allocationSize = 1)
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

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
