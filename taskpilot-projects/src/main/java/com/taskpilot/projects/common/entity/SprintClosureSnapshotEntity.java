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
@Table(name = "sprint_closure_snapshots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SprintClosureSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sprint_id", nullable = false, unique = true)
    private Long sprintId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "sprint_name", nullable = false, length = 255)
    private String sprintName;

    @Column(name = "sprint_version_at_close", nullable = false)
    private Long sprintVersionAtClose;

    @Column(name = "carryover_destination", nullable = false, length = 32)
    private String carryoverDestination;

    @Column(name = "target_sprint_id")
    private Long targetSprintId;

    @Column(name = "total_tasks_at_close", nullable = false)
    private Integer totalTasksAtClose;

    @Column(name = "done_tasks_retained", nullable = false)
    private Integer doneTasksRetained;

    @Column(name = "carried_over_total", nullable = false)
    private Integer carriedOverTotal;

    @Column(name = "carried_over_todo", nullable = false)
    private Integer carriedOverTodo;

    @Column(name = "carried_over_in_progress", nullable = false)
    private Integer carriedOverInProgress;

    @Column(name = "carried_over_review", nullable = false)
    private Integer carriedOverReview;

    @Column(name = "snapshot_payload_json", nullable = false, columnDefinition = "TEXT")
    private String snapshotPayloadJson;

    @Column(name = "closed_by_user_id", nullable = false)
    private Long closedByUserId;

    @Column(name = "closed_at", nullable = false)
    private Instant closedAt;

    @Column(name = "request_id")
    private UUID requestId;
}
