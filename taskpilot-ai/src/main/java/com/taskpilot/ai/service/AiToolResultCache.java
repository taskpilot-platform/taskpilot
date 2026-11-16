package com.taskpilot.ai.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/** Bounded node-local optimization. A key is usable only after current authorization succeeds. */
@Component
public class AiToolResultCache {
    public record Key(Long userId,Long sessionId,String tool,String arguments,String revision) {}
    private record Value(String result,Instant expiry,Instant lastRefreshAttempt) {}
    private final LinkedHashMap<Key,Value> values=new LinkedHashMap<>();
    private final Clock clock;
    private final int capacity;
    @Autowired public AiToolResultCache() { this(Clock.systemUTC(),1000); }
    public AiToolResultCache(Clock clock,int capacity) { if(capacity<1||capacity>1000)throw new IllegalArgumentException("Invalid cache capacity");this.clock=clock;this.capacity=capacity; }
    public synchronized String get(Key key) {
        Value value=values.get(key);
        if(value==null)return null;
        if(!clock.instant().isBefore(value.expiry)) {values.remove(key);return null;}
        return value.result;
    }
    public synchronized void put(Key key,String result) {
        prune();
        values.put(key,new Value(result,clock.instant().plusSeconds(120),Instant.EPOCH));
        while(values.size()>capacity) values.remove(values.keySet().iterator().next());
    }
    public synchronized void prune() { Instant now=clock.instant();values.entrySet().removeIf(e->!now.isBefore(e.getValue().expiry)); }
    public synchronized void invalidateSession(Long sessionId) {values.keySet().removeIf(k->Objects.equals(sessionId,k.sessionId));}
    public synchronized void remove(Key key) {values.remove(key);}
    public synchronized int size() {return values.size();}
    public synchronized List<Key> claimRefreshes() {
        prune();
        Instant now=clock.instant();List<Key> result=new ArrayList<>();
        for(var entry:values.entrySet()) {
            Value v=entry.getValue();
            if(result.size()<10 && !v.expiry.isAfter(now.plusSeconds(30)) && v.lastRefreshAttempt.isBefore(now.minusSeconds(60))) {
                result.add(entry.getKey());entry.setValue(new Value(v.result,v.expiry,now));
            }
        }
        return List.copyOf(result);
    }
}
