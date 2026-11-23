package com.taskpilot.app.meetings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.meetings.service.LiveKitTokenService;
import com.taskpilot.projects.meetings.service.MeetingRoomGateway;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class LiveKitRoomGateway implements MeetingRoomGateway {
    private record Result(Outcome outcome, JsonNode body) {}

    private final LiveKitTokenService tokens;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public LiveKitRoomGateway(LiveKitTokenService tokens, ObjectMapper mapper) {
        this.tokens = tokens;
        this.mapper = mapper;
    }

    @Override
    public Outcome ensureRoom(String room) {
        return call("CreateRoom", room, true, Map.of("name", room)).outcome();
    }

    @Override
    public Outcome removeParticipant(String room, String identity) {
        return call("RemoveParticipant", room, false, Map.of("room", room, "identity", identity)).outcome();
    }

    @Override
    public Outcome terminateRoom(String room) {
        return call("DeleteRoom", room, false, Map.of("room", room)).outcome();
    }

    @Override
    public Participants listParticipants(String room) {
        Result result = call("ListParticipants", room, false, Map.of("room", room));
        if (result.outcome() != Outcome.SUCCESS) return new Participants(result.outcome(), List.of());

        JsonNode participants = result.body().path("participants");
        if (!participants.isMissingNode() && !participants.isArray()) {
            return new Participants(Outcome.RETRYABLE, List.of());
        }
        List<Participant> active = new ArrayList<>();
        for (JsonNode participant : participants) {
            String sid = participant.path("sid").asText();
            String identity = participant.path("identity").asText();
            if (sid.isBlank() || identity.isBlank()) {
                return new Participants(Outcome.RETRYABLE, List.of());
            }
            active.add(new Participant(identity, sid));
        }
        return new Participants(Outcome.SUCCESS, List.copyOf(active));
    }

    private Result call(String method, String room, boolean create, Map<String, String> payload) {
        try {
            String token = tokens.createRoomServiceToken(room, create);
            URI rtcUri = URI.create(tokens.getLivekitUrl());
            String scheme = "wss".equals(rtcUri.getScheme()) ? "https" : "http";
            URI endpoint = URI.create(scheme + "://" + rtcUri.getRawAuthority() + "/twirp/livekit.RoomService/" + method);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(4))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                JsonNode body = mapper.readTree(response.body());
                return body != null && body.isObject() ? new Result(Outcome.SUCCESS, body)
                        : new Result(Outcome.RETRYABLE, null);
            }
            if (status == 408 || status == 425 || status == 429 || status >= 500) {
                return new Result(Outcome.RETRYABLE, null);
            }
            if (status == 404) {
                JsonNode error = mapper.readTree(response.body());
                if (error != null && "not_found".equals(error.path("code").asText())) {
                    return new Result(Outcome.NOT_FOUND, null);
                }
            }
            return new Result(Outcome.PERMANENT, null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.RETRYABLE, null);
        } catch (IOException ex) {
            return new Result(Outcome.RETRYABLE, null);
        } catch (BusinessException | IllegalArgumentException ex) {
            return new Result(Outcome.PERMANENT, null);
        }
    }
}
