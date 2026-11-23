package com.taskpilot.ai.service;

import com.taskpilot.ai.tools.*;
import com.taskpilot.ai.tools.selection.ToolSelectionPolicy;
import com.taskpilot.infrastructure.exception.BusinessException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class P3CacheAuthorizationTest {
    static class TestClock extends Clock {
        Instant now=Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId z){return this;}
        @Override public Instant instant(){return now;}
    }
    @Test void cacheBoundsAndNeverReaccessedExpiredEntriesAreEvicted() {
        var clock=new TestClock();var cache=new AiToolResultCache(clock,1000);
        for(int i=0;i<1001;i++)cache.put(new AiToolResultCache.Key(1L,2L,"t",""+i,"v"),"r");
        assertEquals(1000,cache.size());
        assertNull(cache.get(new AiToolResultCache.Key(1L,2L,"t","0","v")));
        clock.now=clock.now.plusSeconds(120);cache.prune();assertEquals(0,cache.size());
    }
    @Test void everyHitRechecksAuthorityAndRefreshActuallyBypassesSupplierCache() {
        var clock=new TestClock();var cache=new AiToolResultCache(clock,1000);
        var policy=mock(AiToolAccessPolicy.class);when(policy.cacheable("getTaskDetails")).thenReturn(true);
        when(policy.fingerprint(anyString(),anyMap(),anyLong(),anyLong(),eq(false))).thenReturn("rev");
        var registry=new ToolCallingRegistryService(mock(TaskPilotAiTools.class),mock(SmartRoutingService.class),new ToolSelectionPolicy(),policy,cache);
        var calls=new AtomicInteger();ToolExecutor executor=(req,memory)->"result"+calls.incrementAndGet();
        ReflectionTestUtils.setField(registry,"toolExecutors",Map.of("getTaskDetails",executor));
        var req=ToolExecutionRequest.builder().name("getTaskDetails").arguments("{\"taskId\":8}").build();
        ToolExecutionContext.set(new ToolExecutionContext.Context(1L,2L,""));
        try {
            assertEquals("result1",registry.execute(req));assertEquals("result1",registry.execute(req));assertEquals(1,calls.get());
            clock.now=clock.now.plusSeconds(100);registry.refreshActiveCache();
            ToolExecutionContext.set(new ToolExecutionContext.Context(1L,2L,""));
            assertEquals("result2",registry.execute(req));assertEquals(2,calls.get());
            doThrow(new BusinessException(403,"revoked")).when(policy).authorizeSession(1L,2L);
            assertTrue(registry.execute(req).startsWith("Tool execution failed"));assertEquals(2,calls.get());
            registry.refreshActiveCache();
            assertThrows(BusinessException.class,()->registry.reserveWarmup(1L,2L));
        } finally {ToolExecutionContext.clear();}
    }
}
