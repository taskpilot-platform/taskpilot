package com.taskpilot.projects.files.controller;

import com.taskpilot.infrastructure.config.security.FileStreamTokenService;
import com.taskpilot.infrastructure.config.security.CurrentActorResolver;
import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.projects.files.dto.ProjectFileDownloadResult;
import com.taskpilot.projects.files.dto.ProjectFileResponse;
import com.taskpilot.projects.files.service.ProjectFileService;
import com.taskpilot.projects.files.service.RecordingArchiveWorker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
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

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
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

    private RecordingArchiveWorker recordingArchiveWorker;

    @Autowired(required = false)
    public void setRecordingArchiveWorker(RecordingArchiveWorker recordingArchiveWorker) {
        this.recordingArchiveWorker = recordingArchiveWorker;
    }

    @Operation(summary = "Upload a file to project", description = "Upload a file into project storage (up to 50MB)")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProjectFileResponse> uploadFile(
            @PathVariable Long projectId,
            Authentication authentication,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "description", required = false) String description) {
        return ApiResponse.created("File uploaded successfully",
                projectFileService.uploadFile(projectId, CurrentActorResolver.resolve(authentication), file, description));
    }

    @Operation(summary = "List project files", description = "Get paginated list of project files with optional search keyword")
    @GetMapping
    public ApiResponse<Page<ProjectFileResponse>> listFiles(
            @PathVariable Long projectId,
            Authentication authentication,
            @RequestParam(required = false) String keyword,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok("Files retrieved successfully",
                projectFileService.listFiles(projectId, CurrentActorResolver.userId(authentication), keyword, pageable));
    }

    @Operation(summary = "Download project file", description = "Stream download a project file with single-range HTTP 206/416 support")
    @GetMapping("/{fileId}/download")
    public ResponseEntity<Resource> downloadFile(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            @RequestHeader(value = HttpHeaders.IF_RANGE, required = false) String ifRangeHeader,
            Authentication authentication) {
        ProjectFileDownloadResult result = projectFileService.downloadFileWithRange(
                projectId,
                fileId,
                CurrentActorResolver.userId(authentication),
                rangeHeader,
                ifRangeHeader
        );

        try {
            if (result.status() == ProjectFileDownloadResult.Status.RANGE_NOT_SATISFIABLE_416) {
                closeQuietly(result.inputStream());
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + result.totalBytes())
                        .build();
            }

            String encodedFilename = URLEncoder.encode(result.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
            MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
            if (result.contentType() != null && !result.contentType().isBlank()) {
                try {
                    mediaType = MediaType.parseMediaType(result.contentType());
                } catch (Exception ignored) {
                }
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFilename);
            result.etag().ifPresent(headers::setETag);
            result.lastModified().ifPresent(lastModified -> headers.setLastModified(lastModified.toEpochMilli()));

            if (result.status() == ProjectFileDownloadResult.Status.PARTIAL_206 && result.servedRange().isPresent()) {
                headers.set(HttpHeaders.CONTENT_RANGE, result.servedRange().get().toContentRangeHeader(result.totalBytes()));
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                        .headers(headers)
                        .contentType(mediaType)
                        .contentLength(result.responseBytes())
                        .body(new InputStreamResource(result.inputStream()));
            }

            ResponseEntity.BodyBuilder okBuilder = ResponseEntity.ok()
                    .headers(headers)
                    .contentType(mediaType);
            if (result.totalBytes() >= 0) {
                okBuilder.contentLength(result.totalBytes());
            }
            return okBuilder.body(new InputStreamResource(result.inputStream()));
        } catch (RuntimeException ex) {
            closeQuietly(result.inputStream());
            throw ex;
        }
    }

    @Operation(summary = "Stream project file with HTTP Range support",
            description = "Streams private media directly without heap buffering, supporting HTTP 206 Partial Content seeking and project RBAC via JWT or signed stream token")
    @RequestMapping(value = "/{fileId}/stream", method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<StreamingResponseBody> streamFile(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            @RequestHeader(value = HttpHeaders.IF_RANGE, required = false) String ifRangeHeader,
            @RequestParam(value = "token", required = false) String streamToken,
            @RequestParam(value = "download", required = false, defaultValue = "false") boolean download,
            Authentication authentication,
            HttpServletRequest request) {
        Long userId = resolveAuthenticatedUserId(authentication, streamToken, projectId, fileId);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean headOnly = request != null && "HEAD".equalsIgnoreCase(request.getMethod());
        ProjectFileDownloadResult result = projectFileService.streamFileWithRange(
                projectId, fileId, userId, rangeHeader, ifRangeHeader, headOnly);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
            result.etag().ifPresent(headers::setETag);
            result.lastModified().ifPresent(value -> headers.setLastModified(value.toEpochMilli()));
            if (result.status() == ProjectFileDownloadResult.Status.RANGE_NOT_SATISFIABLE_416) {
                closeQuietly(result.inputStream());
                headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + result.totalBytes());
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).headers(headers).build();
            }
            MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
            if (result.contentType() != null && !result.contentType().isBlank()) {
                try { mediaType = MediaType.parseMediaType(result.contentType()); } catch (IllegalArgumentException ignored) { }
            }
            String encodedFilename = URLEncoder.encode(result.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    (download ? "attachment" : "inline") + "; filename*=UTF-8''" + encodedFilename);
            if (result.responseBytes() >= 0) {
                headers.setContentLength(result.responseBytes());
            }
            boolean partial = result.status() == ProjectFileDownloadResult.Status.PARTIAL_206;
            if (partial) {
                headers.set(HttpHeaders.CONTENT_RANGE, result.servedRange().orElseThrow().toContentRangeHeader(result.totalBytes()));
            }
            ResponseEntity.BodyBuilder builder = ResponseEntity.status(partial ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                    .headers(headers).contentType(mediaType);
            if (headOnly) {
                closeQuietly(result.inputStream());
                return builder.build();
            }
            StreamingResponseBody body = outputStream -> {
                try (InputStream inputStream = result.inputStream()) {
                    inputStream.transferTo(outputStream);
                }
            };
            return builder.body(body);
        } catch (RuntimeException ex) {
            closeQuietly(result.inputStream());
            throw ex;
        }
    }

    ResponseEntity<StreamingResponseBody> streamFile(Long projectId, Long fileId, String rangeHeader,
                                                    String streamToken, boolean download, Authentication authentication) {
        return streamFile(projectId, fileId, rangeHeader, null, streamToken, download, authentication, null);
    }

    ResponseEntity<StreamingResponseBody> streamFile(Long projectId, Long fileId, String rangeHeader,
                                                    String streamToken, Authentication authentication) {
        return streamFile(projectId, fileId, rangeHeader, streamToken, false, authentication);
    }

    private Long resolveAuthenticatedUserId(Authentication authentication, String streamToken, Long projectId, Long fileId) {
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getName() != null
                && !"anonymousUser".equals(authentication.getName())) {
            return CurrentActorResolver.userId(authentication);
        }
        Optional<FileStreamTokenService.StreamTokenClaims> claims =
                fileStreamTokenService.verifyToken(streamToken, projectId, fileId);
        return claims.map(FileStreamTokenService.StreamTokenClaims::userId).orElse(null);
    }

    @Operation(summary = "Delete project file", description = "Delete a project file (Uploader or Project Manager only)")
    @DeleteMapping("/{fileId}")
    public ApiResponse<Void> deleteFile(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            Authentication authentication) {
        projectFileService.deleteFile(projectId, fileId, CurrentActorResolver.userId(authentication));
        return ApiResponse.ok("File deleted successfully", null);
    }

    @Operation(summary = "Archive B2 recording to private Google Drive 5TB immediately",
            description = "Streams hot B2 file directly to private Google Drive 5TB with MD5+size verification before deleting from B2")
    @PostMapping("/{fileId}/archive-now")
    public ApiResponse<Boolean> archiveFileNow(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            Authentication authentication) {
        if (recordingArchiveWorker == null) {
            throw new com.taskpilot.infrastructure.exception.BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE.value(), "Archive worker is not available");
        }
        boolean archived = recordingArchiveWorker.archiveFileNow(projectId, fileId, CurrentActorResolver.userId(authentication));
        if (!archived) {
            throw new com.taskpilot.infrastructure.exception.BusinessException(
                    HttpStatus.BAD_GATEWAY.value(),
                    "Archive transfer did not complete; check archive_error or retry shortly");
        }
        return ApiResponse.ok("File archived to private Google Drive", true);
    }

    private static void closeQuietly(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }
}
