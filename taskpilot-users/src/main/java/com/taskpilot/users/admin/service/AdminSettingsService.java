package com.taskpilot.users.admin.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.admin.dto.SystemSettingResponse;
import com.taskpilot.users.admin.dto.SystemSettingUpdateRequest;
import com.taskpilot.users.entity.SystemSettingEntity;
import com.taskpilot.users.repository.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminSettingsService {

    private final SystemSettingRepository systemSettingRepository;

    public List<SystemSettingResponse> getAllSettings(String keyword) {
        List<SystemSettingEntity> settings;
        if (keyword != null && !keyword.isBlank()) {
            settings = systemSettingRepository.findByKeyword(keyword.trim());
        } else {
            settings = systemSettingRepository.findAll();
        }
        return settings.stream()
                .map(SystemSettingResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public SystemSettingResponse updateSetting(SystemSettingUpdateRequest request) {
        SystemSettingEntity setting = systemSettingRepository.findById(request.keyName())
                .orElseGet(() -> SystemSettingEntity.builder()
                        .keyName(request.keyName())
                        .valueJson(request.valueJson())
                        .description(request.description())
                        .build());

        setting.setValueJson(request.valueJson());
        if (request.description() != null) {
            setting.setDescription(request.description());
        }

        SystemSettingEntity saved = systemSettingRepository.save(setting);
        return SystemSettingResponse.fromEntity(saved);
    }
}
