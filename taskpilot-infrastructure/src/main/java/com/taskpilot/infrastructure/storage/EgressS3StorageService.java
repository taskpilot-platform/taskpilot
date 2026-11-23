package com.taskpilot.infrastructure.storage;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated S3-compatible client for LiveKit Egress recordings (Backblaze B2).
 * Enforces path-style routing, optional fail-fast startup validation, presigned GET URLs,
 * and byte-range streaming without full-file heap buffering.
 */
@Slf4j
@Service
public class EgressS3StorageService {

    public record PresignedUrlResult(String url, Instant expiresAt) {}

    public record ObjectMetadata(long contentLength, String contentType, String eTag) {}

    @Getter
    private final boolean enabled;
    private final boolean validateOnStartup;
    @Getter
    private final String endpoint;
    @Getter
    private final String region;
    @Getter
    private final String bucket;
    @Getter
    private final String accessKey;
    @Getter
    private final String secretKey;
    @Getter
    private final Duration presignGetTtl;
    @Getter
    private final Duration presignPutTtl;

    private volatile S3Client s3Client;
    private volatile S3Presigner s3Presigner;

    public EgressS3StorageService(
            boolean enabled,
            boolean validateOnStartup,
            String endpoint,
            String region,
            String bucket,
            String accessKey,
            String secretKey,
            long presignGetTtlMinutes) {
        this(enabled, validateOnStartup, endpoint, region, bucket, accessKey, secretKey, presignGetTtlMinutes, 20L);
    }

    @Autowired
    public EgressS3StorageService(
            @Value("${egress.enabled:true}") boolean enabled,
            @Value("${egress.validate-on-startup:false}") boolean validateOnStartup,
            @Value("${egress.s3.endpoint:}") String endpoint,
            @Value("${egress.s3.region:}") String region,
            @Value("${egress.s3.bucket:}") String bucket,
            @Value("${egress.s3.access-key:}") String accessKey,
            @Value("${egress.s3.secret-key:}") String secretKey,
            @Value("${egress.s3.presign-get-ttl-minutes:60}") long presignGetTtlMinutes,
            @Value("${egress.s3.presign-put-ttl-minutes:20}") long presignPutTtlMinutes) {
        this.enabled = enabled;
        this.validateOnStartup = validateOnStartup;
        this.endpoint = trimOrEmpty(endpoint);
        this.region = trimOrEmpty(region);
        this.bucket = trimOrEmpty(bucket);
        this.accessKey = trimOrEmpty(accessKey);
        this.secretKey = trimOrEmpty(secretKey);
        this.presignGetTtl = Duration.ofMinutes(Math.max(1, presignGetTtlMinutes));
        this.presignPutTtl = Duration.ofMinutes(Math.max(1, Math.min(30, presignPutTtlMinutes)));
    }

    @PostConstruct
    public void initAndValidate() {
        if (!enabled) {
            log.info("LiveKit Egress S3 storage is disabled (egress.enabled=false).");
            return;
        }
        List<String> missing = missingConfigurationFields();
        if (!missing.isEmpty()) {
            String msg = "Backblaze B2 Egress S3 storage is misconfigured; missing/invalid: " + String.join(", ", missing);
            if (validateOnStartup) {
                throw new IllegalStateException(msg);
            }
            log.warn("{} (egress.validate-on-startup=false; Egress recording will be unavailable until configured)", msg);
            return;
        }
        initializeClients();
        log.info("Initialized Backblaze B2 Egress S3 storage: endpoint={}, region={}, bucket={}", endpoint, region, bucket);
    }

    public boolean isConfigured() {
        return enabled && missingConfigurationFields().isEmpty();
    }

    public List<String> missingConfigurationFields() {
        List<String> missing = new ArrayList<>();
        if (endpoint.isBlank() || (!endpoint.startsWith("https://") && !endpoint.startsWith("http://"))) {
            missing.add("egress.s3.endpoint");
        }
        if (region.isBlank()) {
            missing.add("egress.s3.region");
        }
        if (bucket.isBlank()) {
            missing.add("egress.s3.bucket");
        }
        if (accessKey.isBlank()) {
            missing.add("egress.s3.access-key");
        }
        if (secretKey.isBlank()) {
            missing.add("egress.s3.secret-key");
        }
        return missing;
    }

    public PresignedUrlResult generatePresignedGetUrl(String key, Duration ttl) {
        ensureConfigured();
        String cleanedKey = cleanKey(key);
        Duration effectiveTtl = (ttl != null && !ttl.isZero() && !ttl.isNegative()) ? ttl : presignGetTtl;

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(cleanedKey)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(effectiveTtl)
                .getObjectRequest(getObjectRequest)
                .build();

        PresignedGetObjectRequest presigned = getPresigner().presignGetObject(presignRequest);
        Instant expiresAt = presigned.expiration() != null ? presigned.expiration() : Instant.now().plus(effectiveTtl);
        return new PresignedUrlResult(presigned.url().toString(), expiresAt);
    }

