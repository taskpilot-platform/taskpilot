package com.taskpilot.projects.common.entity;

import com.taskpilot.projects.common.enums.MeetingRecordingStatus;
import com.taskpilot.projects.common.enums.ProjectMeetingStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "project_meetings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectMeetingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "host_id")
    private Long hostId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "room_name", nullable = false, unique = true)
    private String roomName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private ProjectMeetingStatus status = ProjectMeetingStatus.ACTIVE;

    @Column(name = "recording_enabled", nullable = false)
    @Builder.Default
    private Boolean recordingEnabled = false;

    @Column(name = "recording_file_id")
    private Long recordingFileId;

    @Column(name = "egress_id", length = 128)
    private String egressId;

    @Enumerated(EnumType.STRING)
    @Column(name = "recording_status", nullable = false, length = 32)
    @Builder.Default
    private MeetingRecordingStatus recordingStatus = MeetingRecordingStatus.IDLE;

    @Column(name = "recording_error", columnDefinition = "TEXT")
    private String recordingError;

    @Column(name = "backup_storage_used", nullable = false)
    @Builder.Default
    private Boolean backupStorageUsed = false;

    @Column(name = "scheduled_start_time")
    private Instant scheduledStartTime;

    @Column(name = "scheduled_end_time")
    private Instant scheduledEndTime;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "transcript", columnDefinition = "TEXT")
    private String transcript;

    @Column(name = "ai_summary", columnDefinition = "TEXT")
    private String aiSummary;

    @Column(name = "ai_summary_generated_at")
    private Instant aiSummaryGeneratedAt;

    @Column(name = "action_items_json", columnDefinition = "TEXT")
    private String actionItemsJson;

    @Column(name = "started_at")
    @Builder.Default
    private Instant startedAt = Instant.now();

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "room_cleanup_at")
    private Instant roomCleanupAt;

    @Column(name = "ending_requested_at")
    private Instant endingRequestedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
