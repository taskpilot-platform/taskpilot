package com.taskpilot.projects.files.service;

import com.taskpilot.contracts.user.dto.UserIdentityDto;
import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.storage.StorageService;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveService;
import com.taskpilot.projects.common.entity.ProjectEntity;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    private UserIdentityPort userIdentityPort;

    @Mock
    private UserProfilePort userProfilePort;

    @InjectMocks
    private ProjectFileService projectFileService;

    private final Long projectId = 10L;
    private final Long userId = 100L;
    private final String email = "user@test.com";

    @BeforeEach
    void setUp() {
        lenient().when(userIdentityPort.findByEmail(email)).thenReturn(Optional.of(new UserIdentityDto(userId, email)));
    }

    @Test
    void uploadFile_success() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "dummy content".getBytes());
        when(storageService.uploadFile(eq(file), anyString(), eq("documents"))).thenReturn("projects/10/files/doc.pdf");

        ProjectFileEntity savedEntity = ProjectFileEntity.builder()
                .id(1L)
                .projectId(projectId)
                .uploaderId(userId)
                .fileName("doc.pdf")
                .originalName("doc.pdf")
                .fileSize(file.getSize())
                .contentType("application/pdf")
                .storageKey("projects/10/files/doc.pdf")
                .storageBucket("documents")
                .build();
        when(projectFileRepository.save(any())).thenReturn(savedEntity);
        when(userProfilePort.findLiteById(userId)).thenReturn(Optional.of(new UserProfileLiteDto(userId, "John Doe", null)));

        ProjectFileResponse response = projectFileService.uploadFile(projectId, email, file, "Spec document");

        assertNotNull(response);
        assertEquals(1L, response.id());
        assertEquals("doc.pdf", response.fileName());
        assertEquals("John Doe", response.uploaderName());
        verify(projectSecurityService).requireActiveProject(projectId);
        verify(projectSecurityService).validateMember(projectId, userId);
    }

    @Test
    void uploadFile_emptyFile_throwsBadRequest() {
        MockMultipartFile file = new MockMultipartFile("file", "", "text/plain", new byte[0]);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectFileService.uploadFile(projectId, email, file, "desc"));
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

        Page<ProjectFileResponse> result = projectFileService.listFiles(projectId, email, null, pageRequest);

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

        FileDownloadResource resource = projectFileService.downloadFile(projectId, 1L, email);

        assertNotNull(resource);
        assertEquals("guide.pdf", resource.fileName());
        assertEquals(2048L, resource.fileSize());
    }

    @Test
    void deleteFile_asUploader_success() {
        ProjectFileEntity entity = ProjectFileEntity.builder()
                .id(1L)
                .projectId(projectId)
                .uploaderId(userId)
                .storageKey("projects/10/files/test.txt")
                .storageBucket("documents")
                .build();
        when(projectFileRepository.findByIdAndProjectId(1L, projectId)).thenReturn(Optional.of(entity));

        projectFileService.deleteFile(projectId, 1L, email);

        verify(storageService).deleteFile("documents", "projects/10/files/test.txt");
        verify(projectFileRepository).delete(entity);
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

        projectFileService.deleteFile(projectId, 1L, email);

        verify(projectFileRepository).delete(entity);
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
                () -> projectFileService.deleteFile(projectId, 1L, email));
        assertEquals(403, ex.getStatus());
        verify(projectFileRepository, never()).delete(any());
    }
}