    public PresignedUrlResult generatePresignedGetUrl(String key) {
        return generatePresignedGetUrl(key, presignGetTtl);
    }

    /**
     * Generates a short-lived presigned PUT URL bound to an exact object key and Content-Type.
     */
    public PresignedUrlResult generatePresignedPutUrl(String key, String contentType, Duration ttl) {
        ensureConfigured();
        String cleanedKey = cleanKey(key);
        Duration effectiveTtl = (ttl != null && !ttl.isZero() && !ttl.isNegative()) ? ttl : presignPutTtl;
        if (effectiveTtl.compareTo(Duration.ofMinutes(30)) > 0) {
            effectiveTtl = Duration.ofMinutes(30);
        }

        PutObjectRequest.Builder putBuilder = PutObjectRequest.builder()
                .bucket(bucket)
                .key(cleanedKey);
        if (contentType != null && !contentType.isBlank()) {
            putBuilder.contentType(contentType.trim());
        }

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(effectiveTtl)
                .putObjectRequest(putBuilder.build())
                .build();

        PresignedPutObjectRequest presigned = getPresigner().presignPutObject(presignRequest);
        Instant expiresAt = presigned.expiration() != null ? presigned.expiration() : Instant.now().plus(effectiveTtl);
        return new PresignedUrlResult(presigned.url().toString(), expiresAt);
    }

    public PresignedUrlResult generatePresignedPutUrl(String key, String contentType) {
        return generatePresignedPutUrl(key, contentType, presignPutTtl);
    }

    public ObjectMetadata headObject(String key) throws IOException {
        ensureConfigured();
        String cleanedKey = cleanKey(key);
        try {
            HeadObjectResponse head = getClient().headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(cleanedKey)
                    .build());
            return new ObjectMetadata(
                    head.contentLength() != null ? head.contentLength() : 0L,
                    head.contentType(),
                    head.eTag()
            );
        } catch (Exception e) {
            throw new IOException("Failed to stat object in B2: bucket=" + bucket + ", key=" + cleanedKey + ": " + e.getMessage(), e);
        }
    }

    public InputStream downloadFile(String key) throws IOException {
        ensureConfigured();
        String cleanedKey = cleanKey(key);
        try {
            return getClient().getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(cleanedKey)
                    .build());
        } catch (Exception e) {
            throw new IOException("Failed to download object from B2: key=" + cleanedKey + ": " + e.getMessage(), e);
        }
    }

    public InputStream downloadFileRange(String key, long start, long end) throws IOException {
        ensureConfigured();
        String cleanedKey = cleanKey(key);
        try {
            return getClient().getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(cleanedKey)
                    .range("bytes=" + start + "-" + end)
                    .build());
        } catch (Exception e) {
            throw new IOException("Failed to download byte range from B2: key=" + cleanedKey + ": " + e.getMessage(), e);
        }
    }

    public void deleteFile(String key) {
        try {
            deleteFileOrThrow(key);
        } catch (Exception e) {
            log.error("Failed to delete object from B2: bucket={}, key={}, error={}", bucket, cleanKey(key), e.getMessage());
        }
    }

    public void deleteFileOrThrow(String key) throws IOException {
        if (key == null || key.isBlank() || !isConfigured()) {
            return;
        }
        String cleanedKey = cleanKey(key);
        try {
            getClient().deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(cleanedKey)
                    .build());
            log.info("Deleted object from B2: bucket={}, key={}", bucket, cleanedKey);
        } catch (Exception e) {
            throw new IOException("Failed to delete object from B2: bucket=" + bucket + ", key=" + cleanedKey + ": " + e.getMessage(), e);
        }
    }

    @PreDestroy
    public void close() {
        if (s3Presigner != null) {
            try {
                s3Presigner.close();
            } catch (Exception ignored) {
            }
        }
        if (s3Client != null) {
            try {
                s3Client.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void ensureConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("Backblaze B2 Egress S3 storage is not configured: missing "
                    + String.join(", ", missingConfigurationFields()));
        }
        if (s3Client == null || s3Presigner == null) {
            synchronized (this) {
                if (s3Client == null || s3Presigner == null) {
                    initializeClients();
                }
            }
        }
    }

    private void initializeClients() {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);
        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(credentials);
        URI endpointUri = URI.create(endpoint);
        Region awsRegion = Region.of(region);

        this.s3Client = S3Client.builder()
                .endpointOverride(endpointUri)
                .region(awsRegion)
                .credentialsProvider(credentialsProvider)
                .forcePathStyle(true)
                .build();

        this.s3Presigner = S3Presigner.builder()
                .endpointOverride(endpointUri)
                .region(awsRegion)
                .credentialsProvider(credentialsProvider)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    private S3Client getClient() {
        ensureConfigured();
        return s3Client;
    }

    private S3Presigner getPresigner() {
        ensureConfigured();
        return s3Presigner;
    }

    private static String cleanKey(String key) {
        if (key == null) {
            return "";
        }
        String trimmed = key.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }

    private static String trimOrEmpty(String value) {
        return value != null ? value.trim() : "";
    }
}
