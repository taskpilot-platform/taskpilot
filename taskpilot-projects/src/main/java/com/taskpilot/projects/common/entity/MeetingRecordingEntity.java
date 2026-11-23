package com.taskpilot.projects.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_meeting_recordings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MeetingRecordingEntity {
    public enum State { REQUESTED, CAPTURING, FINALIZING, UPLOADING, READY, FAILED, INTERRUPTED }
    public enum Origin { BROWSER, IMPORT, CLOUD_EGRESS }
    public enum Coverage {
        SCREEN_ONLY, SCREEN_LOCAL_MIC, SCREEN_SOURCE_AUDIO, SCREEN_SOURCE_AUDIO_LOCAL_MIC, IMPORTED_FILE, CLOUD_COMPOSITE
    }

    @Id
    private UUID id;
    private Long meetingId;
    private Long projectId;
    private Long recorderId;
    private Long projectFileId;
    @Enumerated(EnumType.STRING)
    private Origin origin;
    @Enumerated(EnumType.STRING)
    private Coverage coverage;
    @Enumerated(EnumType.STRING)
    private State state;
    @Version
    private Long version;
    private Long durationMs;
    private Long byteLength;
    private String mimeType;
    private String checksum;
    private Instant createdAt;
    private Instant expiresAt;
}
