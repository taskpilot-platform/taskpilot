package com.taskpilot.contracts.workspace.meeting;

import java.util.List;

public interface ProjectMeetingPort {

    List<MeetingListItemDto> listMeetings(Long projectId, String status, int limit, Long requesterUserId);

    MeetingDetailDto getMeetingDetails(Long projectId, Long meetingId, Long requesterUserId);

    MeetingDetailDto scheduleMeeting(Long projectId, ScheduleMeetingCommand command, Long requesterUserId);

    MeetingSourceTextDto getMeetingSourceText(Long projectId, Long meetingId, Long requesterUserId);

    void saveAiSummary(Long projectId, Long meetingId, String summaryJson, String sourceKind, Long requesterUserId);
}
