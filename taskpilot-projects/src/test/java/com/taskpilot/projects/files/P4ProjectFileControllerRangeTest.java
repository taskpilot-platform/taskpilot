package com.taskpilot.projects.files;

import com.taskpilot.contracts.storage.StorageCleanupPort;
import com.taskpilot.contracts.storage.StorageUploadOperationPort;
import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.infrastructure.config.security.TaskPilotPrincipal;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.exception.GlobalExceptionHandler;
import com.taskpilot.infrastructure.storage.StorageService;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveService;
import com.taskpilot.infrastructure.storage.range.ByteRange;
import com.taskpilot.infrastructure.storage.range.StorageObjectStat;
import com.taskpilot.infrastructure.storage.range.StorageReadRequest;
import com.taskpilot.infrastructure.storage.range.StorageReadResult;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.files.controller.ProjectFileController;
import com.taskpilot.projects.files.service.ProjectFileAttachmentService;
import com.taskpilot.projects.files.service.ProjectFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class P4ProjectFileControllerRangeTest {

    private static final byte[] PAYLOAD = "0123456789ABCDEF".getBytes(StandardCharsets.UTF_8); // 16 bytes
    private static final long PROJECT_ID = 10L;
    private static final long FILE_ID = 55L;
    private static final long USER_ID = 100L;

    @Mock
    private ProjectFileRepository projectFileRepository;
    @Mock
    private StorageService storageService;
    @Mock
    private GoogleDriveService googleDriveService;
    @Mock
    private ProjectSecurityService projectSecurityService;
    @Mock
    private ProjectMemberRepository projectMemberRepository;
    @Mock
    private UserProfilePort userProfilePort;
    @Mock
    private ProjectFileAttachmentService attachmentService;
    @Mock
    private StorageUploadOperationPort uploadOperationPort;
    @Mock
    private StorageCleanupPort storageCleanupPort;

    private ProjectFileService projectFileService;
    private MockMvc mockMvc;
    private TestingAuthenticationToken auth;
    private ProjectFileEntity storedEntity;

    @BeforeEach
    void setUp() {
        projectFileService = new ProjectFileService(
                projectFileRepository,
                storageService,
                googleDriveService,
                projectSecurityService,
                projectMemberRepository,
                userProfilePort,
                attachmentService,
                uploadOperationPort,
                storageCleanupPort
        );
        ProjectFileController controller = new ProjectFileController(projectFileService,
                mock(com.taskpilot.infrastructure.config.security.FileStreamTokenService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        auth = new TestingAuthenticationToken(
                new TaskPilotPrincipal(new CurrentActor(USER_ID, "user@test.com", "USER", 1L)),
                null,
                "ROLE_USER"
        );
        auth.setAuthenticated(true);

        Instant updated = Instant.parse("2026-11-16T10:15:30Z");
        storedEntity = ProjectFileEntity.builder()
                .id(FILE_ID)
                .projectId(PROJECT_ID)
                .uploaderId(USER_ID)
                .fileName("clip.webm")
                .originalName("clip.webm")
                .contentType("video/webm")
                .fileSize((long) PAYLOAD.length)
                .storageBucket("documents")
                .storageKey("projects/10/files/clip.webm")
                .createdAt(updated)
                .updatedAt(updated)
                .build();
    }

    @Test
    void download_full200_closesStreamAndSetsValidators() throws Exception {
        AtomicBoolean closed = new AtomicBoolean(false);
        when(projectFileRepository.findByIdAndProjectId(FILE_ID, PROJECT_ID)).thenReturn(Optional.of(storedEntity));
        when(storageService.readObject(any(StorageReadRequest.class))).thenReturn(
                new StorageReadResult(
                        trackingStream(PAYLOAD, closed),
                        PAYLOAD.length,
                        PAYLOAD.length,
                        "video/webm",
                        Optional.empty(),
                        Optional.of("\"s3-etag-1\"")
                )
        );

        MvcResult mvcResult = mockMvc.perform(get("/api/v1/projects/{p}/files/{f}/download", PROJECT_ID, FILE_ID)
                        .principal(auth))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "16"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"s3-etag-1\""))
                .andReturn();

        assertThat(mvcResult.getResponse().getContentAsByteArray()).isEqualTo(PAYLOAD);
        assertThat(closed).isTrue();
    }

    @Test
    void download_boundedSuffixAndOpenRanges_passExactRangeToProviderAndReturn206() throws Exception {
        when(projectFileRepository.findByIdAndProjectId(FILE_ID, PROJECT_ID)).thenReturn(Optional.of(storedEntity));

        // 1. Bounded range bytes=2-5
        AtomicBoolean closedBounded = new AtomicBoolean(false);
        byte[] boundedSlice = "2345".getBytes(StandardCharsets.UTF_8);
        when(storageService.readObject(new StorageReadRequest("documents", "projects/10/files/clip.webm", Optional.of(new ByteRange(2, 5)))))
                .thenReturn(new StorageReadResult(trackingStream(boundedSlice, closedBounded), 16, 4, "video/webm",
                        Optional.of(new ByteRange(2, 5)), Optional.of("\"e1\"")));

        MvcResult bounded = mockMvc.perform(get("/api/v1/projects/{p}/files/{f}/download", PROJECT_ID, FILE_ID)
                        .header(HttpHeaders.RANGE, "bytes=2-5")
                        .principal(auth))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 2-5/16"))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "4"))
                .andReturn();
        assertThat(bounded.getResponse().getContentAsByteArray()).isEqualTo(boundedSlice);
        assertThat(closedBounded).isTrue();

        // 2. Suffix range bytes=-4 => bytes 12-15
        AtomicBoolean closedSuffix = new AtomicBoolean(false);
        byte[] suffixSlice = "CDEF".getBytes(StandardCharsets.UTF_8);
        when(storageService.readObject(new StorageReadRequest("documents", "projects/10/files/clip.webm", Optional.of(new ByteRange(12, 15)))))
                .thenReturn(new StorageReadResult(trackingStream(suffixSlice, closedSuffix), 16, 4, "video/webm",
                        Optional.of(new ByteRange(12, 15)), Optional.of("\"e1\"")));

        MvcResult suffix = mockMvc.perform(get("/api/v1/projects/{p}/files/{f}/download", PROJECT_ID, FILE_ID)
                        .header(HttpHeaders.RANGE, "bytes=-4")
                        .principal(auth))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 12-15/16"))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "4"))
                .andReturn();
        assertThat(suffix.getResponse().getContentAsByteArray()).isEqualTo(suffixSlice);
        assertThat(closedSuffix).isTrue();

        // 3. Open-ended range bytes=10- => bytes 10-15
        AtomicBoolean closedOpen = new AtomicBoolean(false);
        byte[] openSlice = "ABCDEF".getBytes(StandardCharsets.UTF_8);
        when(storageService.readObject(new StorageReadRequest("documents", "projects/10/files/clip.webm", Optional.of(new ByteRange(10, 15)))))
                .thenReturn(new StorageReadResult(trackingStream(openSlice, closedOpen), 16, 6, "video/webm",
                        Optional.of(new ByteRange(10, 15)), Optional.of("\"e1\"")));

        MvcResult open = mockMvc.perform(get("/api/v1/projects/{p}/files/{f}/download", PROJECT_ID, FILE_ID)
                        .header(HttpHeaders.RANGE, "bytes=10-")
                        .principal(auth))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 10-15/16"))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "6"))
                .andReturn();
        assertThat(open.getResponse().getContentAsByteArray()).isEqualTo(openSlice);
        assertThat(closedOpen).isTrue();
    }

    @Test
    void download_driveFilePassesExactRangeToGoogleDriveService() throws Exception {
        storedEntity.setStorageBucket("googledrive");
        storedEntity.setStorageKey("drive-key-42");
        AtomicBoolean closed = new AtomicBoolean(false);
        byte[] slice = "0123".getBytes(StandardCharsets.UTF_8);

        when(projectFileRepository.findByIdAndProjectId(FILE_ID, PROJECT_ID)).thenReturn(Optional.of(storedEntity));
        when(googleDriveService.readObject("drive-key-42", Optional.of(new ByteRange(0, 3))))
                .thenReturn(new StorageReadResult(trackingStream(slice, closed), 16, 4, "video/webm",
                        Optional.of(new ByteRange(0, 3)), Optional.of("\"drive-etag\"")));

        MvcResult res = mockMvc.perform(get("/api/v1/projects/{p}/files/{f}/download", PROJECT_ID, FILE_ID)
                        .header(HttpHeaders.RANGE, "bytes=0-3")
                        .principal(auth))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-3/16"))
                .andReturn();

        assertThat(res.getResponse().getContentAsByteArray()).isEqualTo(slice);
        assertThat(closed).isTrue();
        verifyNoInteractions(storageService);
    }

    @Test
    void download_malformedAndUnsatisfiableRangesReturn416WithoutCallingProvider() throws Exception {
        when(projectFileRepository.findByIdAndProjectId(FILE_ID, PROJECT_ID)).thenReturn(Optional.of(storedEntity));

        for (String badRange : new String[]{"bytes=99-120", "bytes=10-5", "bytes=-0", "items=0-4"}) {
            mockMvc.perform(get("/api/v1/projects/{p}/files/{f}/download", PROJECT_ID, FILE_ID)
                            .header(HttpHeaders.RANGE, badRange)
                            .principal(auth))
                    .andExpect(status().isRequestedRangeNotSatisfiable())
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */16"));
        }

        verifyNoInteractions(storageService);
        verifyNoInteractions(googleDriveService);
    }

    @Test
    void download_staleIfRangeIgnoresUnsatisfiableRangeButMatchingValidatorReturns416() throws Exception {
        when(projectFileRepository.findByIdAndProjectId(FILE_ID, PROJECT_ID)).thenReturn(Optional.of(storedEntity));
        when(storageService.statObject("documents", "projects/10/files/clip.webm")).thenReturn(Optional.of(
                new StorageObjectStat(16L, "video/webm", Optional.of("\"current\""), Optional.of(storedEntity.getUpdatedAt()))));
        lenient().when(storageService.readObject(new StorageReadRequest("documents", "projects/10/files/clip.webm", Optional.empty())))
                .thenAnswer(invocation -> new StorageReadResult(new ByteArrayInputStream(PAYLOAD), 16, 16,
                        "video/webm", Optional.empty(), Optional.of("\"current\"")));

        for (String stale : new String[]{"\"old\"", "W/\"current\"", "Sun, 15 Nov 2026 00:00:00 GMT"}) {
            mockMvc.perform(get("/api/v1/projects/{p}/files/{f}/download", PROJECT_ID, FILE_ID)
                            .header(HttpHeaders.RANGE, "bytes=99-120").header(HttpHeaders.IF_RANGE, stale).principal(auth))
                    .andExpect(status().isOk()).andExpect(content().bytes(PAYLOAD))
                    .andExpect(header().doesNotExist(HttpHeaders.CONTENT_RANGE));
        }
        mockMvc.perform(get("/api/v1/projects/{p}/files/{f}/download", PROJECT_ID, FILE_ID)
                        .header(HttpHeaders.RANGE, "bytes=99-120").header(HttpHeaders.IF_RANGE, "\"current\"").principal(auth))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */16"));
        verify(storageService, times(3)).readObject(new StorageReadRequest("documents", "projects/10/files/clip.webm", Optional.empty()));
    }

    @Test
    void download_multiRangeAndIfRangePolicy() throws Exception {
        when(projectFileRepository.findByIdAndProjectId(FILE_ID, PROJECT_ID)).thenReturn(Optional.of(storedEntity));
        Instant lastMod = storedEntity.getUpdatedAt();
        when(storageService.statObject("documents", "projects/10/files/clip.webm")).thenReturn(
                Optional.of(new StorageObjectStat(16L, "video/webm", Optional.of("\"strong-1\""), Optional.of(lastMod)))
        );

        // Multi-range -> ignored, returns 200 full
        when(storageService.readObject(new StorageReadRequest("documents", "projects/10/files/clip.webm", Optional.empty())))
                .thenAnswer(invocation -> new StorageReadResult(new ByteArrayInputStream(PAYLOAD), 16, 16, "video/webm",
                        Optional.empty(), Optional.of("\"strong-1\"")));
        mockMvc.perform(get("/api/v1/projects/{p}/files/{f}/download", PROJECT_ID, FILE_ID)
                        .header(HttpHeaders.RANGE, "bytes=0-1, 4-5")
                        .principal(auth))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "16"));

        // If-Range mismatch -> returns 200 full
        mockMvc.perform(get("/api/v1/projects/{p}/files/{f}/download", PROJECT_ID, FILE_ID)
                        .header(HttpHeaders.RANGE, "bytes=0-3")
                        .header(HttpHeaders.IF_RANGE, "\"different-etag\"")
                        .principal(auth))
                .andExpect(status().isOk());

        // If-Range weak ETag -> returns 200 full
        mockMvc.perform(get("/api/v1/projects/{p}/files/{f}/download", PROJECT_ID, FILE_ID)
                        .header(HttpHeaders.RANGE, "bytes=0-3")
                        .header(HttpHeaders.IF_RANGE, "W/\"strong-1\"")
                        .principal(auth))
                .andExpect(status().isOk());

        // If-Range matching strong ETag -> returns 206
        byte[] slice = "0123".getBytes(StandardCharsets.UTF_8);
        when(storageService.readObject(new StorageReadRequest("documents", "projects/10/files/clip.webm", Optional.of(new ByteRange(0, 3)))))
                .thenAnswer(invocation -> new StorageReadResult(new ByteArrayInputStream(slice), 16, 4, "video/webm",
                        Optional.of(new ByteRange(0, 3)), Optional.of("\"strong-1\"")));
        mockMvc.perform(get("/api/v1/projects/{p}/files/{f}/download", PROJECT_ID, FILE_ID)
                        .header(HttpHeaders.RANGE, "bytes=0-3")
                        .header(HttpHeaders.IF_RANGE, "\"strong-1\"")
                        .principal(auth))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-3/16"));

        // If-Range matching HTTP-date -> returns 206
        String httpDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(lastMod, ZoneOffset.UTC));
        mockMvc.perform(get("/api/v1/projects/{p}/files/{f}/download", PROJECT_ID, FILE_ID)
                        .header(HttpHeaders.RANGE, "bytes=0-3")
                        .header(HttpHeaders.IF_RANGE, httpDate)
                        .principal(auth))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-3/16"));
    }

    @Test
    void download_unauthorizedOrMissingFileNeverTouchesProvider() throws Exception {
        doThrow(new BusinessException(HttpStatus.FORBIDDEN.value(), "You are not a member of this project"))
                .when(projectSecurityService).validateMember(PROJECT_ID, USER_ID);

        mockMvc.perform(get("/api/v1/projects/{p}/files/{f}/download", PROJECT_ID, FILE_ID)
                        .header(HttpHeaders.RANGE, "bytes=0-3")
                        .principal(auth))
                .andExpect(status().isForbidden());

        reset(projectSecurityService);
        when(projectFileRepository.findByIdAndProjectId(FILE_ID, PROJECT_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/projects/{p}/files/{f}/download", PROJECT_ID, FILE_ID)
                        .header(HttpHeaders.RANGE, "bytes=0-3")
                        .principal(auth))
                .andExpect(status().isNotFound());

        verifyNoInteractions(storageService);
        verifyNoInteractions(googleDriveService);
    }

    @Test
    void download_closesStreamWhenStreamReadThrowsOrClientAborts() throws Exception {
        when(projectFileRepository.findByIdAndProjectId(FILE_ID, PROJECT_ID)).thenReturn(Optional.of(storedEntity));

        // 1. Client abort ("Broken pipe") handled gracefully by GlobalExceptionHandler AND closes stream
        AtomicBoolean abortClosed = new AtomicBoolean(false);
        InputStream abortStream = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("Broken pipe");
            }

            @Override
            public void close() {
                abortClosed.set(true);
            }
        };
        when(storageService.readObject(any(StorageReadRequest.class))).thenReturn(
                new StorageReadResult(abortStream, 16, 4, "video/webm",
                        Optional.of(new ByteRange(0, 3)), Optional.empty())
        );

        mockMvc.perform(get("/api/v1/projects/{p}/files/{f}/download", PROJECT_ID, FILE_ID)
                        .header(HttpHeaders.RANGE, "bytes=0-3")
                        .principal(auth))
                .andExpect(status().isPartialContent());
        assertThat(abortClosed).isTrue();

        // 2. Non-client-abort IO failure returns 500 AND closes stream
        AtomicBoolean errorClosed = new AtomicBoolean(false);
        InputStream errorStream = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("Provider socket reset");
            }

            @Override
            public void close() {
                errorClosed.set(true);
            }
        };
        when(storageService.readObject(any(StorageReadRequest.class))).thenReturn(
                new StorageReadResult(errorStream, 16, 4, "video/webm",
                        Optional.of(new ByteRange(0, 3)), Optional.empty())
        );

        mockMvc.perform(get("/api/v1/projects/{p}/files/{f}/download", PROJECT_ID, FILE_ID)
                        .header(HttpHeaders.RANGE, "bytes=0-3")
                        .principal(auth))
                .andExpect(status().isInternalServerError());
        assertThat(errorClosed).isTrue();
    }

    @Test
    void download_driveIfRangeUsesStableValidatorWhenMediaAndMetadataEtagsDiffer() throws Exception {
        storedEntity.setStorageBucket("googledrive");
        storedEntity.setStorageKey("drive-key-42");
        when(projectFileRepository.findByIdAndProjectId(FILE_ID, PROJECT_ID)).thenReturn(Optional.of(storedEntity));
        when(googleDriveService.statObject("drive-key-42")).thenReturn(Optional.of(
                new StorageObjectStat(16, "video/webm", Optional.of("\"drive-md5\""), Optional.of(storedEntity.getUpdatedAt()))));
        when(googleDriveService.readObject(eq("drive-key-42"), any())).thenAnswer(call -> {
            Optional<ByteRange> range = call.getArgument(1);
            byte[] bytes = range.isPresent() ? "2345".getBytes(StandardCharsets.UTF_8) : PAYLOAD;
            return new StorageReadResult(new ByteArrayInputStream(bytes), 16, bytes.length, "video/webm", range,
                    Optional.of("\"media-http-etag\""));
        });
        String validator = mockMvc.perform(get("/api/v1/projects/{p}/files/{f}/download", PROJECT_ID, FILE_ID).principal(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        mockMvc.perform(get("/api/v1/projects/{p}/files/{f}/download", PROJECT_ID, FILE_ID).principal(auth)
                        .header(HttpHeaders.RANGE, "bytes=2-5").header(HttpHeaders.IF_RANGE, validator))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.ETAG, validator))
                .andExpect(content().bytes("2345".getBytes(StandardCharsets.UTF_8)));
    }

    private static InputStream trackingStream(byte[] bytes, AtomicBoolean closed) {
        return new ByteArrayInputStream(bytes) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
    }
}
