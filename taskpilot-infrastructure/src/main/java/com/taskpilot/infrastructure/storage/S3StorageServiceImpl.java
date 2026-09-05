package com.taskpilot.infrastructure.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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
    public String uploadFile(MultipartFile file, String folder, String bucket) throws IOException {
        String targetBucket = (bucket != null && !bucket.isBlank()) ? bucket : defaultBucketName;

        String originalFilename = file.getOriginalFilename();
        String sanitizedFilename = (originalFilename != null && !originalFilename.isBlank())
                ? originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_")
                : "file";

        String key = (folder != null && !folder.isEmpty() ? folder + "/" : "")
                + UUID.randomUUID() + "-" + sanitizedFilename;

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(targetBucket)
                .key(key)
                .contentType(file.getContentType())
                .build();

        s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        log.info("Uploaded file to S3: bucket={}, key={}", targetBucket, key);

        if ("avatars".equalsIgnoreCase(targetBucket) && publicUrlPrefix != null && !publicUrlPrefix.isBlank()) {
            String url = publicUrlPrefix.endsWith("/") ? publicUrlPrefix : publicUrlPrefix + "/";
            return url + key;
        }

        return key;
    }

    @Override
    public String uploadFile(MultipartFile file, String folder) throws IOException {
        return uploadFile(file, folder, defaultBucketName);
    }

    @Override
    public InputStream downloadFile(String bucket, String key) throws IOException {
        String targetBucket = (bucket != null && !bucket.isBlank()) ? bucket : defaultBucketName;
        String cleanedKey = cleanKey(key);

        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(targetBucket)
                    .key(cleanedKey)
                    .build();
            return s3Client.getObject(getObjectRequest);
        } catch (Exception e) {
            log.error("Failed to download file from S3: bucket={}, key={}, error={}", targetBucket, cleanedKey, e.getMessage());
            throw new IOException("Failed to download file from S3: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream downloadFile(String fileUrlOrKey) throws IOException {
        return downloadFile(defaultBucketName, fileUrlOrKey);
    }

    @Override
    public void deleteFile(String bucket, String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        String targetBucket = (bucket != null && !bucket.isBlank()) ? bucket : defaultBucketName;
        String cleanedKey = cleanKey(key);

        try {
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(targetBucket)
                    .key(cleanedKey)
                    .build();
            s3Client.deleteObject(deleteObjectRequest);
            log.info("Deleted file from S3: bucket={}, key={}", targetBucket, cleanedKey);
        } catch (Exception e) {
            log.error("Failed to delete file from S3: bucket={}, key={}, error={}", targetBucket, cleanedKey, e.getMessage());
        }
    }

    @Override
    public void deleteFile(String fileUrlOrKey) {
        deleteFile(defaultBucketName, fileUrlOrKey);
    }

    private String cleanKey(String fileUrlOrKey) {
        if (fileUrlOrKey == null) {
            return "";
        }
        String trimmed = fileUrlOrKey.trim();
        if (publicUrlPrefix != null && trimmed.startsWith(publicUrlPrefix)) {
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
