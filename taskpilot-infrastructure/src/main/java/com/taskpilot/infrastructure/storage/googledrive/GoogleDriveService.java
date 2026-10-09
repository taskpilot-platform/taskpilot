package com.taskpilot.infrastructure.storage.googledrive;

import com.google.api.client.http.InputStreamContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.Permission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

@Slf4j
@Service
public class GoogleDriveService {

    private final Drive drive;
    private final String defaultFolderId;

    public GoogleDriveService(
            Drive drive,
            @Value("${google.drive.folder-id:}") String folderId) {
        this.drive = drive;
        this.defaultFolderId = folderId != null ? folderId.trim() : "";
    }

    public boolean isConfigured() {
        return defaultFolderId != null && !defaultFolderId.isBlank();
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

        log.info("Uploading file '{}' (size={} bytes) to Google Drive folder '{}'...", originalFilename, multipartFile.getSize(), parentFolder);

        File uploadedFile = drive.files().create(fileMetadata, mediaContent)
                .setFields("id, name, mimeType, size, webViewLink, webContentLink")
                .execute();

        log.info("File uploaded to Google Drive: id={}, name={}", uploadedFile.getId(), uploadedFile.getName());

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

        return GoogleDriveFileDto.builder()
                .fileId(uploadedFile.getId())
                .name(uploadedFile.getName())
                .mimeType(uploadedFile.getMimeType())
                .size(uploadedFile.getSize())
                .webViewLink(uploadedFile.getWebViewLink())
                .webContentLink(uploadedFile.getWebContentLink())
                .build();
    }

    /**
     * Download a file stream from Google Drive by fileId.
     */
    public InputStream downloadFile(String fileId) throws IOException {
        return drive.files().get(fileId).executeMediaAsInputStream();
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
