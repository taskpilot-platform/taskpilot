package com.taskpilot.projects.files.service;

import com.taskpilot.contracts.storage.StorageCleanupPort;
import com.taskpilot.contracts.storage.StorageCleanupRequest;
import com.taskpilot.contracts.storage.StorageProviderKind;
import com.taskpilot.contracts.storage.StorageTargetDomain;
import com.taskpilot.contracts.storage.StorageUploadOperationDto;
import com.taskpilot.contracts.storage.StorageUploadOperationPort;
import com.taskpilot.contracts.storage.StorageUploadOperationStatus;
import com.taskpilot.contracts.storage.StorageUploadReservationCommand;
import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.storage.StorageService;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveFileDto;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveService;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.files.dto.FileDownloadResource;
import com.taskpilot.projects.files.dto.ProjectFileResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectFileServiceTest {

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
    private ProjectFileAttachmentService projectFileAttachmentService;

    @Mock
    private StorageUploadOperationPort uploadOperationPort;

    @Mock
    private StorageCleanupPort storageCleanupPort;

    private ProjectFileService projectFileService;

    private final Long projectId = 10L;
    private final Long userId = 100L;
    private final CurrentActor actor = new CurrentActor(userId, "user@test.com", "USER", 1L);

    @BeforeEach
    void setUp() {
        projectFileService = new ProjectFileService(
                projectFileRepository,
                storageService,
                googleDriveService,
                projectSecurityService,
                projectMemberRepository,
                userProfilePort,
                projectFileAttachmentService,
                uploadOperationPort,
                storageCleanupPort
        );
    }

    @Test
    void uploadFile_s3Success_reservesBeforeWriteAndAttachesViaProxy() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "dummy content".getBytes());
        UUID opId = UUID.randomUUID();
        String plannedKey = "projects/10/files/u1-doc.pdf";

        when(storageService.planObjectKey("projects/10/files", "doc.pdf")).thenReturn(plannedKey);
        when(uploadOperationPort.reserve(any(StorageUploadReservationCommand.class))).thenReturn(
                new StorageUploadOperationDto(opId, userId, projectId, StorageTargetDomain.PROJECT_FILE,
                        StorageProviderKind.S3, "documents", plannedKey, null, StorageUploadOperationStatus.RESERVED, null, Instant.now().plusSeconds(600))
        );
        when(storageService.uploadFileAtKey(file, "documents", plannedKey)).thenReturn(plannedKey);

        ProjectFileEntity savedEntity = ProjectFileEntity.builder()
                .id(1L)
                .projectId(projectId)
                .uploaderId(userId)
                .fileName("doc.pdf")
                .originalName("doc.pdf")
                .fileSize(file.getSize())
                .contentType("application/pdf")
                .storageKey(plannedKey)
                .storageBucket("documents")
                .description("Spec document")
                .build();
        when(projectFileAttachmentService.attachUploadedFile(
                eq(projectId), eq(userId), eq("doc.pdf"), eq(file.getSize()), eq("application/pdf"),
                eq(plannedKey), eq("documents"), eq("Spec document"), eq(opId)))
                .thenReturn(savedEntity);
        when(userProfilePort.findLiteById(userId)).thenReturn(Optional.of(new UserProfileLiteDto(userId, "John Doe", null)));

        ProjectFileResponse response = projectFileService.uploadFile(projectId, actor, file, "Spec document");

        assertNotNull(response);
        assertEquals(1L, response.id());
        assertEquals("doc.pdf", response.fileName());
        assertEquals("John Doe", response.uploaderName());
        assertEquals("Spec document", response.description());
        verify(uploadOperationPort).reserve(any());
        verify(uploadOperationPort).markProviderWritten(opId, plannedKey);
        verify(projectSecurityService).requireActiveProject(projectId);
        verify(projectSecurityService).validateMember(projectId, userId);
    }

    @Test
    void uploadFile_drivePrimaryAndFallback_neverAppendsOrExposesProviderLinks() throws IOException {
        MockMultipartFile video = new MockMultipartFile("file", "clip.webm", "video/webm", "webm-bytes".getBytes());
        UUID driveOpId = UUID.randomUUID();
        when(googleDriveService.isConfigured()).thenReturn(true);
        when(uploadOperationPort.reserve(any())).thenReturn(
                new StorageUploadOperationDto(driveOpId, userId, projectId, StorageTargetDomain.PROJECT_FILE,
                        StorageProviderKind.GOOGLE_DRIVE, "googledrive", null, null, StorageUploadOperationStatus.RESERVED, null, Instant.now().plusSeconds(600))
        );
        when(googleDriveService.uploadFile(eq(video), isNull(), eq(driveOpId))).thenReturn(
                GoogleDriveFileDto.builder().fileId("drive-id-1").name("clip.webm").mimeType("video/webm").size(10L).build()
        );
        ProjectFileEntity savedDrive = ProjectFileEntity.builder()
                .id(2L)
                .projectId(projectId)
                .uploaderId(userId)
                .fileName("clip.webm")
                .originalName("clip.webm")
                .fileSize(video.getSize())
                .contentType("video/webm")
                .storageKey("drive-id-1")
                .storageBucket("googledrive")
                .description("User note")
                .build();
        when(projectFileAttachmentService.attachUploadedFile(
                eq(projectId), eq(userId), eq("clip.webm"), eq(video.getSize()), eq("video/webm"),
                eq("drive-id-1"), eq("googledrive"), eq("User note"), eq(driveOpId)))
                .thenReturn(savedDrive);
        when(userProfilePort.findLiteById(userId)).thenReturn(Optional.of(new UserProfileLiteDto(userId, "John Doe", null)));

        ProjectFileResponse driveResponse = projectFileService.uploadFile(projectId, actor, video, "User note");
        assertEquals("User note", driveResponse.description());
        assertFalse(driveResponse.description().contains("http"));

        // S3 failure fallback to Drive must also keep description clean
        MockMultipartFile doc = new MockMultipartFile("file", "notes.pdf", "application/pdf", "pdf-bytes".getBytes());
        String plannedKey = "projects/10/files/u2-notes.pdf";
        UUID s3OpId = UUID.randomUUID();
        UUID fallbackOpId = UUID.randomUUID();
        when(storageService.planObjectKey("projects/10/files", "notes.pdf")).thenReturn(plannedKey);
        when(uploadOperationPort.reserve(argThat(cmd -> cmd != null && cmd.provider() == StorageProviderKind.S3)))
                .thenReturn(new StorageUploadOperationDto(s3OpId, userId, projectId, StorageTargetDomain.PROJECT_FILE,
                        StorageProviderKind.S3, "documents", plannedKey, null, StorageUploadOperationStatus.RESERVED, null, Instant.now().plusSeconds(600)));
        when(uploadOperationPort.reserve(argThat(cmd -> cmd != null && cmd.provider() == StorageProviderKind.GOOGLE_DRIVE)))
                .thenReturn(new StorageUploadOperationDto(fallbackOpId, userId, projectId, StorageTargetDomain.PROJECT_FILE,
                        StorageProviderKind.GOOGLE_DRIVE, "googledrive", null, null, StorageUploadOperationStatus.RESERVED, null, Instant.now().plusSeconds(600)));
        when(storageService.uploadFileAtKey(doc, "documents", plannedKey)).thenThrow(new IOException("S3 down"));
        when(googleDriveService.uploadFile(eq(doc), isNull(), eq(fallbackOpId))).thenReturn(
                GoogleDriveFileDto.builder().fileId("drive-fallback-2").name("notes.pdf").mimeType("application/pdf").size(9L).build()
        );
        ProjectFileEntity savedFallback = ProjectFileEntity.builder()
                .id(3L)
                .projectId(projectId)
                .uploaderId(userId)
                .fileName("notes.pdf")
                .originalName("notes.pdf")
                .fileSize(doc.getSize())
                .contentType("application/pdf")
                .storageKey("drive-fallback-2")
                .storageBucket("googledrive")
                .description(null)
                .build();
        when(projectFileAttachmentService.attachUploadedFile(
                eq(projectId), eq(userId), eq("notes.pdf"), eq(doc.getSize()), eq("application/pdf"),
                eq("drive-fallback-2"), eq("googledrive"), isNull(), eq(fallbackOpId)))
                .thenReturn(savedFallback);

        ProjectFileResponse fallbackResponse = projectFileService.uploadFile(projectId, actor, doc, null);
        assertNull(fallbackResponse.description());
        verify(uploadOperationPort).compensateFailedUpload(eq(s3OpId), any(), eq("S3_UPLOAD_FAILED"), anyString());
    }

    @Test
    void uploadFile_metadataFailureCompensatesUploadedObject() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "dummy content".getBytes());
        UUID opId = UUID.randomUUID();
        String plannedKey = "projects/10/files/u1-doc.pdf";

        when(storageService.planObjectKey("projects/10/files", "doc.pdf")).thenReturn(plannedKey);
        when(uploadOperationPort.reserve(any())).thenReturn(
                new StorageUploadOperationDto(opId, userId, projectId, StorageTargetDomain.PROJECT_FILE,
                        StorageProviderKind.S3, "documents", plannedKey, null, StorageUploadOperationStatus.RESERVED, null, Instant.now().plusSeconds(600))
        );
        when(storageService.uploadFileAtKey(file, "documents", plannedKey)).thenReturn(plannedKey);
        when(projectFileAttachmentService.attachUploadedFile(any(), any(), any(), anyLong(), any(), any(), any(), any(), eq(opId)))
                .thenThrow(new RuntimeException("DB constraint failure"));

        assertThrows(RuntimeException.class, () -> projectFileService.uploadFile(projectId, actor, file, "desc"));

        verify(uploadOperationPort).compensateFailedUpload(
                eq(opId),
                argThat(loc -> loc != null && plannedKey.equals(loc.objectKey())),
                eq("METADATA_ATTACH_FAILED"),
                contains("DB constraint failure")
        );
    }

    @Test
    void uploadFile_emptyFile_throwsBadRequest() {
        MockMultipartFile file = new MockMultipartFile("file", "", "text/plain", new byte[0]);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectFileService.uploadFile(projectId, actor, file, "desc"));
        assertEquals(400, ex.getStatus());
    }

    @Test
    void listFiles_success() {
        ProjectFileEntity entity = ProjectFileEntity.builder()
                .id(1L)
                .projectId(projectId)
                .uploaderId(userId)
                .fileName("plan.docx")
                .originalName("plan.docx")
                .fileSize(1024L)
                .build();
        PageRequest pageRequest = PageRequest.of(0, 10);
        when(projectFileRepository.findByProjectId(projectId, pageRequest))
                .thenReturn(new PageImpl<>(List.of(entity)));
        when(userProfilePort.findLiteByIds(anySet()))
                .thenReturn(List.of(new UserProfileLiteDto(userId, "John Doe", null)));

        Page<ProjectFileResponse> result = projectFileService.listFiles(projectId, userId, null, pageRequest);

        assertEquals(1, result.getTotalElements());
        assertEquals("plan.docx", result.getContent().get(0).fileName());
        assertEquals("John Doe", result.getContent().get(0).uploaderName());
    }

    @Test
    void downloadFile_success() throws IOException {
        ProjectFileEntity entity = ProjectFileEntity.builder()
                .id(1L)
                .projectId(projectId)
                .fileName("guide.pdf")
                .originalName("guide.pdf")
                .contentType("application/pdf")
                .fileSize(2048L)
                .storageKey("projects/10/files/guide.pdf")
                .storageBucket("documents")
                .build();
        when(projectFileRepository.findByIdAndProjectId(1L, projectId)).thenReturn(Optional.of(entity));
        when(storageService.downloadFile("documents", "projects/10/files/guide.pdf"))
                .thenReturn(new ByteArrayInputStream("pdf data".getBytes()));

        FileDownloadResource resource = projectFileService.downloadFile(projectId, 1L, userId);

        assertNotNull(resource);
        assertEquals("guide.pdf", resource.fileName());
        assertEquals(2048L, resource.fileSize());
    }

    @Test
    void deleteFile_asUploader_enqueuesCleanupInsteadOfDirectProviderDelete() {
        ProjectFileEntity entity = ProjectFileEntity.builder()
                .id(1L)
                .projectId(projectId)
                .uploaderId(userId)
                .storageKey("projects/10/files/test.txt")
                .storageBucket("documents")
                .build();
        when(projectFileRepository.findByIdAndProjectId(1L, projectId)).thenReturn(Optional.of(entity));

        projectFileService.deleteFile(projectId, 1L, userId);

        verify(projectFileRepository).delete(entity);
        ArgumentCaptor<StorageCleanupRequest> captor = ArgumentCaptor.forClass(StorageCleanupRequest.class);
        verify(storageCleanupPort).enqueueCleanup(captor.capture());
        assertEquals("projects/10/files/test.txt", captor.getValue().locator().objectKey());
        assertEquals("PROJECT_FILE", captor.getValue().sourceType());
        verifyNoInteractions(storageService);
        verifyNoInteractions(googleDriveService);
    }

    @Test
    void deleteFile_asManager_success() {
        Long anotherUser = 999L;
        ProjectFileEntity entity = ProjectFileEntity.builder()
                .id(1L)
                .projectId(projectId)
                .uploaderId(anotherUser)
                .storageKey("projects/10/files/test.txt")
                .storageBucket("documents")
                .build();
        when(projectFileRepository.findByIdAndProjectId(1L, projectId)).thenReturn(Optional.of(entity));

        ProjectMemberEntity managerMember = ProjectMemberEntity.builder()
                .userId(userId)
                .role(MemberRole.MANAGER)
                .build();
        when(projectMemberRepository.findByProjectIdAndUserId(projectId, userId))
                .thenReturn(Optional.of(managerMember));

        projectFileService.deleteFile(projectId, 1L, userId);

        verify(projectFileRepository).delete(entity);
        verify(storageCleanupPort).enqueueCleanup(any(StorageCleanupRequest.class));
    }

    @Test
    void deleteFile_asRegularMember_throwsForbidden() {
        Long anotherUser = 999L;
        ProjectFileEntity entity = ProjectFileEntity.builder()
                .id(1L)
                .projectId(projectId)
                .uploaderId(anotherUser)
                .build();
        when(projectFileRepository.findByIdAndProjectId(1L, projectId)).thenReturn(Optional.of(entity));

        ProjectMemberEntity regularMember = ProjectMemberEntity.builder()
                .userId(userId)
                .role(MemberRole.MEMBER)
                .build();
        when(projectMemberRepository.findByProjectIdAndUserId(projectId, userId))
                .thenReturn(Optional.of(regularMember));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectFileService.deleteFile(projectId, 1L, userId));
        assertEquals(403, ex.getStatus());
        verify(projectFileRepository, never()).delete(any());
        verifyNoInteractions(storageCleanupPort);
    }
}
