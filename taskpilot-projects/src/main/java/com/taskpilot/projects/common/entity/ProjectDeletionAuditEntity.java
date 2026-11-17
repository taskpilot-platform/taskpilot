package com.taskpilot.projects.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_deletion_audits")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectDeletionAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, unique = true)
    private Long projectId;

    @Column(name = "project_name", nullable = false, length = 255)
    private String projectName;

    @Column(name = "deleted_by_user_id", nullable = false)
    private Long deletedByUserId;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "confirmation_text", nullable = false, length = 255)
    private String confirmationText;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "deleted_counts_json", nullable = false, columnDefinition = "TEXT")
    private String deletedCountsJson;

    @Column(name = "enqueued_cleanup_job_count", nullable = false)
    private Integer enqueuedCleanupJobCount;

    @Column(name = "deleted_at", nullable = false)
    private Instant deletedAt;
}
