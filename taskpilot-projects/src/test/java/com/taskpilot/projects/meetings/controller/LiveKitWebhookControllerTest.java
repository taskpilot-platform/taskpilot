package com.taskpilot.projects.meetings.controller;

import com.taskpilot.projects.meetings.service.MeetingRecordingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class LiveKitWebhookControllerTest {

    @Mock
    private MeetingRecordingService meetingRecordingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new LiveKitWebhookController(meetingRecordingService)).build();
    }

    @Test
    void acceptsApplicationWebhookJsonRawBody() throws Exception {
        String rawPayload = "{\"event\":\"egress_ended\",\"egressInfo\":{\"egressId\":\"EG_123\"}}";
        doNothing().when(meetingRecordingService).handleWebhook(rawPayload, "Bearer valid-jwt");

        mockMvc.perform(post("/api/v1/webhooks/livekit")
                        .contentType("application/webhook+json")
                        .header("Authorization", "Bearer valid-jwt")
                        .content(rawPayload))
                .andExpect(status().isOk());

        verify(meetingRecordingService).handleWebhook(rawPayload, "Bearer valid-jwt");
    }

    @Test
    void rejectsMissingAuthorizationHeaderWith401() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/livekit")
                        .contentType("application/webhook+json")
                        .content("{\"event\":\"egress_ended\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsInvalidSignatureWith401() throws Exception {
        String rawPayload = "{\"event\":\"egress_ended\"}";
        doThrow(new SecurityException("Bad JWT")).when(meetingRecordingService).handleWebhook(rawPayload, "bad-token");

        mockMvc.perform(post("/api/v1/webhooks/livekit")
                        .contentType("application/webhook+json")
                        .header("Authorization", "bad-token")
                        .content(rawPayload))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returns503WhenLiveKitNotConfigured() throws Exception {
        String rawPayload = "{\"event\":\"egress_ended\"}";
        doThrow(new IllegalStateException("LiveKit API credentials or URL are not configured"))
                .when(meetingRecordingService).handleWebhook(rawPayload, "some-jwt");

        mockMvc.perform(post("/api/v1/webhooks/livekit")
                        .contentType("application/webhook+json")
                        .header("Authorization", "some-jwt")
                        .content(rawPayload))
                .andExpect(status().isServiceUnavailable());
    }
}
