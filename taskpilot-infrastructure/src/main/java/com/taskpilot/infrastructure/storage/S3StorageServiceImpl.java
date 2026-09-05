package com.taskpilot.infrastructure.storage;

import lombok.extern.slf4j.Slf4j;
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
    private final String bucketName;
    private final String publicUrlPrefix;

    public S3StorageServiceImpl(
            @Value("${supabase.s3.endpoint}") String endpoint,
            @Value("${supabase.s3.access-key}") String accessKey,
            @Value("${supabase.s3.secret-key}") String secretKey,
            @Value("${supabase.s3.region}") String region,
            @Value("${supabase.s3.bucket}") String bucketName,
            @Value("${supabase.s3.public-url}") String publicUrlPrefix) {
        
        this.bucketName = bucketName;
        this.publicUrlPrefix = publicUrlPrefix;

        // Ensure endpoint ends without trailing slash if required, but URI.create handles it.
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);
        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .forcePathStyle(true) // Supabase requires path-style access for S3
                .build();
    }

    @Override
    public String uploadFile(MultipartFile file, String folder) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        
        String key = (folder != null && !folder.isEmpty() ? folder + "/" : "") 
                + UUID.randomUUID().toString() + extension;

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(file.getContentType())
                .build();

        s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

        String url = publicUrlPrefix;
        if (!url.endsWith("/")) {
            url += "/";
        }
        return url + key;
    }

    @Override
    public InputStream downloadFile(String fileUrlOrKey) throws IOException {
        String key = extractKey(fileUrlOrKey);
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            return s3Client.getObject(getObjectRequest);
        } catch (Exception e) {
            log.error("Failed to download file from S3: bucket={}, key={}, error={}", bucketName, key, e.getMessage());
            throw new IOException("Failed to download file from S3: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteFile(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            return;
        }
        String key = extractKey(fileUrl);
        try {
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            s3Client.deleteObject(deleteObjectRequest);
            log.info("Deleted file from S3: bucket={}, key={}", bucketName, key);
        } catch (Exception e) {
            log.error("Failed to delete file from S3: bucket={}, key={}, error={}", bucketName, key, e.getMessage());
        }
    }

    private String extractKey(String fileUrlOrKey) {
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
        if (trimmed.startsWith("/")) {
            return trimmed.substring(1);
        }
        return trimmed;
    }
}
