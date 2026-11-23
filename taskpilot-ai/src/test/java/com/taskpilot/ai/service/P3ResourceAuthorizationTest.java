package com.taskpilot.ai.service;

import com.taskpilot.ai.assignment.port.out.AiAuditPort;
import com.taskpilot.ai.heuristic.HeuristicStrategyFactory;
import com.taskpilot.contracts.assignment.port.out.*;
import com.taskpilot.infrastructure.exception.BusinessException;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class P3ResourceAuthorizationTest {
    @Test
    void outsiderCannotScoreCandidatesOrReachProfilesModelAndAudit() {
        ProjectMemberPort members = mock(ProjectMemberPort.class);
        UserPort users = mock(UserPort.class);
        UserSkillPort skills = mock(UserSkillPort.class);
        ProjectPort projects = mock(ProjectPort.class);
        AiAuditPort audit = mock(AiAuditPort.class);
        StreamingChatModel model = mock(StreamingChatModel.class);
        HeuristicStrategyFactory strategies = mock(HeuristicStrategyFactory.class);
        TaskPerformanceReadPort metrics = mock(TaskPerformanceReadPort.class);
        AutoAssignmentService service = new AutoAssignmentService(members, metrics, skills, audit, users, projects, strategies, model);
        BusinessException denied = assertThrows(BusinessException.class,
                () -> service.recommendCandidates(91L, List.of("Java"), 3, 12L, Set.of(), Set.of()));
        assertEquals(403, denied.getStatus());
        verify(members).isProjectMember(91L, 12L);
        verify(members, never()).findProjectMembers(any());
        verifyNoInteractions(metrics, users, skills, projects, audit, model, strategies);
    }
}
