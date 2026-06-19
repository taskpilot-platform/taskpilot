package com.taskpilot.contracts.aiquery.dto;

import java.util.List;
import java.util.Map;

public record SmartQueryResponseDto(
    Map<String, Object> results,      // key -> data (List hoặc single object)
    Map<String, String> errors,       // key -> error message nếu step bị lỗi
    List<ChainStatus> chainStatuses,  // Trạng thái của từng chain chạy song song
    long totalDurationMs              // Tổng thời gian thực thi
) {
    public record ChainStatus(
        int chainIndex,
        int totalSteps,
        int completedSteps,
        long durationMs
    ) {}
}
