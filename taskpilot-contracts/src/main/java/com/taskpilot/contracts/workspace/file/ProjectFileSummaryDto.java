package com.taskpilot.contracts.workspace.file;

import java.time.Instant;

public record ProjectFileSummaryDto(
    Long id,
    Long projectId,
    String name,
    FileCategory category,
    String contentType,
    long sizeBytes,
    String sizeHuman,
    Long uploaderId,
    String uploaderName,
    String description,
    Instant createdAt,
    StorageTier storageTier
) {}
