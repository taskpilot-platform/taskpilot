package com.taskpilot.ai.service;
import com.taskpilot.contracts.user.dto.CurrentActor;
/** Simulates the authenticated application endpoint for domain-tool tests; never natural-language consent. */
public final class P3TestActions {
    private P3TestActions() {}
    public static Object confirm(PendingAiActionService service,String id,Long uid,Long sid) {
        var dto=service.describe(id,uid,sid);
        return service.confirmExplicit(id,new CurrentActor(uid,"test@example.com","USER",0L),sid,"test-"+id,dto.payloadHash());
    }
}
