package com.taskpilot.ai.service;
import com.taskpilot.ai.entity.AiLogEntity;
import com.taskpilot.ai.repository.AiLogRepository;
import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.infrastructure.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class P3FeedbackOwnershipTest {
    @Test void missingLogAndForeignOwnerFailWithoutMutationEvenForGlobalAdmin() {
        var repo=mock(AiLogRepository.class);var service=new AiLogService(repo);
        var entry=AiLogEntity.builder().id(1L).userId(7L).humanFeedback("PENDING").build();
        when(repo.findById(1L)).thenReturn(Optional.of(entry));
        assertThrows(BusinessException.class,()->service.updateFeedback(1L,"ACCEPTED",new CurrentActor(8,"b","ADMIN",0)));
        assertEquals("PENDING",entry.getHumanFeedback());verify(repo,never()).save(any());
        assertEquals(404,assertThrows(BusinessException.class,()->service.updateFeedback(9L,"ACCEPTED",new CurrentActor(7,"a","USER",0))).getStatus());
        service.updateFeedback(1L,"REJECTED",new CurrentActor(7,"a","USER",0));verify(repo).save(entry);
    }
}
