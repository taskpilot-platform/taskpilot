package com.taskpilot.infrastructure.storage.googledrive;

import com.google.api.services.drive.Drive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class GoogleDriveServiceTest {

    @Mock
    private Drive drive;

    @Test
    void isConfigured_returnsTrueWhenFolderIdProvided() {
        GoogleDriveService service = new GoogleDriveService(drive, "test-folder-123");
        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    void isConfigured_returnsFalseWhenFolderIdBlankOrNull() {
        GoogleDriveService service1 = new GoogleDriveService(drive, "");
        assertThat(service1.isConfigured()).isFalse();

        GoogleDriveService service2 = new GoogleDriveService(drive, null);
        assertThat(service2.isConfigured()).isFalse();
    }

    @Test
    void uploadFile_throwsIllegalArgumentExceptionWhenFileNullOrEmpty() {
        GoogleDriveService service = new GoogleDriveService(drive, "test-folder");

        assertThatThrownBy(() -> service.uploadFile(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot upload empty file");

        MockMultipartFile emptyFile = new MockMultipartFile("file", "test.txt", "text/plain", new byte[0]);
        assertThatThrownBy(() -> service.uploadFile(emptyFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot upload empty file");
    }
}
