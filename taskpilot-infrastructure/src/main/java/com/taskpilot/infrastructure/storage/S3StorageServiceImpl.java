package com.taskpilot.infrastructure.storage;

import com.taskpilot.infrastructure.storage.range.ByteRange;
import com.taskpilot.infrastructure.storage.range.ProviderRangeValidator;
import com.taskpilot.infrastructure.storage.range.StorageDeleteResult;
import com.taskpilot.infrastructure.storage.range.StorageObjectStat;
import com.taskpilot.infrastructure.storage.range.StorageReadRequest;
import com.taskpilot.infrastructure.storage.range.StorageReadResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class S3StorageServiceImpl implements StorageService {

    private final S3Client s3Client;
    private final String defaultBucketName;
    private final String publicUrlPrefix;

    @Autowired
    public S3StorageServiceImpl(
            @Value("${supabase.s3.endpoint}") String endpoint,
            @Value("${supabase.s3.access-key}") String accessKey,
            @Value("${supabase.s3.secret-key}") String secretKey,
            @Value("${supabase.s3.region}") String region,
            @Value("${supabase.s3.bucket:avatars}") String bucketName,
            @Value("${supabase.s3.public-url:}") String publicUrlPrefix) {

        this.defaultBucketName = bucketName;
        this.publicUrlPrefix = publicUrlPrefix;

        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);
        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .forcePathStyle(true) // Supabase requires path-style access for S3
                .build();
    }

    @Override
    public String planObjectKey(String folder, String originalFilename) {
        String sanitizedFilename = (originalFilename != null && !originalFilename.isBlank())
                ? originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_")
                : "file";
        String prefix = (folder != null && !folder.isEmpty())
                ? (folder.endsWith("/") ? folder : folder + "/")
                : "";
        return prefix + UUID.randomUUID() + "-" + sanitizedFilename;
    }

    @Override
    public String uploadFile(MultipartFile file, String folder, String bucket) throws IOException {
        String key = planObjectKey(folder, file.getOriginalFilename());
        return uploadFileAtKey(file, bucket, key);
    }

    @Override
    public String uploadFileAtKey(MultipartFile file, String bucket, String key) throws IOException {
        String targetBucket = resolveBucket(bucket);
        String cleanedKey = cleanKey(key);
        if (cleanedKey.isBlank()) {
            throw new IllegalArgumentException("Object key must not be blank");
        }

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(targetBucket)
                .key(cleanedKey)
                .contentType(file.getContentType())
                .build();

        s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        log.info("Uploaded file to S3: bucket={}, key={}", targetBucket, cleanedKey);

        if ("avatars".equalsIgnoreCase(targetBucket) && publicUrlPrefix != null && !publicUrlPrefix.isBlank()) {
            String url = publicUrlPrefix.endsWith("/") ? publicUrlPrefix : publicUrlPrefix + "/";
            return url + cleanedKey;
        }

        return cleanedKey;
    }

    @Override
    public String uploadFile(MultipartFile file, String folder) throws IOException {
        return uploadFile(file, folder, defaultBucketName);
    }

    @Override
    public InputStream downloadFile(String bucket, String key) throws IOException {
        return readObject(new StorageReadRequest(bucket, key, Optional.empty())).body();
    }

    @Override
    public StorageReadResult readObject(StorageReadRequest request) throws IOException {
        String targetBucket = resolveBucket(request.bucket());
        String cleanedKey = cleanKey(request.key());

        try {
            GetObjectRequest.Builder builder = GetObjectRequest.builder()
                    .bucket(targetBucket)
                    .key(cleanedKey);
            if (request.range().isPresent()) {
                builder.range(request.range().get().toHttpRangeHeader());
            }

            ResponseInputStream<GetObjectResponse> stream = s3Client.getObject(builder.build());
            try {
                GetObjectResponse response = stream.response();
                Optional<ByteRange> requestedRange = request.range();
                if (requestedRange.isPresent()) {
                    ProviderRangeValidator.validate(requestedRange.get(),
                            response != null && response.sdkHttpResponse() != null ? response.sdkHttpResponse().statusCode() : 0,
                            response != null ? response.contentRange() : null,
                            response != null ? response.contentLength() : null);
                }
                long responseBytes = resolveResponseBytes(response, requestedRange);
                long totalBytes = resolveTotalBytes(response, responseBytes);
                String contentType = response != null ? response.contentType() : null;
                Optional<String> etag = response != null && response.eTag() != null && !response.eTag().isBlank()
                        ? Optional.of(response.eTag())
                        : Optional.empty();
                return new StorageReadResult(stream, totalBytes, responseBytes, contentType, requestedRange, etag);
            } catch (IOException | RuntimeException ex) {
                stream.abort();
                closeQuietly(stream);
                throw ex;
            }
        } catch (Exception e) {
            log.error("Failed to download file from S3: bucket={}, key={}, error={}", targetBucket, cleanedKey, e.getMessage());
            throw new IOException("Failed to download file from S3: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<StorageObjectStat> statObject(String bucket, String key) throws IOException {
        String targetBucket = resolveBucket(bucket);
        String cleanedKey = cleanKey(key);
        if (cleanedKey.isBlank()) {
            return Optional.empty();
        }

        try {
            HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(targetBucket)
                    .key(cleanedKey)
                    .build());
            if (head == null) {
                return Optional.empty();
            }
            long totalBytes = head.contentLength() != null ? head.contentLength() : -1L;
            return Optional.of(new StorageObjectStat(
                    totalBytes,
                    head.contentType(),
                    Optional.ofNullable(head.eTag()).filter(s -> !s.isBlank()),
                    Optional.ofNullable(head.lastModified())
            ));
        } catch (NoSuchKeyException e) {
            throw new IOException("Object not found in S3: " + cleanedKey, e);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new IOException("Object not found in S3: " + cleanedKey, e);
            }
            throw new IOException("Failed to stat object in S3: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IOException("Failed to stat object in S3: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream downloadFile(String fileUrlOrKey) throws IOException {
        return downloadFile(defaultBucketName, fileUrlOrKey);
    }

    @Override
    public InputStream downloadFileRange(String bucket, String key, long start, long end) throws IOException {
        String targetBucket = (bucket != null && !bucket.isBlank()) ? bucket : defaultBucketName;
        String cleanedKey = cleanKey(key);

        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(targetBucket)
                    .key(cleanedKey)
                    .range("bytes=" + start + "-" + end)
                    .build();
            return s3Client.getObject(getObjectRequest);
        } catch (Exception e) {
            log.error("Failed to download byte range from S3: bucket={}, key={}, error={}", targetBucket, cleanedKey, e.getMessage());
            throw new IOException("Failed to download byte range from S3: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteFile(String bucket, String key) {
        StorageDeleteResult result = deleteObject(bucket, key);
        if (!result.isSuccess()) {
            log.error("Failed to delete file from S3: bucket={}, key={}, code={}, error={}",
                    resolveBucket(bucket), cleanKey(key), result.errorCode(), result.safeMessage());
        }
    }

    @Override
    public StorageDeleteResult deleteObject(String bucket, String key) {
        if (key == null || key.isBlank()) {
            return StorageDeleteResult.notFound();
        }
        String targetBucket = resolveBucket(bucket);
        String cleanedKey = cleanKey(key);
        if (cleanedKey.isBlank()) {
            return StorageDeleteResult.notFound();
        }

        try {
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(targetBucket)
                    .key(cleanedKey)
                    .build();
            s3Client.deleteObject(deleteObjectRequest);
            log.info("Deleted file from S3: bucket={}, key={}", targetBucket, cleanedKey);
            return StorageDeleteResult.deleted();
        } catch (NoSuchKeyException e) {
            log.info("S3 object already absent (404 treated as idempotent success): bucket={}, key={}", targetBucket, cleanedKey);
            return StorageDeleteResult.notFound();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                log.info("S3 object already absent (404 treated as idempotent success): bucket={}, key={}", targetBucket, cleanedKey);
                return StorageDeleteResult.notFound();
            }
            if (e.statusCode() == 401 || e.statusCode() == 403) {
                return StorageDeleteResult.authFailure("S3_HTTP_" + e.statusCode(), sanitizeError(e));
            }
            return StorageDeleteResult.retryableFailure("S3_HTTP_" + e.statusCode(), sanitizeError(e));
        } catch (Exception e) {
            return StorageDeleteResult.retryableFailure("S3_ERROR", sanitizeError(e));
        }
    }

    @Override
    public void deleteFile(String fileUrlOrKey) {
        deleteFile(defaultBucketName, fileUrlOrKey);
    }

    private String resolveBucket(String bucket) {
        return (bucket != null && !bucket.isBlank()) ? bucket : defaultBucketName;
    }

    private static long resolveResponseBytes(GetObjectResponse response, Optional<ByteRange> requestedRange) {
        if (response != null && response.contentLength() != null) {
            return response.contentLength();
        }
        return requestedRange.map(ByteRange::length).orElse(-1L);
    }

    private static long resolveTotalBytes(GetObjectResponse response, long responseBytes) {
        if (response != null && response.contentRange() != null) {
            int slash = response.contentRange().lastIndexOf('/');
            if (slash >= 0 && slash + 1 < response.contentRange().length()) {
                String totalPart = response.contentRange().substring(slash + 1).trim();
                if (!"*".equals(totalPart)) {
                    try {
                        return Long.parseLong(totalPart);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return response != null && response.contentLength() != null ? response.contentLength() : responseBytes;
    }

    private static void closeQuietly(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String sanitizeError(Throwable error) {
        String msg = error.getMessage();
        if (msg == null || msg.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return msg.length() > 300 ? msg.substring(0, 300) : msg;
    }

    private String cleanKey(String fileUrlOrKey) {
        if (fileUrlOrKey == null) {
            return "";
        }
        String trimmed = fileUrlOrKey.trim();
        if (publicUrlPrefix != null && !publicUrlPrefix.isBlank() && trimmed.startsWith(publicUrlPrefix)) {
            String sub = trimmed.substring(publicUrlPrefix.length());
            if (sub.startsWith("/")) {
                sub = sub.substring(1);
            }
            return sub;
        }
        if (trimmed.startsWith("/documents/")) {
            trimmed = trimmed.substring("/documents/".length());
        } else if (trimmed.startsWith("/avatars/")) {
            trimmed = trimmed.substring("/avatars/".length());
        } else if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }
}
