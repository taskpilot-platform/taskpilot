package com.taskpilot.app.meetings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.projects.meetings.service.LiveKitTokenService;
import com.taskpilot.projects.meetings.service.MeetingRoomLifecycle;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Set;

@RestController
@RequiredArgsConstructor
public class LiveKitRoomWebhookController {
    private static final int MAX_EVENT_BYTES = 64 * 1024;
    private static final Set<String> ROOM_EVENTS = Set.of("room_started", "room_finished", "participant_joined", "participant_left");

    private final LiveKitTokenService tokens;
    private final MeetingRoomLifecycle lifecycle;
    private final ObjectMapper mapper;

    @PostMapping("/api/v1/integrations/livekit/room-events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void receive(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_EVENT_BYTES + 1);
        if (body.length > MAX_EVENT_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        if (!tokens.verifiesWebhook(request.getHeader("Authorization"), body)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        var event = mapper.readTree(body);
        if (event == null || !event.isObject()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String room = event.path("room").path("name").asText();
        if (room.startsWith("tp-proj-") && room.length() <= 128 && ROOM_EVENTS.contains(event.path("event").asText())) {
            lifecycle.reconcileRoomFromProvider(room);
        }
    }
}
