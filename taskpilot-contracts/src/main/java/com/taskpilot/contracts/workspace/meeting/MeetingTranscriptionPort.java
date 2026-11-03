package com.taskpilot.contracts.workspace.meeting;

import java.util.Optional;

public interface MeetingTranscriptionPort {

    Optional<String> transcribeRecording(Long projectId, Long meetingId);
}
