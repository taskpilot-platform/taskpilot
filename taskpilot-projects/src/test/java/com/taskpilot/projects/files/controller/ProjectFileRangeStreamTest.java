package com.taskpilot.projects.files.controller;

import com.taskpilot.contracts.user.dto.UserIdentityDto;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;
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
    private UserIdentityPort userIdentityPort;
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
                userIdentityPort,
                userProfilePort
        );
        fileService.setEgressS3StorageService(egressS3StorageService);
        tokenService = new FileStreamTokenService("404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970");
        controller = new ProjectFileController(fileService, tokenService);

        lenient().when(userIdentityPort.findByEmail("member@taskpilot.io"))
                .thenReturn(Optional.of(new UserIdentityDto(7L, "member@taskpilot.io")));
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
        when(googleDriveService.downloadFileRange("drive-id-123", 100L, 109L))
                .thenReturn(new ByteArrayInputStream("0123456789".getBytes(StandardCharsets.UTF_8)));

        String signedToken = tokenService.issueToken("member@taskpilot.io", 10L, 42L).token();

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
        verify(googleDriveService).downloadFileRange("drive-id-123", 100L, 109L);
    }

    @Test
    void streamFile_rejectsMissingAuthAndInvalidTokenWith401() {
        ResponseEntity<StreamingResponseBody> response = controller.streamFile(
                10L, 42L, "bytes=0-10", "invalid-token", null
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void streamFile_rejectsUnsatisfiableRangeWith416() {
        ProjectFileEntity entity = ProjectFileEntity.builder()
                .id(42L).projectId(10L).originalName("meeting.mp4").contentType("video/mp4")
                .fileSize(100L).storageProvider("b2").storageKey("recordings/m.mp4").build();
        when(projectFileRepository.findByIdAndProjectId(42L, 10L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> fileService.streamFile(10L, 42L, "member@taskpilot.io", "bytes=500-600"))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(416);
    }
}
