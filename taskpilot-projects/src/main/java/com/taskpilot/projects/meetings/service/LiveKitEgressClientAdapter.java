package com.taskpilot.projects.meetings.service;

import com.taskpilot.infrastructure.storage.EgressS3StorageService;
import io.livekit.server.EgressServiceClient;
import io.livekit.server.WebhookReceiver;
import livekit.LivekitEgress;
import livekit.LivekitWebhook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import retrofit2.Response;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Thin adapter wrapping the official LiveKit Java Server SDK ({@link EgressServiceClient}
 * and {@link WebhookReceiver}) so higher-level recording orchestration remains cleanly testable.
 */
@Slf4j
@Component
public class LiveKitEgressClientAdapter {

    public record ParsedWebhookEvent(String eventName, EgressSnapshot egressSnapshot) {}

    private final String livekitHttpUrl;
    private final String apiKey;
    private final String apiSecret;
    private final String defaultLayout;

    private volatile EgressServiceClient egressClient;
    private volatile WebhookReceiver webhookReceiver;

    public LiveKitEgressClientAdapter(
            @Value("${livekit.url:}") String livekitUrl,
            @Value("${livekit.api-key:}") String apiKey,
            @Value("${livekit.api-secret:}") String apiSecret,
            @Value("${egress.layout:grid}") String defaultLayout) {
        this.livekitHttpUrl = normalizeHttpUrl(livekitUrl);
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.apiSecret = apiSecret != null ? apiSecret.trim() : "";
        this.defaultLayout = (defaultLayout != null && !defaultLayout.isBlank()) ? defaultLayout.trim() : "grid";
    }

    public boolean isConfigured() {
        return !livekitHttpUrl.isBlank()
                && !apiKey.isBlank()
                && !apiSecret.isBlank()
                && !"local-dummy".equals(apiKey)
                && !"local-dummy".equals(apiSecret);
    }

    public EgressSnapshot startRoomCompositeEgress(
            String roomName,
            String filepath,
            EgressS3StorageService s3Storage) throws IOException {
        ensureConfigured();

        LivekitEgress.S3Upload s3Upload = LivekitEgress.S3Upload.newBuilder()
                .setAccessKey(s3Storage.getAccessKey())
                .setSecret(s3Storage.getSecretKey())
                .setRegion(s3Storage.getRegion())
                .setEndpoint(s3Storage.getEndpoint())
                .setBucket(s3Storage.getBucket())
                .setForcePathStyle(true)
                .build();

        LivekitEgress.EncodedFileOutput fileOutput = LivekitEgress.EncodedFileOutput.newBuilder()
                .setFileType(LivekitEgress.EncodedFileType.MP4)
                .setFilepath(filepath)
                .setS3(s3Upload)
                .build();

        Response<LivekitEgress.EgressInfo> response = getClient()
                .startRoomCompositeEgress(
                        roomName,
                        fileOutput,
                        defaultLayout,
                        LivekitEgress.EncodingOptionsPreset.H264_720P_30
                )
                .execute();

        if (!response.isSuccessful() || response.body() == null) {
            throw new IOException("LiveKit StartRoomCompositeEgress failed (HTTP " + response.code() + "): "
                    + extractErrorBody(response));
        }
        return toSnapshot(response.body());
    }

    public EgressSnapshot stopEgress(String egressId) throws IOException {
        ensureConfigured();

        Response<LivekitEgress.EgressInfo> response = getClient()
                .stopEgress(egressId)
                .execute();

        if (!response.isSuccessful() || response.body() == null) {
            throw new IOException("LiveKit StopEgress failed (HTTP " + response.code() + "): "
                    + extractErrorBody(response));
        }
        return toSnapshot(response.body());
    }

    public List<EgressSnapshot> listEgress(String roomName, String egressId) throws IOException {
        ensureConfigured();

        Response<List<LivekitEgress.EgressInfo>> response = getClient()
                .listEgress(roomName, egressId, null)
                .execute();

        if (!response.isSuccessful()) {
            throw new IOException("LiveKit ListEgress failed (HTTP " + response.code() + "): "
                    + extractErrorBody(response));
        }
        List<LivekitEgress.EgressInfo> body = response.body();
        if (body == null || body.isEmpty()) {
            return Collections.emptyList();
        }
        return body.stream().map(this::toSnapshot).toList();
    }

    public ParsedWebhookEvent receiveWebhook(String rawBody, String authorizationHeader) {
        ensureConfigured();
        if (rawBody == null || rawBody.isBlank()) {
            throw new IllegalArgumentException("Webhook body is empty");
        }
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new SecurityException("Missing Authorization header on LiveKit webhook");
        }
        LivekitWebhook.WebhookEvent event = getWebhookReceiver().receive(rawBody, authorizationHeader);
        EgressSnapshot snapshot = event.hasEgressInfo() ? toSnapshot(event.getEgressInfo()) : null;
        return new ParsedWebhookEvent(event.getEvent(), snapshot);
    }

    EgressSnapshot toSnapshot(LivekitEgress.EgressInfo info) {
        LivekitEgress.FileInfo fileInfo = null;
        if (info.getFileResultsCount() > 0) {
            fileInfo = info.getFileResults(0);
        } else if (info.getFile() != null
                && (!info.getFile().getFilename().isBlank() || info.getFile().getSize() > 0)) {
            fileInfo = info.getFile();
        }

        String filepath = fileInfo != null ? fileInfo.getFilename() : "";
        long fileSize = fileInfo != null ? fileInfo.getSize() : 0L;
        long durationNanos = fileInfo != null ? fileInfo.getDuration() : 0L;
        String status = info.getStatus() != null ? info.getStatus().name() : "UNRECOGNIZED";

        return new EgressSnapshot(
                info.getEgressId(),
                info.getRoomName(),
                status,
                info.getError(),
                info.getBackupStorageUsed(),
                filepath,
                fileSize,
                durationNanos
        );
    }

    private void ensureConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("LiveKit API credentials or URL are not configured");
        }
    }

    private EgressServiceClient getClient() {
        if (egressClient == null) {
            synchronized (this) {
                if (egressClient == null) {
                    egressClient = EgressServiceClient.createClient(livekitHttpUrl, apiKey, apiSecret);
                }
            }
        }
        return egressClient;
    }

    private WebhookReceiver getWebhookReceiver() {
        if (webhookReceiver == null) {
            synchronized (this) {
                if (webhookReceiver == null) {
                    webhookReceiver = new WebhookReceiver(apiKey, apiSecret);
                }
            }
        }
        return webhookReceiver;
    }

    static String normalizeHttpUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }
        String trimmed = rawUrl.trim();
        if (trimmed.startsWith("wss://")) {
            return "https://" + trimmed.substring("wss://".length());
        }
        if (trimmed.startsWith("ws://")) {
            return "http://" + trimmed.substring("ws://".length());
        }
        return trimmed;
    }

    private static String extractErrorBody(Response<?> response) {
        try {
            return Optional.ofNullable(response.errorBody())
                    .map(body -> {
                        try {
                            return body.string();
                        } catch (IOException e) {
                            return "";
                        }
                    })
                    .orElse("");
        } catch (Exception e) {
            return "";
        }
    }
}
