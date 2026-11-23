package com.taskpilot.projects.files.controller;

import com.taskpilot.infrastructure.config.security.FileStreamTokenService;
import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.projects.files.dto.FileDownloadResource;
import com.taskpilot.projects.files.dto.FileStreamResource;
import com.taskpilot.projects.files.dto.ProjectFileResponse;
import com.taskpilot.projects.files.service.ProjectFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Tag(name = "15. Project Files", description = "APIs for project file storage and document management")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/files")
@RequiredArgsConstructor
public class ProjectFileController {

    private final ProjectFileService projectFileService;
    private final FileStreamTokenService fileStreamTokenService;

    @Operation(summary = "Upload a file to project", description = "Upload a file into project storage (up to 50MB)")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProjectFileResponse> uploadFile(
            @PathVariable Long projectId,
            Authentication authentication,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "description", required = false) String description) {
        return ApiResponse.created("File uploaded successfully",
                projectFileService.uploadFile(projectId, authentication.getName(), file, description));
    }

    @Operation(summary = "List project files", description = "Get paginated list of project files with optional search keyword")
    @GetMapping
    public ApiResponse<Page<ProjectFileResponse>> listFiles(
            @PathVariable Long projectId,
            Authentication authentication,
            @RequestParam(required = false) String keyword,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok("Files retrieved successfully",
                projectFileService.listFiles(projectId, authentication.getName(), keyword, pageable));
    }

    @Operation(summary = "Download project file", description = "Stream download a project file")
    @GetMapping("/{fileId}/download")
    public ResponseEntity<Resource> downloadFile(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            Authentication authentication) {
        FileDownloadResource resource = projectFileService.downloadFile(projectId, fileId, authentication.getName());

        String encodedFilename = URLEncoder.encode(resource.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (resource.contentType() != null && !resource.contentType().isBlank()) {
            try {
                mediaType = MediaType.parseMediaType(resource.contentType());
            } catch (Exception ignored) {
            }
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(resource.fileSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFilename)
                .body(new InputStreamResource(resource.inputStream()));
    }

    @Operation(summary = "Stream project file with HTTP Range support",
            description = "Streams private media directly without heap buffering, supporting HTTP 206 Partial Content seeking and project RBAC via JWT or signed stream token")
    @GetMapping("/{fileId}/stream")
    public ResponseEntity<StreamingResponseBody> streamFile(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            @RequestParam(value = "token", required = false) String streamToken,
            Authentication authentication) {

        String userEmail = resolveAuthenticatedEmail(authentication, streamToken, projectId, fileId);
        if (userEmail == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        FileStreamResource streamResource = projectFileService.streamFile(projectId, fileId, userEmail, rangeHeader);

        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (streamResource.contentType() != null && !streamResource.contentType().isBlank()) {
            try {
                mediaType = MediaType.parseMediaType(streamResource.contentType());
            } catch (Exception ignored) {
            }
        }

        String encodedFilename = URLEncoder.encode(streamResource.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        StreamingResponseBody body = outputStream -> {
            try (InputStream inputStream = streamResource.streamOpener().open()) {
                inputStream.transferTo(outputStream);
            }
        };

        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(streamResource.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                .contentType(mediaType)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encodedFilename);

        if (streamResource.totalSize() > 0) {
            builder.contentLength(streamResource.contentLength());
        }
        if (streamResource.partial()) {
            builder.header(HttpHeaders.CONTENT_RANGE,
                    "bytes " + streamResource.rangeStart() + "-" + streamResource.rangeEnd() + "/" + streamResource.totalSize());
        }

        return builder.body(body);
    }

    private String resolveAuthenticatedEmail(Authentication authentication, String streamToken, Long projectId, Long fileId) {
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getName() != null
                && !"anonymousUser".equals(authentication.getName())) {
            return authentication.getName();
        }
        Optional<FileStreamTokenService.StreamTokenClaims> claims =
                fileStreamTokenService.verifyToken(streamToken, projectId, fileId);
        return claims.map(FileStreamTokenService.StreamTokenClaims::userEmail).orElse(null);
    }

    @Operation(summary = "Delete project file", description = "Delete a project file (Uploader or Project Manager only)")
    @DeleteMapping("/{fileId}")
    public ApiResponse<Void> deleteFile(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            Authentication authentication) {
        projectFileService.deleteFile(projectId, fileId, authentication.getName());
        return ApiResponse.ok("File deleted successfully", null);
    }
}
