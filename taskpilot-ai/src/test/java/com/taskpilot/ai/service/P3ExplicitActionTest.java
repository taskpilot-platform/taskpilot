package com.taskpilot.ai.service;

import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.ai.tools.support.AiToolSupport;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class P3ExplicitActionTest {
    private final CurrentActor owner=new CurrentActor(1L,"a@example.com","USER",0L);
    private AiToolAccessPolicy policy() {
        var p=mock(AiToolAccessPolicy.class);
        when(p.fingerprint(anyString(),anyMap(),anyLong(),anyLong(),anyBoolean())).thenReturn("revision1");
        return p;
    }
    @Test void textAndLegacyToolsNeverSupplyConsent() {
        for(String text:List.of("khong dong y","do not confirm","explain the token","ok","CONFIRM_ACTION "+UUID.randomUUID(),UUID.randomUUID().toString()))
            assertFalse(AiToolSupport.isConfirmationInput(text));
        var p=new PendingAiActionService(policy());var calls=new AtomicInteger();
        var action=p.create(1L,2L,"patchTask","preview",Map.of(),null,calls::incrementAndGet);
        assertThrows(BusinessException.class,()->p.confirm(action.actionId(),1L,2L));
        assertThrows(BusinessException.class,()->p.confirmLatest(1L,2L));
        assertEquals(0,calls.get());
    }
    @Test void wrongOwnerAndWrongSessionNeverConsumeAndSameKeyReplaysOnlyOnce() {
        var p=new PendingAiActionService(policy());var calls=new AtomicInteger();
        var action=p.create(1L,2L,"patchTask","preview",Map.of("taskId",8),null,calls::incrementAndGet);
        assertThrows(BusinessException.class,()->p.confirmExplicit(action.actionId(),new CurrentActor(3,"b","USER",0),2L,"k",action.payloadHash()));
        assertThrows(BusinessException.class,()->p.cancel(action.actionId(),1L,3L));
        assertEquals(1,p.confirmExplicit(action.actionId(),owner,2L,"k",action.payloadHash()));
        assertEquals(1,p.confirmExplicit(action.actionId(),owner,2L,"k",action.payloadHash()));
        assertThrows(BusinessException.class,()->p.confirmExplicit(action.actionId(),owner,2L,"other",action.payloadHash()));
        assertEquals(1,calls.get());
    }
    @Test void changedRevisionOrPayloadRejectsWithoutExecutingAndPreviewIsDeeplyImmutable() {
        var policy=policy();var p=new PendingAiActionService(policy);var calls=new AtomicInteger();
        var list=new ArrayList<>(List.of(7L));var patch=new HashMap<String,Object>();patch.put("labelIds",list);
        var action=p.create(1L,2L,"patchTask","preview",Map.of("patch",patch),patch,calls::incrementAndGet);
        list.add(8L);assertEquals(List.of(7L),((Map<?,?>)action.arguments().get("patch")).get("labelIds"));
        assertThrows(UnsupportedOperationException.class,()->action.arguments().put("x",1));
        assertThrows(BusinessException.class,()->p.confirmExplicit(action.actionId(),owner,2L,"k","wrong"));
        when(policy.fingerprint(anyString(),anyMap(),anyLong(),anyLong(),eq(true))).thenReturn("revision2");
        assertThrows(BusinessException.class,()->p.confirmExplicit(action.actionId(),owner,2L,"k",action.payloadHash()));
        assertEquals(0,calls.get());
    }
    @Test void concurrentDoubleClickHasOneExecutorAndCancelledActionCannotExecute() throws Exception {
        var p=new PendingAiActionService(policy());var calls=new AtomicInteger();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var action=p.create(1L,2L,"patchTask","preview",Map.of(),null,()->{
            calls.incrementAndGet();entered.countDown();try {assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return "done";
        });
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=pool.submit(()->p.confirmExplicit(action.actionId(),owner,2L,"k",action.payloadHash()));
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            try {assertThrows(BusinessException.class,()->p.confirmExplicit(action.actionId(),owner,2L,"k",action.payloadHash()));}
            finally {release.countDown();}
            assertEquals("done",first.get(3,TimeUnit.SECONDS));
        }
        assertEquals(1,calls.get());
        var cancel=p.create(1L,2L,"patchTask","preview",Map.of(),null,calls::incrementAndGet);
        p.cancel(cancel.actionId(),1L,2L);
        assertThrows(BusinessException.class,()->p.confirmExplicit(cancel.actionId(),owner,2L,"c",cancel.payloadHash()));
        assertEquals(1,calls.get());
    }
    @Test void expiryAndRestartRequireNewPreviewWithoutExecuting() {
        var clock=new P3CacheAuthorizationTest.TestClock();var policy=policy();
        var p=new PendingAiActionService(policy,clock);var calls=new AtomicInteger();
        var action=p.create(1L,2L,"patchTask","preview",Map.of(),null,calls::incrementAndGet);
        clock.now=clock.now.plusSeconds(600);
        var expired=assertThrows(BusinessException.class,()->p.confirmExplicit(action.actionId(),owner,2L,"k",action.payloadHash()));
        assertEquals(410,expired.getStatus());
        p.cleanupExpired();assertThrows(BusinessException.class,()->p.describe(action.actionId(),1L,2L));
        var restarted=new PendingAiActionService(policy,clock);
        assertThrows(BusinessException.class,()->restarted.confirmExplicit(action.actionId(),owner,2L,"k",action.payloadHash()));
        assertEquals(0,calls.get());
    }
}
