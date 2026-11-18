package com.taskpilot.projects.files.service;

import com.taskpilot.contracts.storage.StorageUploadOperationPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.MeetingRecordingEntity;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.repository.MeetingRecordingRepository;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectFileRecordingAttachmentTest {
    @Test
    void aRetriedRecordingUploadCannotAttachASecondProjectFile() {
        var files = mock(ProjectFileRepository.class);
        var projects = mock(ProjectRepository.class);
        var recordings = mock(MeetingRecordingRepository.class);
        var operations = mock(StorageUploadOperationPort.class);
        var attachments = new ProjectFileAttachmentService(files, projects, mock(ProjectSecurityService.class), operations, recordings);
        UUID id = UUID.randomUUID();
        String name = "meeting_4_rec_" + id + ".webm";
        when(projects.findByIdForUpdate(2L)).thenReturn(Optional.of(ProjectEntity.builder().id(2L).build()));
        when(recordings.findById(id)).thenReturn(Optional.of(MeetingRecordingEntity.builder().id(id)
                .projectId(2L).meetingId(4L).recorderId(7L).state(MeetingRecordingEntity.State.UPLOADING).build()));
        when(files.existsByProjectIdAndUploaderIdAndOriginalNameIn(2L, 7L,
                List.of(name, "meeting_4_rec_" + id + ".mp4"))).thenReturn(false, true);
        when(files.saveAndFlush(any(ProjectFileEntity.class))).thenAnswer(call -> {
            ProjectFileEntity file = call.getArgument(0);
            file.setId(8L);
            return file;
        });

        assertEquals(8L, attachments.attachUploadedFile(2L, 7L, name, 50, "video/webm",
                "first", "googledrive", "", UUID.randomUUID()).getId());
        assertEquals(409, assertThrows(BusinessException.class, () -> attachments.attachUploadedFile(2L, 7L, name,
                50, "video/webm", "duplicate", "googledrive", "", UUID.randomUUID())).getStatus());
        verify(files).saveAndFlush(any(ProjectFileEntity.class));
        verify(operations).markAttachedInCurrentTransaction(any(), eq(8L));
    }
}
