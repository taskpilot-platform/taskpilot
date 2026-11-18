package com.taskpilot.ai.service;

import com.taskpilot.ai.dto.ConfirmationRequiredDto;
import com.taskpilot.ai.entity.ChatMessageEntity;
import com.taskpilot.ai.repository.ChatMessageRepository;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import com.taskpilot.ai.tools.support.AiToolSupport;
import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.infrastructure.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Only the authenticated explicit endpoint may execute. Model tools cannot manufacture consent. */
@Service
public class PendingAiActionService {
    private static final Duration TTL=Duration.ofMinutes(10);
    private final Map<String,Action> actions=new ConcurrentHashMap<>();
    private final AiToolAccessPolicy policy;
    private final Clock clock;
    private final ChatMessageRepository messages;
    private final ChatMemoryStore memory;
    @Autowired public PendingAiActionService(AiToolAccessPolicy policy, ChatMessageRepository messages, ChatMemoryStore memory) {
        this(policy, Clock.systemUTC(), messages, memory);
    }
    public PendingAiActionService(AiToolAccessPolicy policy) { this(policy, Clock.systemUTC()); }
    public PendingAiActionService(AiToolAccessPolicy policy, Clock clock) { this(policy,clock,null,null); }
    private PendingAiActionService(AiToolAccessPolicy policy, Clock clock, ChatMessageRepository messages, ChatMemoryStore memory) {
        this.policy=Objects.requireNonNull(policy); this.clock=clock; this.messages=messages; this.memory=memory;
    }

    public ConfirmationRequiredDto create(Long userId,Long sessionId,String toolName,String summary,
            Map<String,Object> arguments,Object preview,Supplier<Object> executor) {
        return create(userId, sessionId, toolName, summary, arguments, preview, executor, null);
    }

    public ConfirmationRequiredDto create(Long userId,Long sessionId,String toolName,String summary,
            Map<String,Object> arguments,Object preview,Supplier<Object> executor,Runnable onCancel) {
        var frozen=AiToolSupport.immutableArguments(arguments);
        String revision=policy.fingerprint(toolName,frozen,userId,sessionId,false);
        Object frozenPreview=preview==null?null:AiToolSupport.immutableValue(preview);
        String hash=hash(toolName+":"+userId+":"+sessionId+":"+revision+":"+json(frozen)+":"+json(frozenPreview));
        String id=UUID.randomUUID().toString();
        var dto=new ConfirmationRequiredDto(true,id,toolName,summary,frozen,
                frozenPreview,clock.instant().plus(TTL),hash);
        synchronized(actions) {
            cleanupExpired();
            if(actions.size()>=1000) throw new BusinessException(429,"Too many pending AI actions; cancel an existing action");
            actions.put(id,new Action(userId,sessionId,dto,revision,executor,onCancel));
        }
        return dto;
    }

    public ConfirmationRequiredDto describe(String id,Long userId,Long sessionId) {
        policy.authorizeSession(userId,sessionId);
        Action action=owned(id,userId,sessionId);
        synchronized(action) { requireUsable(action);return action.dto; }
    }

