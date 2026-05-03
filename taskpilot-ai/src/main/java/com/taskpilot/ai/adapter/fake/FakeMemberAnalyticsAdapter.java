package com.taskpilot.ai.adapter.fake;

import com.taskpilot.contracts.aiquery.dto.MemberWorkloadDto;
import com.taskpilot.contracts.aiquery.port.out.MemberAnalyticsPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@Profile("ai-dev")
public class FakeMemberAnalyticsAdapter implements MemberAnalyticsPort {

    @Override
    public List<MemberWorkloadDto> getMemberWorkloadForProject(Long projectId) {
        log.info("[FakeAdapter] getMemberWorkloadForProject called for projectId={}", projectId);
        return ScenarioFixtures.getMemberWorkloads(projectId);
    }

    @Override
    public MemberWorkloadDto getMemberWorkload(Long memberId) {
        log.info("[FakeAdapter] getMemberWorkload called for memberId={}", memberId);
        return ScenarioFixtures.getSingleMemberWorkload(memberId);
    }
}
