package com.taskpilot.infrastructure.storage.googledrive;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoogleDriveFileDto {
    private String fileId;
    private String name;
    private String mimeType;
    private Long size;
    private String md5Checksum;
    private Instant modifiedTime;
    private String webViewLink;
    private String webContentLink;
}
