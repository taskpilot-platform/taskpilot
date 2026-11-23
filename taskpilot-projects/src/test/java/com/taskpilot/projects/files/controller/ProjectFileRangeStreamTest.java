package com.taskpilot.projects.files.controller;

import com.taskpilot.contracts.user.dto.AccountAuthStateDto;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.infrastructure.config.security.FileStreamTokenService;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.storage.EgressS3StorageService;
import com.taskpilot.infrastructure.storage.StorageService;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveService;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.files.dto.FileStreamResource;
import com.taskpilot.infrastructure.storage.range.ByteRange;
import com.taskpilot.infrastructure.storage.range.StorageReadResult;
import com.taskpilot.infrastructure.storage.range.StorageObjectStat;
import org.springframework.mock.web.MockHttpServletRequest;
import com.taskpilot.projects.files.service.ProjectFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectFileRangeStreamTest {

    @Mock
    private ProjectFileRepository projectFileRepository;
    @Mock
    private StorageService storageService;
    @Mock
    private GoogleDriveService googleDriveService;
    @Mock
    private EgressS3StorageService egressS3StorageService;
    @Mock
    private ProjectSecurityService projectSecurityService;
    @Mock
    private ProjectMemberRepository projectMemberRepository;
    @Mock
    private AccountAuthStatePort accountAuthStatePort;
    @Mock
    private UserProfilePort userProfilePort;

    private ProjectFileService fileService;
    private FileStreamTokenService tokenService;
    private ProjectFileController controller;

    @BeforeEach
    void setUp() {
        fileService = new ProjectFileService(
                projectFileRepository,
                storageService,
                googleDriveService,
                projectSecurityService,
                projectMemberRepository,
                userProfilePort
        );
        fileService.setEgressS3StorageService(egressS3StorageService);
        tokenService = new FileStreamTokenService("404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970", accountAuthStatePort);
        controller = new ProjectFileController(fileService, tokenService);

        lenient().when(accountAuthStatePort.findById(7L))
                .thenReturn(Optional.of(new AccountAuthStateDto(7L, "member@taskpilot.io", "USER", "ACTIVE", true, 0L)));
        lenient().when(googleDriveService.isConfigured()).thenReturn(true);
        lenient().when(egressS3StorageService.isConfigured()).thenReturn(true);
    }

    @Test
    void streamFile_returns206PartialContentForGoogleDriveWithSignedToken() throws Exception {
        ProjectFileEntity entity = ProjectFileEntity.builder()
                .id(42L)
                .projectId(10L)
                .originalName("meeting.mp4")
                .contentType("video/mp4")
                .fileSize(1000L)
                .storageProvider("gdrive")
                .storageBucket("googledrive")
                .storageKey("drive-id-123")
                .build();

        when(projectFileRepository.findByIdAndProjectId(42L, 10L)).thenReturn(Optional.of(entity));
        when(googleDriveService.readObject("drive-id-123", Optional.of(new ByteRange(100L, 109L))))
                .thenReturn(read("0123456789", 1000L, new ByteRange(100L, 109L)));

        String signedToken = tokenService.issueToken(7L, 10L, 42L).token();

        ResponseEntity<StreamingResponseBody> response = controller.streamFile(
                10L, 42L, "bytes=100-109", signedToken, null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 100-109/1000");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(10L);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(response.getBody()).isNotNull();
        response.getBody().writeTo(out);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("0123456789");

        verify(projectSecurityService).validateMember(10L, 7L);
        verify(googleDriveService).readObject("drive-id-123", Optional.of(new ByteRange(100L, 109L)));
    }

    @Test
    void streamFile_rejectsMissingAuthAndInvalidTokenWith401() {
        ResponseEntity<StreamingResponseBody> response = controller.streamFile(
                10L, 42L, "bytes=0-10", "invalid-token", null
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void streamFile_rejectsUnsatisfiableRangeWith416AndContentRangeHeader() {
        ProjectFileEntity entity = ProjectFileEntity.builder()
                .id(42L).projectId(10L).originalName("meeting.mp4").contentType("video/mp4")
                .fileSize(100L).storageProvider("b2").storageKey("recordings/m.mp4").build();
        when(projectFileRepository.findByIdAndProjectId(42L, 10L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> fileService.streamFile(10L, 42L, 7L, "bytes=500-600"))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(416);

        String signedToken = tokenService.issueToken(7L, 10L, 42L).token();
        ResponseEntity<StreamingResponseBody> resp = controller.streamFile(10L, 42L, "bytes=500-600", signedToken, null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */100");
    }

    @Test
    void streamFile_supportsSuffixRangeOpenEndedGdriveChunkCapFull200AndRbacRejection() throws Exception {
        long fiftyMb = 50L * 1024L * 1024L;
        ProjectFileEntity gdriveEntity = ProjectFileEntity.builder()
                .id(42L).projectId(10L).originalName("meeting.mp4").contentType("video/mp4")
                .fileSize(fiftyMb).storageProvider("gdrive").storageBucket("googledrive").storageKey("drive-big").build();
        when(projectFileRepository.findByIdAndProjectId(42L, 10L)).thenReturn(Optional.of(gdriveEntity));

        when(googleDriveService.readObject(org.mockito.ArgumentMatchers.eq("drive-big"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    Optional<ByteRange> range = call.getArgument(1);
                    long responseBytes = range.map(ByteRange::length).orElse(fiftyMb);
                    return new StorageReadResult(new ByteArrayInputStream(new byte[0]), fiftyMb, responseBytes,
                            "video/mp4", range, Optional.empty());
                });

        // 1. Suffix range: bytes=-100
        FileStreamResource suffixRes = fileService.streamFile(10L, 42L, 7L, "bytes=-100");
        assertThat(suffixRes.partial()).isTrue();
        assertThat(suffixRes.rangeStart()).isEqualTo(fiftyMb - 100);
        assertThat(suffixRes.rangeEnd()).isEqualTo(fiftyMb - 1);

        // 2. Open-ended Google Drive range bytes=1024- is capped to 8MB chunk
        FileStreamResource openEndedRes = fileService.streamFile(10L, 42L, 7L, "bytes=1024-");
        assertThat(openEndedRes.partial()).isTrue();
        assertThat(openEndedRes.rangeStart()).isEqualTo(1024L);
        assertThat(openEndedRes.contentLength()).isEqualTo(8L * 1024L * 1024L);

        // 3. Full content 200 OK without Range header + attachment disposition when download=true
        String signedToken = tokenService.issueToken(7L, 10L, 42L).token();
        ResponseEntity<StreamingResponseBody> fullResp =
                controller.streamFile(10L, 42L, null, signedToken, true, null);
        assertThat(fullResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fullResp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment;");
        fullResp.getBody().writeTo(new ByteArrayOutputStream());
        suffixRes.streamOpener().open().close();
        openEndedRes.streamOpener().open().close();

        // 4. Non-member RBAC rejection even with valid signed token
        doThrow(new BusinessException(403, "Not a project member"))
                .when(projectSecurityService).validateMember(10L, 7L);
        assertThatThrownBy(() -> controller.streamFile(10L, 42L, "bytes=0-10", signedToken, null))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(403);
    }

    @Test
    void streamFile_staleIfRangeReturnsFullB2AndMatchingRangeReturns416() throws Exception {
        ProjectFileEntity entity = ProjectFileEntity.builder().id(42L).projectId(10L)
                .originalName("meeting.mp4").fileSize(100L).storageProvider("b2")
                .storageBucket("recordings").storageKey("recording").build();
        when(projectFileRepository.findByIdAndProjectId(42L, 10L)).thenReturn(Optional.of(entity));
        when(egressS3StorageService.statObject("recording")).thenReturn(Optional.of(
                new StorageObjectStat(100L, "video/mp4", Optional.of("\"current\""), Optional.empty())));
        when(egressS3StorageService.readObject("recording", Optional.empty()))
                .thenReturn(read("body", 100L, null));
        String token = tokenService.issueToken(7L, 10L, 42L).token();
        ResponseEntity<StreamingResponseBody> response = controller.streamFile(
                10L, 42L, "bytes=500-600", "\"stale\"", token, false, null, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        response.getBody().writeTo(new ByteArrayOutputStream());
        ResponseEntity<StreamingResponseBody> matching = controller.streamFile(
                10L, 42L, "bytes=500-600", "\"current\"", token, false, null, null);
        assertThat(matching.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        org.mockito.Mockito.verifyNoInteractions(storageService);
    }

    @Test
    void streamFile_headUsesMetadataWithoutOpeningProviderBody() throws Exception {
        ProjectFileEntity entity = ProjectFileEntity.builder().id(42L).projectId(10L)
                .originalName("meeting.mp4").fileSize(100L).storageProvider("b2")
                .storageBucket("recordings").storageKey("recording").build();
        when(projectFileRepository.findByIdAndProjectId(42L, 10L)).thenReturn(Optional.of(entity));
        when(egressS3StorageService.statObject("recording")).thenReturn(Optional.of(
                new StorageObjectStat(100L, "video/mp4", Optional.of("\"current\""), Optional.empty())));
        MockHttpServletRequest request = new MockHttpServletRequest("HEAD", "/");
        ResponseEntity<StreamingResponseBody> response = controller.streamFile(10L, 42L, "bytes=10-19", null,
                tokenService.issueToken(7L, 10L, 42L).token(), false, null, request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getHeaders().getContentLength()).isEqualTo(10L);
        assertThat(response.getBody()).isNull();
        org.mockito.Mockito.verify(egressS3StorageService, org.mockito.Mockito.never())
                .readObject(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void streamFile_rejectsMismatchedProviderRangeAndClosesBodyBeforeReturning206() throws Exception {
        ProjectFileEntity entity = ProjectFileEntity.builder().id(42L).projectId(10L).originalName("clip")
                .fileSize(100L).storageProvider("b2").storageBucket("recordings").storageKey("recording").build();
        when(projectFileRepository.findByIdAndProjectId(42L, 10L)).thenReturn(Optional.of(entity));
        var body = org.mockito.Mockito.spy(new ByteArrayInputStream(new byte[10]));
        when(egressS3StorageService.readObject("recording", Optional.of(new ByteRange(10, 19))))
                .thenReturn(new StorageReadResult(body, 100L, 10L, "video/mp4",
                        Optional.of(new ByteRange(0, 9)), Optional.empty()));
        assertThatThrownBy(() -> controller.streamFile(10L, 42L, "bytes=10-19",
                tokenService.issueToken(7L, 10L, 42L).token(), null)).isInstanceOf(BusinessException.class);
        verify(body).close();
    }

    @Test
    void streamFile_b2ProxyCapsOpenEndedRangeAndEnforcesRevocationAndTokenIsolation() throws Exception {
        long fiftyMb = 50L * 1024L * 1024L;
        ProjectFileEntity b2Entity = ProjectFileEntity.builder()
                .id(42L).projectId(10L).originalName("meeting.mp4").contentType("video/mp4")
                .fileSize(fiftyMb).storageProvider("b2").storageBucket("recordings").storageKey("recordings/m42.mp4").build();
        when(projectFileRepository.findByIdAndProjectId(42L, 10L)).thenReturn(Optional.of(b2Entity));
        when(egressS3StorageService.readObject(org.mockito.ArgumentMatchers.eq("recordings/m42.mp4"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    Optional<ByteRange> range = call.getArgument(1);
                    long responseBytes = range.map(ByteRange::length).orElse(fiftyMb);
                    return new StorageReadResult(new ByteArrayInputStream(new byte[8]), fiftyMb, responseBytes,
                            "video/mp4", range, Optional.of("\"b2-etag\""));
                });

        String signedToken = tokenService.issueToken(7L, 10L, 42L).token();

        // 1. Open-ended B2 range bytes=0- is capped to 8MB chunk so revocation & thread release happen between chunks.
        ResponseEntity<StreamingResponseBody> firstChunk = controller.streamFile(10L, 42L, "bytes=0-", signedToken, null);
        assertThat(firstChunk.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(firstChunk.getHeaders().getContentLength()).isEqualTo(8L * 1024L * 1024L);
        assertThat(firstChunk.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 0-" + (8L * 1024L * 1024L - 1) + "/" + fiftyMb);
        org.mockito.Mockito.verify(egressS3StorageService, org.mockito.Mockito.never()).generatePresignedGetUrl(org.mockito.ArgumentMatchers.any());

        // 2. Cross-project and cross-file token replay rejected with 401 before touching B2.
        assertThat(controller.streamFile(11L, 42L, "bytes=0-10", signedToken, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.streamFile(10L, 43L, "bytes=0-10", signedToken, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 3. Token version rotation invalidates already-issued stream URL on next seek/request.
        when(accountAuthStatePort.findById(7L))
                .thenReturn(Optional.of(new AccountAuthStateDto(7L, "member@taskpilot.io", "USER", "ACTIVE", true, 1L)));
        assertThat(controller.streamFile(10L, 42L, "bytes=1024-2047", signedToken, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 4. Suspended/locked account invalidates stream URL on next seek/request.
        when(accountAuthStatePort.findById(7L))
                .thenReturn(Optional.of(new AccountAuthStateDto(7L, "member@taskpilot.io", "USER", "SUSPENDED", true, 0L)));
        assertThat(controller.streamFile(10L, 42L, "bytes=1024-2047", signedToken, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 5. Restored account but membership revoked -> 403 Forbidden before touching B2.
        when(accountAuthStatePort.findById(7L))
                .thenReturn(Optional.of(new AccountAuthStateDto(7L, "member@taskpilot.io", "USER", "ACTIVE", true, 0L)));
        doThrow(new BusinessException(403, "Not a project member"))
                .when(projectSecurityService).validateMember(10L, 7L);
        assertThatThrownBy(() -> controller.streamFile(10L, 42L, "bytes=1024-2047", signedToken, null))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(403);
        org.mockito.Mockito.verify(egressS3StorageService, org.mockito.Mockito.times(1))
                .readObject(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void streamFile_b2ProviderErrorReturnsControlled500WithoutDirectPresignedFallback() throws Exception {
        ProjectFileEntity b2Entity = ProjectFileEntity.builder()
                .id(42L).projectId(10L).originalName("meeting.mp4").contentType("video/mp4")
                .fileSize(1000L).storageProvider("b2").storageBucket("recordings").storageKey("recordings/m42.mp4").build();
        when(projectFileRepository.findByIdAndProjectId(42L, 10L)).thenReturn(Optional.of(b2Entity));
        when(egressS3StorageService.readObject("recordings/m42.mp4", Optional.of(new ByteRange(0L, 99L))))
                .thenThrow(new java.io.IOException("B2 upstream 503"));

        String signedToken = tokenService.issueToken(7L, 10L, 42L).token();
        assertThatThrownBy(() -> controller.streamFile(10L, 42L, "bytes=0-99", signedToken, null))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(500);
        org.mockito.Mockito.verify(egressS3StorageService, org.mockito.Mockito.never()).generatePresignedGetUrl(org.mockito.ArgumentMatchers.any());
    }

    private static StorageReadResult read(String value, long total, ByteRange range) {
        return new StorageReadResult(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)), total,
                range == null ? total : range.length(), "video/mp4", Optional.ofNullable(range), Optional.empty());
    }
}
