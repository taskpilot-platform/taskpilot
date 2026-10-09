package com.taskpilot.infrastructure.storage.googledrive;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoogleDriveFileDto {
    private String fileId;
    private String name;
    private String mimeType;
    private Long size;
    private String webViewLink;
    private String webContentLink;
}
