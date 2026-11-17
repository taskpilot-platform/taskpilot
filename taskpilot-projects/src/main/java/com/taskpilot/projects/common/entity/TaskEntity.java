package com.taskpilot.projects.common.entity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.taskpilot.contracts.task.EvidenceStatus;
import com.taskpilot.projects.common.enums.PriorityLevel;
import com.taskpilot.projects.common.enums.TaskStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "tasks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "tasks_id_seq_gen")
    @SequenceGenerator(name = "tasks_id_seq_gen", sequenceName = "tasks_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "sprint_id")
    private Long sprintId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    private TaskStatus status = TaskStatus.TODO;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    private PriorityLevel priority = PriorityLevel.MEDIUM;

    @Builder.Default
    @Column(nullable = false)
    private Float position = 0f;

    @Builder.Default
    @Column(name = "difficulty_level")
    private Integer difficultyLevel = 1;

    @Column(name = "assignee_id")
    private Long assigneeId;

    @Column(name = "reporter_id")
    private Long reporterId;

    @Column(name = "start_date")
    private Instant startDate;

    @Column(name = "due_date")
    private Instant dueDate;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Builder.Default
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Builder.Default
    @Column(name = "assignment_version", nullable = false)
    private Long assignmentVersion = 0L;

    @Builder.Default
    @Column(name = "updated_at_legacy", nullable = false)
    private Boolean updatedAtLegacy = Boolean.FALSE;

    @Column(name = "current_submission_id")
    private Long currentSubmissionId;

    @Column(name = "current_evaluation_id")
    private Long currentEvaluationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "completion_evidence_status", length = 32)
    private EvidenceStatus completionEvidenceStatus;

    @Builder.Default
    @Column(name = "criteria_revision", nullable = false)
    private Long criteriaRevision = 0L;

    @Builder.Default
    @Column(name = "due_commitment_revision", nullable = false)
    private Long dueCommitmentRevision = 0L;

    @Column(name = "assignment_decision_id", length = 64)
    private String assignmentDecisionId;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by_user_id")
    private Long deletedByUserId;

    @Column(name = "deletion_reason", length = 255)
    private String deletionReason;

    @Column(name = "successor_of_task_id")
    private Long successorOfTaskId;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    @PrePersist
    void onPrePersist() {
        if (updatedAt == null) {
            updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
        if (version == null) {
            version = 0L;
        }
        if (assignmentVersion == null) {
            assignmentVersion = 0L;
        }
        if (updatedAtLegacy == null) {
            updatedAtLegacy = Boolean.FALSE;
        }
        if (criteriaRevision == null) {
            criteriaRevision = 0L;
        }
        if (dueCommitmentRevision == null) {
            dueCommitmentRevision = 0L;
        }
    }

    @PreUpdate
    void onPreUpdate() {
        // Fallback when internal lifecycle listeners (e.g. roster removal) update tasks outside TaskCommandService.
        if (updatedAt == null) {
            updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
        updatedAtLegacy = Boolean.FALSE;
    }

    public long effectiveVersion() {
        return version != null ? version : 0L;
    }

    public long effectiveAssignmentVersion() {
        return assignmentVersion != null ? assignmentVersion : 0L;
    }

    public long bumpAssignmentVersion() {
        long next = effectiveAssignmentVersion() + 1L;
        this.assignmentVersion = next;
        return next;
    }

    public long effectiveCriteriaRevision() {
        return criteriaRevision != null ? criteriaRevision : 0L;
    }

    public long bumpCriteriaRevision() {
        long next = effectiveCriteriaRevision() + 1L;
        this.criteriaRevision = next;
        return next;
    }

    public long effectiveDueCommitmentRevision() {
        return dueCommitmentRevision != null ? dueCommitmentRevision : 0L;
    }

    public long bumpDueCommitmentRevision() {
        long next = effectiveDueCommitmentRevision() + 1L;
        this.dueCommitmentRevision = next;
        return next;
    }

    public void markMutated(Instant txNow) {
        this.updatedAt = (txNow != null ? txNow : Instant.now()).truncatedTo(ChronoUnit.MICROS);
        this.updatedAtLegacy = Boolean.FALSE;
    }

    public boolean updateAssignee(Long nextAssigneeId) {
        if (java.util.Objects.equals(this.assigneeId, nextAssigneeId)) {
            return false;
        }
        this.assigneeId = nextAssigneeId;
        bumpAssignmentVersion();
        return true;
    }
}
