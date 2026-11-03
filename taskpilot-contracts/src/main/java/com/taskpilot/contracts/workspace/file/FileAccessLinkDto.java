package com.taskpilot.contracts.workspace.file;

public record FileAccessLinkDto(
    Long fileId,
    String appRoute,
    String downloadApi,
    boolean requiresAuth
) {}
