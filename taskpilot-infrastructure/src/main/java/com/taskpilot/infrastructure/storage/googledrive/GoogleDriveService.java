package com.taskpilot.infrastructure.storage.googledrive;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.InputStreamContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.taskpilot.infrastructure.storage.range.ByteRange;
import com.taskpilot.infrastructure.storage.range.ProviderRangeValidator;
import com.taskpilot.infrastructure.storage.range.StorageDeleteResult;
import com.taskpilot.infrastructure.storage.range.StorageObjectStat;
import com.taskpilot.infrastructure.storage.range.StorageReadResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.google.api.client.googleapis.media.MediaHttpUploader;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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



    private static final String SAFE_DRIVE_FIELDS = "id, name, mimeType, size, md5Checksum, modifiedTime";
    private static final String OPERATION_ID_APP_PROPERTY = "taskpilotOperationId";

    private final Drive drive;
    private final String defaultFolderId;
    private final boolean credentialsConfigured;

    public GoogleDriveService(
            Drive drive,
            @Value("${google.drive.folder-id:}") String folderId) {
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
        return credentialsConfigured && !defaultFolderId.isBlank();
    }

    /**
     * Upload a multipart file directly to private Google Drive storage using service credentials.
     * Does NOT grant anyone-reader permissions or fetch/expose provider sharing links.
     */
    public GoogleDriveFileDto uploadFile(MultipartFile multipartFile) throws IOException {
        return uploadFile(multipartFile, defaultFolderId, null);
    }

    /**
     * Upload a multipart file to a specific folder in private Google Drive storage.
     */
    public GoogleDriveFileDto uploadFile(MultipartFile multipartFile, String targetFolderId) throws IOException {
        return uploadFile(multipartFile, targetFolderId, null);
    }

    /**
     * Upload a multipart file with an optional durable correlation operationId stored in appProperties
     * so crash-window reconciliation can locate and clean up unattached uploads.
     */
    public GoogleDriveFileDto uploadFile(MultipartFile multipartFile, String targetFolderId, UUID operationId) throws IOException {
        if (multipartFile == null || multipartFile.isEmpty()) {
            throw new IllegalArgumentException("Cannot upload empty file to Google Drive");
        }

        String originalFilename = multipartFile.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            originalFilename = "unnamed_file_" + System.currentTimeMillis();
        }

        File fileMetadata = new File();
        fileMetadata.setName(originalFilename);
        if (operationId != null) {
            fileMetadata.setAppProperties(Map.of(OPERATION_ID_APP_PROPERTY, operationId.toString()));
        }

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

        log.info("Uploading private file '{}' (size={} bytes, operationId={}) to Google Drive folder '{}'...",
                originalFilename, multipartFile.getSize(), operationId, parentFolder);

        File uploadedFile = drive.files().create(fileMetadata, mediaContent)
                .setFields(SAFE_DRIVE_FIELDS)
                .execute();

        log.info("File uploaded privately to Google Drive: id={}, name={}", uploadedFile.getId(), uploadedFile.getName());

        return toDto(uploadedFile);
    }

    /**
     * Bounded correlation lookup by appProperties.taskpilotOperationId for crash recovery.
     */
    public List<GoogleDriveFileDto> findFilesByOperationId(UUID operationId) throws IOException {
        if (operationId == null) {
            return List.of();
        }
        String query = "appProperties has { key='" + OPERATION_ID_APP_PROPERTY + "' and value='" + operationId + "' } and trashed = false";
        FileList list = drive.files().list()
                .setQ(query)
                .setPageSize(10)
                .setFields("files(" + SAFE_DRIVE_FIELDS + ")")
                .execute();
        if (list == null || list.getFiles() == null || list.getFiles().isEmpty()) {
            return List.of();
        }
        return list.getFiles().stream()
                .filter(f -> f != null && f.getId() != null && !f.getId().isBlank())
                .map(this::toDto)
                .toList();
    }

    /**
     * Download a full file stream from Google Drive by fileId.
     */
    public InputStream downloadFile(String fileId) throws IOException {
        return drive.files().get(fileId).executeMediaAsInputStream();
    }

    /**
     * Stream an object (or exact single byte range via HTTP Range header) from Google Drive,
     * disconnecting the underlying HTTP response when the returned stream is closed.
     */
    public StorageReadResult readObject(String fileId, Optional<ByteRange> range) throws IOException {
        Optional<ByteRange> targetRange = range != null ? range : Optional.empty();
        Drive.Files.Get getRequest = drive.files().get(fileId);
        if (targetRange.isPresent()) {
            getRequest.getRequestHeaders().setRange(targetRange.get().toHttpRangeHeader());
        }

        HttpResponse httpResponse = getRequest.executeMedia();
        if (httpResponse == null) {
            if (targetRange.isPresent()) {
                throw new IOException("Storage provider returned no verifiable range response");
            }
            InputStream fallbackStream = getRequest.executeMediaAsInputStream();
            long responseBytes = targetRange.map(ByteRange::length).orElse(-1L);
            return new StorageReadResult(fallbackStream, responseBytes, responseBytes, null, targetRange, Optional.empty());
        }

        try {
            if (targetRange.isPresent()) {
                var headers = httpResponse.getHeaders();
                ProviderRangeValidator.validate(targetRange.get(), httpResponse.getStatusCode(),
                        headers != null ? headers.getContentRange() : null,
                        headers != null ? headers.getContentLength() : null);
            }
            InputStream rawContent = httpResponse.getContent();
            InputStream managedStream = new DisconnectingInputStream(rawContent, httpResponse);
            long contentLength = httpResponse.getHeaders() != null && httpResponse.getHeaders().getContentLength() != null
                    ? httpResponse.getHeaders().getContentLength()
                    : targetRange.map(ByteRange::length).orElse(-1L);
            long totalBytes = resolveTotalBytes(
                    httpResponse.getHeaders() != null ? httpResponse.getHeaders().getContentRange() : null,
                    contentLength
            );
            String etag = httpResponse.getHeaders() != null ? httpResponse.getHeaders().getETag() : null;
            return new StorageReadResult(
                    managedStream,
                    totalBytes,
                    contentLength,
                    httpResponse.getContentType(),
                    targetRange,
                    Optional.ofNullable(etag).filter(s -> !s.isBlank())
            );
        } catch (IOException | RuntimeException ex) {
            disconnectQuietly(httpResponse);
            throw ex;
        }
    }

    public Optional<StorageObjectStat> statObject(String fileId) throws IOException {
        if (fileId == null || fileId.isBlank()) {
            return Optional.empty();
        }
        File metadata = drive.files().get(fileId)
                .setFields(SAFE_DRIVE_FIELDS)
                .execute();
        if (metadata == null) {
            return Optional.empty();
        }
        long size = metadata.getSize() != null ? metadata.getSize() : -1L;
        Optional<String> etag = Optional.ofNullable(metadata.getMd5Checksum())
                .filter(s -> !s.isBlank())
                .map(md5 -> "\"" + md5 + "\"");
        Optional<Instant> modified = Optional.ofNullable(metadata.getModifiedTime())
                .map(dt -> Instant.ofEpochMilli(dt.getValue()));
        return Optional.of(new StorageObjectStat(size, metadata.getMimeType(), etag, modified));
    }

    /**
     * Delete a file from Google Drive by fileId.
     */
    public void deleteFile(String fileId) {
        StorageDeleteResult result = deleteObject(fileId);
        if (!result.isSuccess()) {
            log.error("Failed to delete Google Drive file id={}: code={}, error={}",
                    fileId, result.errorCode(), result.safeMessage());
        }
    }

    /**
     * Delete a file from Google Drive and propagate structured outcome (404 treated as idempotent success).
     */
    public StorageDeleteResult deleteObject(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return StorageDeleteResult.notFound();
        }
        try {
            drive.files().delete(fileId).execute();
            log.info("Deleted Google Drive file id={}", fileId);
            return StorageDeleteResult.deleted();
        } catch (GoogleJsonResponseException e) {
            int code = e.getStatusCode();
            if (code == 404) {
                log.info("Google Drive file already absent (404 treated as idempotent success): id={}", fileId);
                return StorageDeleteResult.notFound();
            }
            if (code == 401 || code == 403) {
                return StorageDeleteResult.authFailure("DRIVE_HTTP_" + code, sanitizeError(e));
            }
            return StorageDeleteResult.retryableFailure("DRIVE_HTTP_" + code, sanitizeError(e));
        } catch (HttpResponseException e) {
            int code = e.getStatusCode();
            if (code == 404) {
                return StorageDeleteResult.notFound();
            }
            if (code == 401 || code == 403) {
                return StorageDeleteResult.authFailure("DRIVE_HTTP_" + code, sanitizeError(e));
            }
            return StorageDeleteResult.retryableFailure("DRIVE_HTTP_" + code, sanitizeError(e));
        } catch (Exception e) {
            return StorageDeleteResult.retryableFailure("DRIVE_ERROR", sanitizeError(e));
        }
    }

    private GoogleDriveFileDto toDto(File file) {
        Instant modified = file.getModifiedTime() != null
                ? Instant.ofEpochMilli(file.getModifiedTime().getValue())
                : null;
        return GoogleDriveFileDto.builder()
                .fileId(file.getId())
                .name(file.getName())
                .mimeType(file.getMimeType())
                .size(file.getSize())
                .md5Checksum(file.getMd5Checksum())
                .modifiedTime(modified)
                .build();
    }

    private static long resolveTotalBytes(String contentRange, long fallbackContentLength) {
        if (contentRange != null) {
            int slash = contentRange.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < contentRange.length()) {
                String totalPart = contentRange.substring(slash + 1).trim();
                if (!"*".equals(totalPart)) {
                    try {
                        return Long.parseLong(totalPart);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return fallbackContentLength;
    }

    private static void disconnectQuietly(HttpResponse response) {
        if (response != null) {
            try {
                response.disconnect();
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

    private static final class DisconnectingInputStream extends FilterInputStream {
        private final HttpResponse httpResponse;
        private boolean closed;

        DisconnectingInputStream(InputStream in, HttpResponse httpResponse) {
            super(in != null ? in : InputStream.nullInputStream());
            this.httpResponse = httpResponse;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                super.close();
            } finally {
                disconnectQuietly(httpResponse);
            }
        }
    }
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
}
