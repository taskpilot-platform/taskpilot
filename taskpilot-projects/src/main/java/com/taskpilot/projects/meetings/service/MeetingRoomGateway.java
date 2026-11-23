package com.taskpilot.projects.meetings.service;

import java.util.List;

public interface MeetingRoomGateway {
    enum Outcome { SUCCESS, NOT_FOUND, RETRYABLE, PERMANENT }

    record Participant(String identity, String sid) {}
    record Participants(Outcome outcome, List<Participant> values) {}

    Outcome ensureRoom(String room);

    Outcome removeParticipant(String room, String identity);

    Outcome terminateRoom(String room);

    Participants listParticipants(String room);
}
