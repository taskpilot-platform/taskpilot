package com.taskpilot.projects.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "task_reviews")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskReviewEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "submission_id", nullable = false)
    private Long submissionId;

    @Column(name = "reviewer_id", nullable = false)
    private Long reviewerId;

    @Column(nullable = false, length = 32)
    private String decision;

    @Column(name = "quality_rating")
    private Integer qualityRating;

    @Column(name = "rubric_version", length = 32)
    private String rubricVersion;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String feedback;

    @Column(name = "evidence_summary", columnDefinition = "TEXT")
    private String evidenceSummary;

    @Builder.Default
    @Column(name = "self_attested", nullable = false)
    private Boolean selfAttested = Boolean.FALSE;

    @Column(name = "supersedes_review_id")
    private Long supersedesReviewId;

    @Column(name = "reviewed_at", nullable = false)
    private Instant reviewedAt;
}