    @Transactional
    public Object confirmExplicit(String id,CurrentActor actor,Long sessionId,String idempotencyKey,String payloadHash) {
        if(actor==null) throw new BusinessException(401,"Authentication required");
        policy.authorizeSession(actor.userId(),sessionId);
        if(idempotencyKey==null || idempotencyKey.isBlank() || idempotencyKey.length()>128)
            throw new BusinessException(400,"A bounded idempotency key is required");
        Action action=owned(id,actor.userId(),sessionId);
        synchronized(action) {
            requireUsable(action);
            if(!Objects.equals(payloadHash,action.dto.payloadHash())) throw new BusinessException(409,"Action preview changed; request a new preview");
            if(action.key!=null && !action.key.equals(idempotencyKey)) throw new BusinessException(409,"Action already claimed by another request");
            if(action.state==State.SUCCEEDED) return action.result;
            if(action.state==State.RUNNING) throw new BusinessException(409,"Action is already executing; retry with the same key");
            action.key=idempotencyKey;action.state=State.RUNNING;
        }
        try {
            String current=policy.fingerprint(action.dto.toolName(),action.dto.arguments(),actor.userId(),sessionId,true);
            if(!Objects.equals(current,action.revision)) throw new BusinessException(409,"Resource or permissions changed; request a new preview");
            Object result=com.taskpilot.ai.tools.ToolExecutionContext.callWith(new com.taskpilot.ai.tools.ToolExecutionContext.Context(actor.userId(),sessionId,"explicit HTTP confirmation",java.util.Collections.emptySet(),actor),action.executor::get);
            if(messages!=null) messages.save(ChatMessageEntity.builder().sessionId(sessionId)
                    .sender(ChatMessageEntity.SenderType.ASSISTANT)
                    .clientMessageId("ai-action:"+id)
                    .content("Đã thực hiện thao tác: " + action.dto.summary()).build());
            if(TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        synchronized(action) {
                            if(status==STATUS_COMMITTED) {
                                action.result=result;action.state=State.SUCCEEDED;
                                if(memory!=null) memory.deleteMessages(sessionId);
                            }
                            else action.state=State.PENDING;
                        }
                    }
                });
            } else synchronized(action) { action.result=result;action.state=State.SUCCEEDED; }
            return result;
        } catch(RuntimeException|Error ex) {
            synchronized(action) { action.state=State.PENDING; }
            throw ex;
        }
    }

    /** Compatibility rejects old model/tool callers rather than retaining a consent bypass. */
    public Object confirm(String id,Long userId,Long sessionId) { throw new BusinessException(403,"Use the explicit action confirmation endpoint"); }
    public Object confirmLatest(Long userId,Long sessionId) { throw new BusinessException(403,"Confirm one explicit action through the application"); }

    public void cancel(String id,Long userId,Long sessionId) {
        policy.authorizeSession(userId,sessionId);
        Action action=owned(id,userId,sessionId);
        Runnable cancelHook = null;
        synchronized(action) {
            if(action.state==State.RUNNING || action.state==State.SUCCEEDED) throw new BusinessException(409,"Action already executing or completed");
            if (action.state == State.PENDING) {
                cancelHook = action.onCancel;
            }
            action.state=State.CANCELLED;
        }
        if (cancelHook != null) {
            cancelHook.run();
        }
    }
    private Action owned(String id,Long userId,Long sessionId) {
        Action action=id==null?null:actions.get(id);
        if(action==null) throw new BusinessException(404,"Pending action unavailable; request a new preview");
        if(!Objects.equals(action.userId,userId)||!Objects.equals(action.sessionId,sessionId)) throw new BusinessException(403,"Pending action does not belong to this session");
        return action;
    }
    private void requireUsable(Action action) {
        if(!clock.instant().isBefore(action.dto.expiresAt()) || action.state==State.CANCELLED)
            throw new BusinessException(410,"Action expired or cancelled; request a new preview");
    }
    @Scheduled(fixedDelay=60000) public void cleanupExpired() {
        Instant now=clock.instant();
        actions.entrySet().removeIf(e -> { synchronized(e.getValue()) { return e.getValue().state!=State.RUNNING&&!now.isBefore(e.getValue().dto.expiresAt()); } });
    }
    private static String json(Object value) {
        try { return AiToolSupport.PATCH_OBJECT_MAPPER.writeValueAsString(value); }
        catch(Exception ex) { throw new IllegalArgumentException("Invalid action payload",ex); }
    }
    private static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception ex) {throw new IllegalStateException(ex);}
    }
    private enum State { PENDING,RUNNING,SUCCEEDED,CANCELLED }
    private static final class Action {
        final Long userId,sessionId; final ConfirmationRequiredDto dto; final String revision; final Supplier<Object> executor; final Runnable onCancel;
        State state=State.PENDING; String key; Object result;
        Action(Long uid,Long sid,ConfirmationRequiredDto dto,String revision,Supplier<Object> executor,Runnable onCancel) {
            this.userId=uid;this.sessionId=sid;this.dto=dto;this.revision=revision;this.executor=executor;this.onCancel=onCancel;
        }
    }
}
