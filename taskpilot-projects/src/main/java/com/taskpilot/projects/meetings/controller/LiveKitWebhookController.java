package com.taskpilot.projects.meetings.controller;

import com.taskpilot.projects.meetings.service.MeetingRecordingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives signed webhooks from LiveKit Cloud ({@code Content-Type: application/webhook+json}).
 * Reads the raw request body untouched so {@code WebhookReceiver} can verify the JWT + SHA-256 signature.
 */
@Slf4j
@Tag(name = "17. Project Meetings", description = "LiveKit Cloud webhook receiver")
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class LiveKitWebhookController {

    private final MeetingRecordingService meetingRecordingService;

    @Operation(summary = "Receive LiveKit Cloud webhook", description = "Verifies LiveKit JWT+SHA256 signature and processes Egress events")
    @PostMapping(
            value = "/livekit",
            consumes = {"application/webhook+json", MediaType.APPLICATION_JSON_VALUE, MediaType.ALL_VALUE}
    )
    public ResponseEntity<String> handleLiveKitWebhook(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) String rawBody) {
        if (authorization == null || authorization.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing Authorization header");
        }
        if (rawBody == null || rawBody.isBlank()) {
            return ResponseEntity.badRequest().body("Empty webhook body");
        }
        try {
            meetingRecordingService.handleWebhook(rawBody, authorization);
            return ResponseEntity.ok("OK");
        } catch (SecurityException e) {
            log.warn("Rejected LiveKit webhook with invalid signature: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid webhook signature");
        } catch (IllegalArgumentException e) {
            log.warn("Rejected invalid LiveKit webhook: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid webhook payload or signature");
        } catch (IllegalStateException e) {
            log.warn("LiveKit webhook rejected because LiveKit is not configured: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("LiveKit webhook service is not configured");
        } catch (Exception e) {
            log.error("Error processing LiveKit webhook: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Webhook processing error");
        }
    }
}
