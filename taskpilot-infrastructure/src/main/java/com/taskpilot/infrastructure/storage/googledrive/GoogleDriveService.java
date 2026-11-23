package com.taskpilot.infrastructure.storage.googledrive;

import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.http.InputStreamContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.Permission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;

@Slf4j
@Service
public class GoogleDriveService {

    public record StreamedUploadResult(
            GoogleDriveFileDto driveFile,
            String computedMd5Hex,
            long streamedBytes
    ) {}

    private static final int MIN_RESUMABLE_CHUNK_SIZE = MediaHttpUploader.MINIMUM_CHUNK_SIZE; // 256 KB
    private static final int DEFAULT_RESUMABLE_CHUNK_SIZE = 4 * 1024 * 1024; // 4 MB

    private final Drive drive;
    private final String defaultFolderId;
    private final boolean credentialsConfigured;

    public GoogleDriveService(Drive drive, String folderId) {
        this.drive = drive;
        this.defaultFolderId = folderId != null ? folderId.trim() : "";
        this.credentialsConfigured = true;
    }

    @Autowired
    public GoogleDriveService(
            Drive drive,
            @Value("${google.drive.folder-id:}") String folderId,
            @Value("${google.drive.client-id:}") String clientId,
            @Value("${google.drive.client-secret:}") String clientSecret,
            @Value("${google.drive.refresh-token:}") String refreshToken) {
        this.drive = drive;
        this.defaultFolderId = folderId != null ? folderId.trim() : "";
        this.credentialsConfigured = clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank()
                && refreshToken != null && !refreshToken.isBlank();
    }

    public boolean isConfigured() {
        return credentialsConfigured && defaultFolderId != null && !defaultFolderId.isBlank();
    }

    /**
     * Upload a multipart file directly to Google Drive 5TB storage.
     * Sets public reader permission so the file can be viewed or downloaded directly.
     *
     * @param multipartFile file from request
     * @return GoogleDriveFileDto containing fileId, name, webViewLink, and webContentLink
     * @throws IOException on upload failure
     */
    public GoogleDriveFileDto uploadFile(MultipartFile multipartFile) throws IOException {
        return uploadFile(multipartFile, defaultFolderId);
    }

    /**
     * Upload a multipart file to a specific folder in Google Drive.
     *
     * @param multipartFile file from request
     * @param targetFolderId folder ID on Google Drive
     * @return GoogleDriveFileDto
     * @throws IOException on upload failure
     */
    public GoogleDriveFileDto uploadFile(MultipartFile multipartFile, String targetFolderId) throws IOException {
        return uploadFile(multipartFile, targetFolderId, true);
    }

    /**
     * Upload a multipart file to a specific folder in Google Drive, optionally keeping it private.
     */
    public GoogleDriveFileDto uploadFile(MultipartFile multipartFile, String targetFolderId, boolean makePublic) throws IOException {
        if (multipartFile == null || multipartFile.isEmpty()) {
            throw new IllegalArgumentException("Cannot upload empty file to Google Drive");
        }

        String originalFilename = multipartFile.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            originalFilename = "unnamed_file_" + System.currentTimeMillis();
        }

        File fileMetadata = new File();
        fileMetadata.setName(originalFilename);

        String parentFolder = (targetFolderId != null && !targetFolderId.isBlank()) ? targetFolderId : defaultFolderId;
        if (parentFolder != null && !parentFolder.isBlank()) {
            fileMetadata.setParents(Collections.singletonList(parentFolder));
        }

