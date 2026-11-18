package com.taskpilot.projects.meetings.controller;

import com.taskpilot.infrastructure.config.security.CurrentActorResolver;
import com.taskpilot.projects.common.entity.MeetingRecordingEntity.Coverage;
import com.taskpilot.projects.common.entity.MeetingRecordingEntity.Origin;
import com.taskpilot.projects.common.entity.MeetingRecordingEntity.State;
import com.taskpilot.projects.meetings.service.ClientMeetingRecordingService;
import com.taskpilot.projects.meetings.service.ClientMeetingRecordingService.RecordingView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/projects/{projectId}/meetings/{meetingId}/recordings",
        "/api/projects/{projectId}/meetings/{meetingId}/recordings"})
@RequiredArgsConstructor
public class MeetingRecordingController {
    private final ClientMeetingRecordingService recordings;

    public record BeginRequest(@NotNull UUID idempotencyKey, @NotNull Origin origin, @NotNull Coverage coverage) {}
    public record StateRequest(@NotNull State state, @NotNull @PositiveOrZero Long expectedVersion) {}
    public record FinalizeRequest(@NotNull @Positive Long projectFileId, @Positive Long durationMs,
                                  @NotNull @Positive @Max(ClientMeetingRecordingService.MAX_BYTES) Long byteLength,
                                  @NotBlank @jakarta.validation.constraints.Size(max = 96) String mimeType,
                                  @NotBlank @Pattern(regexp = "[A-Fa-f0-9]{64}") String checksum,
                                  @NotNull @PositiveOrZero Long expectedVersion) {}

    @GetMapping
    public List<RecordingView> list(@PathVariable Long projectId, @PathVariable Long meetingId,
                                    Authentication authentication) {
        return recordings.list(projectId, meetingId, CurrentActorResolver.userId(authentication));
    }

    @PostMapping
    public RecordingView begin(@PathVariable Long projectId, @PathVariable Long meetingId,
                               Authentication authentication, @Valid @RequestBody BeginRequest request) {
        return recordings.begin(projectId, meetingId, CurrentActorResolver.userId(authentication),
                request.idempotencyKey(), request.origin(), request.coverage());
    }

    @PostMapping("/{recordingId}/state")
    public RecordingView transition(@PathVariable Long projectId, @PathVariable Long meetingId,
                                    @PathVariable UUID recordingId, Authentication authentication,
                                    @Valid @RequestBody StateRequest request) {
        return recordings.transition(projectId, meetingId, CurrentActorResolver.userId(authentication),
                recordingId, request.state(), request.expectedVersion());
    }

    @PostMapping("/{recordingId}/failed")
    public RecordingView fail(@PathVariable Long projectId, @PathVariable Long meetingId,
                              @PathVariable UUID recordingId, Authentication authentication,
                              @Valid @RequestBody StateRequest request) {
        return recordings.transition(projectId, meetingId, CurrentActorResolver.userId(authentication),
                recordingId, State.FAILED, request.expectedVersion());
    }

    @PostMapping("/{recordingId}/finalize")
    public RecordingView finish(@PathVariable Long projectId, @PathVariable Long meetingId,
                                @PathVariable UUID recordingId, Authentication authentication,
                                @Valid @RequestBody FinalizeRequest request) {
        return recordings.finalizeRecording(projectId, meetingId, CurrentActorResolver.userId(authentication),
                recordingId, request.projectFileId(), request.durationMs(), request.byteLength(),
                request.mimeType(), request.checksum(), request.expectedVersion());
    }
}
