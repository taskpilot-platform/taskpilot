package com.taskpilot.infrastructure.notification;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class OneSignalService {

    private static final String ONESIGNAL_URL = "https://onesignal.com/api/v1/notifications";

    @Value("${onesignal.app-id:}")
    private String appId;

    @Value("${onesignal.api-key:}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    public boolean sendNotificationToUser(String targetUserId, String title, String message) {
        if (appId == null || appId.isBlank() || apiKey == null || apiKey.isBlank()) {
            log.warn("OneSignal config missing. Skip push send for userId={}", targetUserId);
            return false;
        }

        Map<String, Object> body = new HashMap<>();
        body.put("app_id", appId);
        body.put("target_channel", "push");
        body.put("include_aliases", Map.of("external_id", List.of(targetUserId)));
        body.put("headings", Map.of("en", title));
        body.put("contents", Map.of("en", message));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Basic " + apiKey);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(ONESIGNAL_URL, request, String.class);
            log.info("OneSignal push sent to userId={} response={}", targetUserId, response.getBody());
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException ex) {
            log.error("OneSignal push failed for userId={}: {}", targetUserId, ex.getMessage());
            return false;
        }
    }
}
