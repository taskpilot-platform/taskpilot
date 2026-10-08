package com.taskpilot.projects.meetings.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateMeetingRequest(
        @NotBlank(message = "Meeting title is required")
        @Size(max = 255, message = "Meeting title cannot exceed 255 characters")
        String title,

        @Size(max = 2000, message = "Meeting description cannot exceed 2000 characters")
        String description,

        Boolean recordingEnabled
) {
}