        String contentType = multipartFile.getContentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }

        InputStreamContent mediaContent = new InputStreamContent(contentType, multipartFile.getInputStream());
        mediaContent.setLength(multipartFile.getSize());

        log.info("Uploading file '{}' (size={} bytes, public={}) to Google Drive folder '{}'...",
                originalFilename, multipartFile.getSize(), makePublic, parentFolder);

        File uploadedFile = drive.files().create(fileMetadata, mediaContent)
                .setFields("id, name, mimeType, size, md5Checksum, webViewLink, webContentLink")
                .execute();

        log.info("File uploaded to Google Drive: id={}, name={}", uploadedFile.getId(), uploadedFile.getName());

        if (makePublic) {
            // Make file readable to anyone with link for direct streaming / download
            try {
                Permission permission = new Permission()
                        .setType("anyone")
                        .setRole("reader");
                drive.permissions().create(uploadedFile.getId(), permission).execute();
                log.info("Public read permission granted for Drive file id={}", uploadedFile.getId());
            } catch (Exception e) {
                log.warn("Failed to set public permission on Drive file {}: {}", uploadedFile.getId(), e.getMessage());
            }
        }

        return GoogleDriveFileDto.builder()
                .fileId(uploadedFile.getId())
                .name(uploadedFile.getName())
                .mimeType(uploadedFile.getMimeType())
                .size(uploadedFile.getSize())
                .md5Checksum(uploadedFile.getMd5Checksum())
                .webViewLink(uploadedFile.getWebViewLink())
                .webContentLink(uploadedFile.getWebContentLink())
                .build();
    }

    /**
     * Streams a large file directly into Google Drive as a PRIVATE file using chunked resumable
     * upload ({@link MediaHttpUploader#setDirectUploadEnabled(boolean) false}) while computing
     * MD5 and exact byte count on the fly via {@link DigestInputStream} — never buffering the
     * full file into JVM heap and never granting public ("anyone") permissions.
     */
    public StreamedUploadResult uploadPrivateStreamWithMd5(
            InputStream sourceStream,
            String fileName,
            String contentType,
            long contentLength,
            String targetFolderId,
            int chunkSizeBytes) throws IOException {
        if (sourceStream == null) {
            throw new IllegalArgumentException("Source stream cannot be null");
        }

        String safeName = (fileName != null && !fileName.isBlank())
                ? fileName.trim()
                : "recording_" + System.currentTimeMillis() + ".mp4";
        String safeContentType = (contentType != null && !contentType.isBlank())
                ? contentType.trim()
                : "application/octet-stream";

        File fileMetadata = new File();
        fileMetadata.setName(safeName);
        String parentFolder = (targetFolderId != null && !targetFolderId.isBlank()) ? targetFolderId.trim() : defaultFolderId;
        if (parentFolder != null && !parentFolder.isBlank()) {
            fileMetadata.setParents(Collections.singletonList(parentFolder));
        }

        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 MessageDigest unavailable", e);
        }

        CountingInputStream countingStream = new CountingInputStream(sourceStream);
        DigestInputStream digestStream = new DigestInputStream(countingStream, md5);

        InputStreamContent mediaContent = new InputStreamContent(safeContentType, digestStream);
        if (contentLength > 0) {
            mediaContent.setLength(contentLength);
        }

        Drive.Files.Create createRequest = drive.files().create(fileMetadata, mediaContent)
                .setFields("id, name, mimeType, size, md5Checksum");

        MediaHttpUploader uploader = createRequest.getMediaHttpUploader();
        if (uploader != null) {
            uploader.setDirectUploadEnabled(false);
            uploader.setChunkSize(normalizeChunkSize(chunkSizeBytes));
        }

        log.info("Streaming private archive '{}' (expectedBytes={}) to Google Drive folder '{}'...",
                safeName, contentLength, parentFolder);

        File uploadedFile = createRequest.execute();
        String computedMd5Hex = HexFormat.of().formatHex(md5.digest());
        long streamedBytes = countingStream.getBytesRead();

        GoogleDriveFileDto dto = GoogleDriveFileDto.builder()
                .fileId(uploadedFile.getId())
                .name(uploadedFile.getName())
                .mimeType(uploadedFile.getMimeType())
                .size(uploadedFile.getSize())
                .md5Checksum(uploadedFile.getMd5Checksum())
                .build();

        return new StreamedUploadResult(dto, computedMd5Hex, streamedBytes);
    }

    static int normalizeChunkSize(int requestedBytes) {
        int base = requestedBytes > 0 ? requestedBytes : DEFAULT_RESUMABLE_CHUNK_SIZE;
        int multiples = Math.max(1, base / MIN_RESUMABLE_CHUNK_SIZE);
        return multiples * MIN_RESUMABLE_CHUNK_SIZE;
    }

    private static final class CountingInputStream extends FilterInputStream {
        private long bytesRead = 0L;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                bytesRead++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                bytesRead += n;
            }
            return n;
        }

        long getBytesRead() {
            return bytesRead;
        }
    }

    /**
     * Download a file stream from Google Drive by fileId.
     */
    public InputStream downloadFile(String fileId) throws IOException {
        return drive.files().get(fileId).executeMediaAsInputStream();
    }

    /**
     * Stream an inclusive byte range [start, end] directly from Google Drive without full-file buffering.
     */
    public InputStream downloadFileRange(String fileId, long start, long end) throws IOException {
        Drive.Files.Get request = drive.files().get(fileId);
        request.getRequestHeaders().setRange("bytes=" + start + "-" + end);
        return request.executeMediaAsInputStream();
    }

    /**
     * Fetch file metadata (size, mimeType, md5Checksum) from Google Drive.
     */
    public File getFileMetadata(String fileId) throws IOException {
        return drive.files().get(fileId)
                .setFields("id, name, mimeType, size, md5Checksum")
                .execute();
    }

    /**
     * Delete a file from Google Drive by fileId.
     */
    public void deleteFile(String fileId) {
        try {
            drive.files().delete(fileId).execute();
            log.info("Deleted Google Drive file id={}", fileId);
        } catch (Exception e) {
            log.error("Failed to delete Google Drive file id={}: {}", fileId, e.getMessage());
        }
    }
}
