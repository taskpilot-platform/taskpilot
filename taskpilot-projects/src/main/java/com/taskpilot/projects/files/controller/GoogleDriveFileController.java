package com.taskpilot.projects.files.controller;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveFileDto;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

@Slf4j
@Tag(name = "16. Google Drive 5TB Storage", description = "Direct APIs for Google Drive 5TB storage and file uploads")
@RestController
@RequiredArgsConstructor
public class GoogleDriveFileController {

    private final GoogleDriveService googleDriveService;

    /**
     * Standalone test upload endpoint matching the user's Postman test specification:
     * POST /api/files/upload
     */
    @PostMapping("/api/files/upload")
    public ResponseEntity<String> upload(@RequestParam("file") MultipartFile file) {
        try {
            GoogleDriveFileDto dto = googleDriveService.uploadFile(file);
            log.info("Direct Google Drive upload successful: fileId={}, link={}", dto.getFileId(), dto.getWebViewLink());
            return ResponseEntity.ok("Upload thành công! Link: " + dto.getWebViewLink());
        } catch (Exception e) {
            log.error("Google Drive upload failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Lỗi upload: " + e.getMessage());
        }
    }

    /**
     * Standard TaskPilot API endpoint returning structured response:
     * POST /api/v1/files/google-drive/upload
     */
    @Operation(summary = "Upload file directly to Google Drive 5TB", description = "Uploads any file directly to the configured Google Drive 5TB folder and sets public read permissions")
    @PostMapping(value = "/api/v1/files/google-drive/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<GoogleDriveFileDto> uploadStructured(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folderId", required = false) String folderId) {
        try {
            GoogleDriveFileDto dto = (folderId != null && !folderId.isBlank())
                    ? googleDriveService.uploadFile(file, folderId.trim())
                    : googleDriveService.uploadFile(file);
            return ApiResponse.ok("File uploaded to Google Drive successfully", dto);
        } catch (Exception e) {
            log.error("Failed to upload file to Google Drive: {}", e.getMessage(), e);
            throw new RuntimeException("Google Drive upload failed: " + e.getMessage(), e);
        }
    }

    /**
     * Stream download file from Google Drive:
     * GET /api/v1/files/google-drive/{fileId}/download
     */
    @Operation(summary = "Download file from Google Drive", description = "Stream download file content by Google Drive file ID")
    @GetMapping("/api/v1/files/google-drive/{fileId}/download")
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileId) {
        try {
            InputStream is = googleDriveService.downloadFile(fileId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"gdrive_" + fileId + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new InputStreamResource(is));
        } catch (Exception e) {
            log.error("Failed to download file from Google Drive: {}", e.getMessage(), e);
            return ResponseEntity.notFound().build();
        }
    }
}
