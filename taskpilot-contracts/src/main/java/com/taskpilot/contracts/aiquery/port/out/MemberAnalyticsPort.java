package com.taskpilot.contracts.aiquery.port.out;

import com.taskpilot.contracts.aiquery.dto.MemberWorkloadDto;

import java.util.List;

public interface MemberAnalyticsPort {
    List<MemberWorkloadDto> getMemberWorkloadForProject(Long projectId);
    MemberWorkloadDto getMemberWorkload(Long memberId);
}
