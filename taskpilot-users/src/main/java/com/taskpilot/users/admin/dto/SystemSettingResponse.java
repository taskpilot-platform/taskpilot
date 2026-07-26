package com.taskpilot.users.admin.dto;

import com.taskpilot.users.common.entity.SystemSettingEntity;

public record SystemSettingResponse(
        String keyName,
        Object valueJson,
        String description) {
    public static SystemSettingResponse fromEntity(SystemSettingEntity entity) {
        return new SystemSettingResponse(
                entity.getKeyName(),
                entity.getValueJson(),
                entity.getDescription());
    }
}
